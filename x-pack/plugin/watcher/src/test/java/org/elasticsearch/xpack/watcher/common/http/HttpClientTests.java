/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.common.http;

import com.carrotsearch.randomizedtesting.generators.RandomStrings;
import org.apache.http.HttpHeaders;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.mocksocket.MockServerSocket;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.http.MockResponse;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.xpack.core.XPackSettings;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.core.ssl.TestsSSLService;
import org.elasticsearch.xpack.core.ssl.VerificationMode;
import org.junit.After;
import org.junit.Before;

import javax.net.ssl.SSLContext;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.Is.is;

public class HttpClientTests extends ESTestCase {

    private MockWebServer webServer = new MockWebServer();
    private HttpClient httpClient;
    private Environment environment = TestEnvironment.newEnvironment(Settings.builder().put("path.home", createTempDir()).build());

    @Before
    public void init() throws Exception {
        webServer.start();
        httpClient = new HttpClient(Settings.EMPTY, new SSLService(environment.settings(), environment), null);
    }

    @After
    public void shutdown() throws Exception {
        webServer.close();
        httpClient.close();
    }

    public void testBasics() throws Exception {
        int responseCode = randomIntBetween(200, 203);
        String body = randomAlphaOfLengthBetween(2, 8096);
        webServer.enqueue(new MockResponse().setResponseCode(responseCode).setBody(body));

        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/" + randomAlphaOfLength(5));

        String paramKey = randomAlphaOfLength(3);
        String paramValue = randomAlphaOfLength(3);
        requestBuilder.setParam(paramKey, paramValue);

        // Certain headers keys like via and host are illegal and the jdk http client ignores those, so lets
        // prepend all keys with `_`, so we don't run into a failure because randomly a restricted header was used:
        String headerKey = "_" + randomAlphaOfLength(3);
        String headerValue = randomAlphaOfLength(3);
        requestBuilder.setHeader(headerKey, headerValue);

        requestBuilder.body(randomAlphaOfLength(5));
        HttpRequest request = requestBuilder.build();

        HttpResponse response = httpClient.execute(request);
        assertThat(response.status(), equalTo(responseCode));
        assertThat(response.body().utf8ToString(), equalTo(body));
        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), equalTo(request.path()));
        assertThat(webServer.requests().get(0).getUri().getQuery(), equalTo(paramKey + "=" + paramValue));
        assertThat(webServer.requests().get(0).getHeader(headerKey), equalTo(headerValue));
    }

    public void testNoQueryString() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.GET)
                .path("/test");

        HttpResponse response = httpClient.execute(requestBuilder.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getBody(), is(nullValue()));
    }

    public void testUrlEncodingWithQueryStrings() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.GET)
                .path("/test")
                .setParam("key", "value 123:123");

        HttpResponse response = httpClient.execute(requestBuilder.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getUri().getRawQuery(), is("key=value+123%3A123"));
        assertThat(webServer.requests().get(0).getBody(), is(nullValue()));
    }

    public void testBasicAuth() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/test")
                .auth(new BasicAuth("user", "pass".toCharArray()))
                .body("body");
        HttpResponse response = httpClient.execute(request.build());
        assertThat(response.status(), equalTo(200));
        assertThat(response.body().utf8ToString(), equalTo("body"));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/test"));
        assertThat(webServer.requests().get(0).getHeader("Authorization"), is("Basic dXNlcjpwYXNz"));
    }

    public void testNoPathSpecified() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("doesntmatter"));
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort()).method(HttpMethod.GET);
        httpClient.execute(request.build());

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/"));
    }

    public void testHttps() throws Exception {
        Path trustedCertPath = getDataPath("/org/elasticsearch/xpack/security/keystore/truststore-testnode-only.crt");
        Path certPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.crt");
        Path keyPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.pem");
        MockSecureSettings secureSettings = new MockSecureSettings();
        final boolean noFallback = randomBoolean();
        Settings settings;
        if (noFallback) {
            settings = Settings.builder()
                .put("xpack.http.ssl.certificate_authorities", trustedCertPath)
                .setSecureSettings(secureSettings)
                .build();
        } else {
            settings = Settings.builder()
                .put("xpack.ssl.certificate_authorities", trustedCertPath)
                .setSecureSettings(secureSettings)
                .build();
        }
        try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
            secureSettings = new MockSecureSettings();
            // We can't use the client created above for the server since it is only a truststore
            secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
            Settings settings2 = Settings.builder()
                .put("xpack.ssl.key", keyPath)
                .put("xpack.ssl.certificate", certPath)
                .put("xpack.watcher.enabled", false) // avoid deprecation warning since this is used for the server
                .setSecureSettings(secureSettings)
                .build();

            TestsSSLService sslService = new TestsSSLService(settings2, environment);
            testSslMockWebserver(client, sslService.sslContext(), false);
        }

        if (noFallback == false) {
            assertWarnings("SSL configuration [xpack.http.ssl] relies upon fallback to another configuration for [" +
                "trust configuration], which is deprecated.");
        }
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/72396")
    public void testHttpsWithTLSv1GeneratesWarnings() throws Exception {
        Path trustedCertPath = getDataPath("/org/elasticsearch/xpack/security/keystore/truststore-testnode-only.crt");
        Path certPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.crt");
        Path keyPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.pem");
        MockSecureSettings secureSettings = new MockSecureSettings();
        Settings settings = Settings.builder()
                .put("xpack.http.ssl.certificate_authorities", trustedCertPath)
                .setSecureSettings(secureSettings)
                .build();

        try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
            secureSettings = new MockSecureSettings();
            secureSettings.setString("xpack.security.http.ssl.secure_key_passphrase", "testnode");
            Settings settings2 = Settings.builder()
                .put("xpack.security.http.ssl.key", keyPath)
                .put("xpack.security.http.ssl.certificate", certPath)
                .put("xpack.watcher.enabled", false)
                .setSecureSettings(secureSettings)
                .build();

            TestsSSLService sslService = new TestsSSLService(settings2, environment);
            testSslMockWebserver(client, sslService.sslContext("xpack.security.http.ssl."), false, Collections.singletonList("TLSv1"));
        }

        assertWarnings("a TLS v1.0 session was used for [http connection to localhost]," +
            " this protocol will be disabled by default in a future version." +
            " The [xpack.http.ssl.supported_protocols] setting can be used to control this.");
    }

    public void testHttpsDisableHostnameVerification() throws Exception {
        Path certPath = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-no-subjaltname.crt");
        Path keyPath = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode-no-subjaltname.pem");
        final boolean useHttpSslSettings = randomBoolean();
        Settings settings;
        if (useHttpSslSettings) {
            Settings.Builder builder = Settings.builder()
                .put("xpack.http.ssl.certificate_authorities", certPath);
            if (inFipsJvm()) {
                //Can't use TrustAllConfig in FIPS mode
                builder.put("xpack.http.ssl.verification_mode", VerificationMode.CERTIFICATE);
            } else {
                builder.put("xpack.http.ssl.verification_mode", randomFrom(VerificationMode.NONE, VerificationMode.CERTIFICATE));
            }
            settings = builder.build();
        } else {
            Settings.Builder builder = Settings.builder()
                .put("xpack.ssl.certificate_authorities", certPath);
            if (inFipsJvm()) {
                //Can't use TrustAllConfig in FIPS mode
                builder.put("xpack.ssl.verification_mode", VerificationMode.CERTIFICATE);
            } else {
                builder.put("xpack.ssl.verification_mode", randomFrom(VerificationMode.NONE, VerificationMode.CERTIFICATE));
            }
            settings = builder.build();
        }
        try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
            MockSecureSettings secureSettings = new MockSecureSettings();
            // We can't use the client created above for the server since it only defines a truststore
            secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode-no-subjaltname");
            Settings settings2 = Settings.builder()
                .put("xpack.ssl.key", keyPath)
                .put("xpack.ssl.certificate", certPath)
                .put("xpack.watcher.enabled", false) // avoid deprecation warning since this is used for the server
                .setSecureSettings(secureSettings)
                .build();

            TestsSSLService sslService = new TestsSSLService(settings2, environment);
            testSslMockWebserver(client, sslService.sslContext(), false);
        }

        if (useHttpSslSettings == false) {
            assertWarnings("SSL configuration [xpack.http.ssl] relies upon fallback to another configuration for [" +
                "trust configuration, certificate verification mode], which is deprecated.");
        }
    }

    public void testHttpsClientAuth() throws Exception {
        Path certPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.crt");
        Path keyPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.pem");
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        secureSettings.setString("xpack.http.ssl.secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("xpack.ssl.key", keyPath)
            .put("xpack.ssl.certificate", certPath)
            .put("xpack.http.ssl.key", keyPath)
            .put("xpack.http.ssl.certificate", certPath)
            .setSecureSettings(secureSettings)
            .build();

        TestsSSLService sslService = new TestsSSLService(settings, environment);
        try (HttpClient client = new HttpClient(settings, sslService, null)) {
            testSslMockWebserver(client, sslService.sslContext(), true);
        }
    }

    private void testSslMockWebserver(HttpClient client, SSLContext sslContext, boolean needClientAuth) throws IOException {
        testSslMockWebserver(client, sslContext, needClientAuth, XPackSettings.DEFAULT_SUPPORTED_PROTOCOLS);
    }

    private void testSslMockWebserver(HttpClient client, SSLContext sslContext, boolean needClientAuth, List<String> supportedProtocols)
        throws IOException {
        try (MockWebServer mockWebServer = new MockWebServer(sslContext, needClientAuth, supportedProtocols)) {
            mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
            mockWebServer.start();

            HttpRequest.Builder request = HttpRequest.builder("localhost", mockWebServer.getPort())
                    .scheme(Scheme.HTTPS)
                    .path("/test");
            HttpResponse response = client.execute(request.build());
            assertThat(response.status(), equalTo(200));
            assertThat(response.body().utf8ToString(), equalTo("body"));

            assertThat(mockWebServer.requests(), hasSize(1));
            assertThat(mockWebServer.requests().get(0).getUri().getPath(), is("/test"));
        }
    }

    public void testHttpResponseWithAnyStatusCodeCanReturnBody() throws Exception {
        int statusCode = randomFrom(200, 201, 400, 401, 403, 404, 405, 409, 413, 429, 500, 503);
        String body = RandomStrings.randomAsciiOfLength(random(), 100);
        boolean hasBody = usually();
        MockResponse mockResponse = new MockResponse().setResponseCode(statusCode);
        if (hasBody) {
            mockResponse.setBody(body);
        }
        webServer.enqueue(mockResponse);
        HttpRequest.Builder request = HttpRequest.builder("localhost", webServer.getPort())
                .method(HttpMethod.POST)
                .path("/test")
                .auth(new BasicAuth("user", "pass".toCharArray()))
                .body("body")
                .connectionTimeout(TimeValue.timeValueMillis(500))
                .readTimeout(TimeValue.timeValueMillis(500));
        HttpResponse response = httpClient.execute(request.build());
        assertThat(response.status(), equalTo(statusCode));
        assertThat(response.hasContent(), is(hasBody));
        if (hasBody) {
            assertThat(response.body().utf8ToString(), is(body));
        }
    }

    @Network
    public void testHttpsWithoutTruststore() throws Exception {
        try (HttpClient client = new HttpClient(Settings.EMPTY, new SSLService(Settings.EMPTY, environment), null)) {
            // Known server with a valid cert from a commercial CA
            HttpRequest.Builder request = HttpRequest.builder("www.elastic.co", 443).scheme(Scheme.HTTPS);
            HttpResponse response = client.execute(request.build());
            assertThat(response.status(), equalTo(200));
            assertThat(response.hasContent(), is(true));
            assertThat(response.body(), notNullValue());
        }
    }

    public void testThatProxyCanBeConfigured() throws Exception {
        // this test fakes a proxy server that sends a response instead of forwarding it to the mock web server
        try (MockWebServer proxyServer = new MockWebServer()) {
            proxyServer.enqueue(new MockResponse().setResponseCode(200).setBody("fullProxiedContent"));
            proxyServer.start();
            Settings settings = Settings.builder()
                    .put(HttpSettings.PROXY_HOST.getKey(), "localhost")
                    .put(HttpSettings.PROXY_PORT.getKey(), proxyServer.getPort())
                    .build();

            HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                    .method(HttpMethod.GET)
                    .path("/");

            try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
                HttpResponse response = client.execute(requestBuilder.build());
                assertThat(response.status(), equalTo(200));
                assertThat(response.body().utf8ToString(), equalTo("fullProxiedContent"));
            }

            // ensure we hit the proxyServer and not the webserver
            assertThat(webServer.requests(), hasSize(0));
            assertThat(proxyServer.requests(), hasSize(1));
        }
    }

    public void testSetProxy() throws Exception {
        HttpProxy localhostHttpProxy = new HttpProxy("localhost", 1234, Scheme.HTTP);
        RequestConfig.Builder config = RequestConfig.custom();

        // no proxy configured at all
        HttpClient.setProxy(config, HttpRequest.builder().fromUrl("https://elastic.co").build(), HttpProxy.NO_PROXY);
        assertThat(config.build().getProxy(), is(nullValue()));

        // no system wide proxy configured, proxy in request
        config = RequestConfig.custom();
        HttpClient.setProxy(config,
                HttpRequest.builder().fromUrl("https://elastic.co").proxy(new HttpProxy("localhost", 23456)).build(),
                HttpProxy.NO_PROXY);
        assertThat(config.build().getProxy().toString(), is("http://localhost:23456"));

        // system wide proxy configured, no proxy in request
        config = RequestConfig.custom();
        HttpClient.setProxy(config, HttpRequest.builder().fromUrl("https://elastic.co").build(),
                localhostHttpProxy);
        assertThat(config.build().getProxy().toString(), is("http://localhost:1234"));

        // proxy in request, no system wide proxy configured. request
        config = RequestConfig.custom();
        HttpClient.setProxy(config,
                HttpRequest.builder().fromUrl("https://elastic.co").proxy(new HttpProxy("localhost", 23456, Scheme.HTTP)).build(),
                HttpProxy.NO_PROXY);
        assertThat(config.build().getProxy().toString(), is("http://localhost:23456"));

        // proxy in request, system wide proxy configured. request wins
        config = RequestConfig.custom();
        HttpClient.setProxy(config,
                HttpRequest.builder().fromUrl("http://elastic.co").proxy(new HttpProxy("localhost", 23456, Scheme.HTTPS)).build(),
                localhostHttpProxy);
        assertThat(config.build().getProxy().toString(), is("https://localhost:23456"));
    }

    public void testProxyCanHaveDifferentSchemeThanRequest() throws Exception {
        Path trustedCertPath = getDataPath("/org/elasticsearch/xpack/security/keystore/truststore-testnode-only.crt");
        Path certPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.crt");
        Path keyPath = getDataPath("/org/elasticsearch/xpack/security/keystore/testnode.pem");
        // this test fakes a proxy server that sends a response instead of forwarding it to the mock web server
        // on top of that the proxy request is HTTPS but the real request is HTTP only
        MockSecureSettings serverSecureSettings = new MockSecureSettings();
        // We can't use the client created above for the server since it is only a truststore
        serverSecureSettings.setString("xpack.ssl.secure_key_passphrase", "testnode");
        Settings serverSettings = Settings.builder()
            .put("xpack.ssl.key", keyPath)
            .put("xpack.ssl.certificate", certPath)
            .put("xpack.watcher.enabled", false) // avoid deprecation warning since this is used for the server
            .setSecureSettings(serverSecureSettings)
            .build();
        TestsSSLService sslService = new TestsSSLService(serverSettings, environment);

        try (MockWebServer proxyServer = new MockWebServer(sslService.sslContext(), false)) {
            proxyServer.enqueue(new MockResponse().setResponseCode(200).setBody("fullProxiedContent"));
            proxyServer.start();

            MockSecureSettings secureSettings = new MockSecureSettings();
            secureSettings.setString("xpack.http.ssl.secure_key_passphrase", "testnode");
            Settings settings = Settings.builder()
                .put(HttpSettings.PROXY_HOST.getKey(), "localhost")
                .put(HttpSettings.PROXY_PORT.getKey(), proxyServer.getPort())
                .put(HttpSettings.PROXY_SCHEME.getKey(), "https")
                .put("xpack.http.ssl.key", keyPath)
                .put("xpack.http.ssl.certificate", certPath)
                .put("xpack.http.ssl.certificate_authorities", trustedCertPath)
                .setSecureSettings(secureSettings)
                .build();

            HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                    .method(HttpMethod.GET)
                    .scheme(Scheme.HTTP)
                    .path("/");

            try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
                HttpResponse response = client.execute(requestBuilder.build());
                assertThat(response.status(), equalTo(200));
                assertThat(response.body().utf8ToString(), equalTo("fullProxiedContent"));
            }

            // ensure we hit the proxyServer and not the webserver
            assertThat(webServer.requests(), hasSize(0));
            assertThat(proxyServer.requests(), hasSize(1));
        }
    }

    public void testThatProxyCanBeOverriddenByRequest() throws Exception {
        // this test fakes a proxy server that sends a response instead of forwarding it to the mock web server
        try (MockWebServer proxyServer = new MockWebServer()) {
            proxyServer.enqueue(new MockResponse().setResponseCode(200).setBody("fullProxiedContent"));
            proxyServer.start();
            Settings settings = Settings.builder()
                    .put(HttpSettings.PROXY_HOST.getKey(), "localhost")
                    .put(HttpSettings.PROXY_PORT.getKey(), proxyServer.getPort() + 1)
                    .put(HttpSettings.PROXY_HOST.getKey(), "https")
                    .build();

            HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort())
                    .method(HttpMethod.GET)
                    .proxy(new HttpProxy("localhost", proxyServer.getPort(), Scheme.HTTP))
                    .path("/");

            try (HttpClient client = new HttpClient(settings, new SSLService(settings, environment), null)) {
                HttpResponse response = client.execute(requestBuilder.build());
                assertThat(response.status(), equalTo(200));
                assertThat(response.body().utf8ToString(), equalTo("fullProxiedContent"));
            }

            // ensure we hit the proxyServer and not the webserver
            assertThat(webServer.requests(), hasSize(0));
            assertThat(proxyServer.requests(), hasSize(1));
        }
    }

    public void testThatProxyConfigurationRequiresHostAndPort() {
        Settings.Builder settings = Settings.builder();
        if (randomBoolean()) {
            settings.put(HttpSettings.PROXY_HOST.getKey(), "localhost");
        } else {
            settings.put(HttpSettings.PROXY_PORT.getKey(), 8080);
        }

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> new HttpClient(settings.build(), new SSLService(settings.build(), environment), null));
        assertThat(e.getMessage(),
                containsString("HTTP proxy requires both settings: [xpack.http.proxy.host] and [xpack.http.proxy.port]"));
    }

    public void testThatUrlPathIsNotEncoded() throws Exception {
        // %2F is a slash that needs to be encoded to not be misinterpreted as a path
        String path = "/%3Clogstash-%7Bnow%2Fd%7D%3E/_search";
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
        HttpRequest request;
        if (randomBoolean()) {
            request = HttpRequest.builder("localhost", webServer.getPort()).path(path).build();
        } else {
            // ensure that fromUrl acts the same way than the above builder
            request = HttpRequest.builder().fromUrl(String.format(Locale.ROOT, "http://localhost:%s%s", webServer.getPort(), path)).build();
        }
        httpClient.execute(request);

        assertThat(webServer.requests(), hasSize(1));

        // under no circumstances have a double encode of %2F => %25 (percent sign)
        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getRawPath(), not(containsString("%25")));
        assertThat(webServer.requests().get(0).getUri().getPath(), is("/<logstash-{now/d}>/_search"));
    }

    public void testThatDuplicateHeaderKeysAreReturned() throws Exception {
        MockResponse mockResponse = new MockResponse().setResponseCode(200).setBody("foo")
                .addHeader("foo", "bar")
                .addHeader("foo", "baz")
                .addHeader("Content-Length", "3");
        webServer.enqueue(mockResponse);

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path("/").build();
        HttpResponse httpResponse = httpClient.execute(request);

        assertThat(webServer.requests(), hasSize(1));

        assertThat(httpResponse.headers(), hasKey("foo"));
        assertThat(httpResponse.headers().get("foo"), containsInAnyOrder("bar", "baz"));
    }

    // finally fixing https://github.com/elastic/x-plugins/issues/1141 - yay! Fixed due to switching to apache http client internally!
    public void testThatClientTakesTimeoutsIntoAccountAfterHeadersAreSent() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("foo").setBodyDelay(TimeValue.timeValueSeconds(2)));

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path("/foo")
                .method(HttpMethod.POST)
                .body("foo")
                .connectionTimeout(TimeValue.timeValueMillis(500))
                .readTimeout(TimeValue.timeValueMillis(500))
                .build();
        SocketTimeoutException e = expectThrows(SocketTimeoutException.class, () -> httpClient.execute(request));
        assertThat(e.getMessage(), is("Read timed out"));
    }

    public void testThatHttpClientFailsOnNonHttpResponse() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Exception> hasExceptionHappened = new AtomicReference();
        try (ServerSocket serverSocket = new MockServerSocket(0, 50, InetAddress.getByName("localhost"))) {
            executor.execute(() -> {
                try (Socket socket = serverSocket.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    in.readLine();
                    socket.getOutputStream().write("This is not an HTTP response".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (Exception e) {
                    hasExceptionHappened.set(e);
                    logger.error((Supplier<?>) () -> new ParameterizedMessage("Error in writing non HTTP response"), e);
                }
            });
            HttpRequest request = HttpRequest.builder("localhost", serverSocket.getLocalPort()).path("/").build();
            expectThrows(ClientProtocolException.class, () -> httpClient.execute(request));
            assertThat("A server side exception occurred, but shouldn't", hasExceptionHappened.get(), is(nullValue()));
        } finally {
            terminate(executor);
        }
    }

    public void testNoContentResponse() throws Exception {
        int noContentStatusCode = 204;
        webServer.enqueue(new MockResponse().setResponseCode(noContentStatusCode));
        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path("/foo").build();
        HttpResponse response = httpClient.execute(request);
        assertThat(response.status(), is(noContentStatusCode));
        assertThat(response.body(), is(nullValue()));
    }

    public void testMaxHttpResponseSize() throws Exception {
        int randomBytesLength = scaledRandomIntBetween(2, 100);
        String data = randomAlphaOfLength(randomBytesLength);
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody(data));

        Settings settings = Settings.builder()
                .put(HttpSettings.MAX_HTTP_RESPONSE_SIZE.getKey(), new ByteSizeValue(randomBytesLength - 1, ByteSizeUnit.BYTES))
                .build();

        HttpRequest.Builder requestBuilder = HttpRequest.builder("localhost", webServer.getPort()).method(HttpMethod.GET).path("/");

        try (HttpClient client = new HttpClient(settings, new SSLService(environment.settings(), environment), null)) {
            IOException e = expectThrows(IOException.class, () -> client.execute(requestBuilder.build()));
            assertThat(e.getMessage(), startsWith("Maximum limit of"));
        }
    }

    public void testThatGetRedirectIsFollowed() throws Exception {
        String redirectUrl = "http://" + webServer.getHostName() + ":" + webServer.getPort() + "/foo";
        webServer.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", redirectUrl));
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.HEAD);

        if (method == HttpMethod.GET) {
            webServer.enqueue(new MockResponse().setResponseCode(200).setBody("shouldBeRead"));
        } else if (method == HttpMethod.HEAD) {
            webServer.enqueue(new MockResponse().setResponseCode(200));
        }

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path("/")
                .method(method)
                .build();
        HttpResponse response = httpClient.execute(request);

        assertThat(webServer.requests(), hasSize(2));
        if (method == HttpMethod.GET) {
            assertThat(response.body().utf8ToString(), is("shouldBeRead"));
        } else if (method == HttpMethod.HEAD) {
            assertThat(response.body(), is(nullValue()));
        }
    }

    // not allowed by RFC, only allowed for GET or HEAD
    public void testThatPostRedirectIsNotFollowed() throws Exception {
        String redirectUrl = "http://" + webServer.getHostName() + ":" + webServer.getPort() + "/foo";
        webServer.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", redirectUrl));
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("shouldNeverBeRead"));

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort()).path("/").method(HttpMethod.POST).build();
        HttpResponse response = httpClient.execute(request);
        assertThat(response.body(), is(nullValue()));
        assertThat(webServer.requests(), hasSize(1));
    }

    public void testThatBodyWithUTF8Content() throws Exception {
        String body = "title あいうえお";
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody(body));

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort())
                .path("/")
                .setHeader(HttpHeaders.CONTENT_TYPE, XContentType.JSON.mediaType())
                .body(body)
                .build();
        HttpResponse response = httpClient.execute(request);
        assertThat(response.body().utf8ToString(), is(body));

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getHeader(HttpHeaders.CONTENT_TYPE), is(XContentType.JSON.mediaType()));
        assertThat(webServer.requests().get(0).getBody(), is(body));
    }

    public void testThatUrlDoesNotContainQuestionMarkAtTheEnd() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("whatever"));

        HttpRequest request = HttpRequest.builder("localhost", webServer.getPort())
                        .path("foo")
                        .build();
        httpClient.execute(request);
        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getRawPath(), is("/foo"));
    }
}
