/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.rest;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.rest.RestRequest;

import java.io.IOException;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;

/**
 * Identifies an object that supplies a filter for the content of a {@link RestRequest}. This interface should be implemented by a
 * {@link org.elasticsearch.rest.RestHandler} that expects there will be sensitive content in the body of the request such as a password
 */
public interface RestRequestFilter {

    /**
     * Wraps the RestRequest and returns a version that provides the filtered content
     */
    default RestRequest getFilteredRequest(RestRequest restRequest) throws IOException {
        Set<String> fields = getFilteredFields();
        if (restRequest.hasContent() && fields.isEmpty() == false) {
            return new RestRequest(restRequest.getXContentRegistry(), restRequest.params(), restRequest.path(), restRequest.getHeaders()) {

                private BytesReference filteredBytes = null;

                @Override
                public Method method() {
                    return restRequest.method();
                }

                @Override
                public String uri() {
                    return restRequest.uri();
                }

                @Override
                public boolean hasContent() {
                    return true;
                }

                @Nullable
                @Override
                public SocketAddress getRemoteAddress() {
                    return restRequest.getRemoteAddress();
                }

                @Nullable
                @Override
                public SocketAddress getLocalAddress() {
                    return restRequest.getLocalAddress();
                }

                @Override
                public BytesReference innerContent() {
                    if (filteredBytes == null) {
                        BytesReference content = restRequest.content();
                        Tuple<XContentType, Map<String, Object>> result = XContentHelper.convertToMap(content, true);
                        Map<String, Object> transformedSource = XContentMapValues.filter(result.v2(), null,
                                fields.toArray(Strings.EMPTY_ARRAY));
                        try {
                            XContentBuilder xContentBuilder = XContentBuilder.builder(result.v1().xContent()).map(transformedSource);
                            filteredBytes = BytesReference.bytes(xContentBuilder);
                        } catch (IOException e) {
                            throw new ElasticsearchException("failed to parse request", e);
                        }
                    }
                    return filteredBytes;
                }
            };
        } else {
            return restRequest;
        }
    }

    /**
     * The list of fields that should be filtered. This can be a dot separated pattern to match sub objects and also supports wildcards
     */
    Set<String> getFilteredFields();
}
