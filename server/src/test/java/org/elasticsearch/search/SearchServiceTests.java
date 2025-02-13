/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search;

import com.carrotsearch.hppc.IntArrayList;
import org.apache.lucene.search.Query;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchTask;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.AbstractQueryBuilder;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.MatchNoneQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryRewriteContext;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.index.search.stats.SearchStats;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.SearchOperationListener;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.settings.InternalOrPrivateSettingsPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.script.MockScriptEngine;
import org.elasticsearch.script.MockScriptPlugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.MultiBucketConsumerService;
import org.elasticsearch.search.aggregations.bucket.global.GlobalAggregationBuilder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.search.aggregations.support.ValueType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.ShardFetchRequest;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.internal.ShardSearchLocalRequest;
import org.elasticsearch.search.internal.ShardSearchTransportRequest;
import org.elasticsearch.search.suggest.SuggestBuilder;
import org.elasticsearch.test.ESSingleNodeTestCase;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static java.util.Collections.singletonList;
import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.indices.cluster.IndicesClusterStateService.AllocatedIndices.IndexRemovalReason.DELETED;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchHits;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.startsWith;

public class SearchServiceTests extends ESSingleNodeTestCase {

    @Override
    protected boolean resetNodeAfterTest() {
        return true;
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(FailOnRewriteQueryPlugin.class, CustomScriptPlugin.class, InternalOrPrivateSettingsPlugin.class,
            MockSearchService.TestPlugin.class);
    }

    public static class CustomScriptPlugin extends MockScriptPlugin {

        static final String DUMMY_SCRIPT = "dummyScript";

        @Override
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            return Collections.singletonMap(DUMMY_SCRIPT, vars -> "dummy");
        }

        @Override
        public void onIndexModule(IndexModule indexModule) {
            indexModule.addSearchOperationListener(new SearchOperationListener() {
                @Override
                public void onNewContext(SearchContext context) {
                    if ("throttled_threadpool_index".equals(context.indexShard().shardId().getIndex().getName())) {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search_throttled]"));
                    } else {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search]"));
                    }
                }

                @Override
                public void onFetchPhase(SearchContext context, long tookInNanos) {
                    if ("throttled_threadpool_index".equals(context.indexShard().shardId().getIndex().getName())) {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search_throttled]"));
                    } else {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search]"));
                    }
                }

                @Override
                public void onQueryPhase(SearchContext context, long tookInNanos) {
                    if ("throttled_threadpool_index".equals(context.indexShard().shardId().getIndex().getName())) {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search_throttled]"));
                    } else {
                        assertThat(Thread.currentThread().getName(), startsWith("elasticsearch[node_s_0][search]"));
                    }
                }
            });
        }
    }

    @Override
    protected Settings nodeSettings() {
        return Settings.builder().put("search.default_search_timeout", "5s")
            .put(MultiBucketConsumerService.MAX_BUCKET_SETTING.getKey(), MultiBucketConsumerService.SOFT_LIMIT_MAX_BUCKETS).build();
    }

    public void testClearOnClose() {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        SearchResponse searchResponse = client().prepareSearch("index").setSize(1).setScroll("1m").get();
        assertThat(searchResponse.getScrollId(), is(notNullValue()));
        SearchService service = getInstanceFromNode(SearchService.class);

        assertEquals(1, service.getActiveContexts());
        service.doClose(); // this kills the keep-alive reaper we have to reset the node after this test
        assertEquals(0, service.getActiveContexts());
    }

    public void testClearOnStop() {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        SearchResponse searchResponse = client().prepareSearch("index").setSize(1).setScroll("1m").get();
        assertThat(searchResponse.getScrollId(), is(notNullValue()));
        SearchService service = getInstanceFromNode(SearchService.class);

        assertEquals(1, service.getActiveContexts());
        service.doStop();
        assertEquals(0, service.getActiveContexts());
    }

    public void testClearIndexDelete() {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        SearchResponse searchResponse = client().prepareSearch("index").setSize(1).setScroll("1m").get();
        assertThat(searchResponse.getScrollId(), is(notNullValue()));
        SearchService service = getInstanceFromNode(SearchService.class);

        assertEquals(1, service.getActiveContexts());
        assertAcked(client().admin().indices().prepareDelete("index"));
        assertEquals(0, service.getActiveContexts());
    }

    public void testCloseSearchContextOnRewriteException() {
        // if refresh happens while checking the exception, the subsequent reference count might not match, so we switch it off
        createIndex("index", Settings.builder().put("index.refresh_interval", -1).build());
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();

        SearchService service = getInstanceFromNode(SearchService.class);
        IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        IndexShard indexShard = indexService.getShard(0);

        final int activeContexts = service.getActiveContexts();
        final int activeRefs = indexShard.store().refCount();
        expectThrows(SearchPhaseExecutionException.class, () ->
                client().prepareSearch("index").setQuery(new FailOnRewriteQueryBuilder()).get());
        assertEquals(activeContexts, service.getActiveContexts());
        assertEquals(activeRefs, indexShard.store().refCount());
    }

    public void testSearchWhileIndexDeleted() throws InterruptedException {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();

        SearchService service = getInstanceFromNode(SearchService.class);
        IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        IndexShard indexShard = indexService.getShard(0);
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch startGun = new CountDownLatch(1);
        Semaphore semaphore = new Semaphore(Integer.MAX_VALUE);

        final Thread thread = new Thread() {
            @Override
            public void run() {
                startGun.countDown();
                while(running.get()) {
                    service.afterIndexRemoved(indexService.index(), indexService.getIndexSettings(), DELETED);
                    if (randomBoolean()) {
                        // here we trigger some refreshes to ensure the IR go out of scope such that we hit ACE if we access a search
                        // context in a non-sane way.
                        try {
                            semaphore.acquire();
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                        client().prepareIndex("index", "type").setSource("field", "value")
                            .setRefreshPolicy(randomFrom(WriteRequest.RefreshPolicy.values())).execute(new ActionListener<IndexResponse>() {
                            @Override
                            public void onResponse(IndexResponse indexResponse) {
                                semaphore.release();
                            }

                            @Override
                            public void onFailure(Exception e) {
                                semaphore.release();
                            }
                        });
                    }
                }
            }
        };
        thread.start();
        startGun.await();
        try {
            final int rounds = scaledRandomIntBetween(100, 10000);
            for (int i = 0; i < rounds; i++) {
                try {
                    try {
                        PlainActionFuture<SearchPhaseResult> result = new PlainActionFuture<>();
                        final boolean useScroll = randomBoolean();
                        ShardSearchLocalRequest shardRequest;
                        if (useScroll) {
                            shardRequest = new ShardScrollRequestTest(indexShard.shardId());
                        } else {
                            shardRequest = new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                                new SearchSourceBuilder(), new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f,
                                true, null, null);
                        }
                        service.executeQueryPhase(
                            shardRequest,
                            new SearchTask(123L, "", "", "", null, Collections.emptyMap()), result);
                        SearchPhaseResult searchPhaseResult = result.get();
                        IntArrayList intCursors = new IntArrayList(1);
                        intCursors.add(0);
                        ShardFetchRequest req = new ShardFetchRequest(searchPhaseResult.getRequestId(), intCursors, null/* not a scroll */);
                        PlainActionFuture<FetchSearchResult> listener = new PlainActionFuture<>();
                        service.executeFetchPhase(req, new SearchTask(123L, "", "", "", null, Collections.emptyMap()), listener);
                        listener.get();
                        if (useScroll) {
                            // have to free context since this test does not remove the index from IndicesService.
                            service.freeContext(searchPhaseResult.getRequestId());
                        }
                    } catch (ExecutionException ex) {
                        assertThat(ex.getCause(), instanceOf(RuntimeException.class));
                        throw ((RuntimeException)ex.getCause());
                    }
                } catch (AlreadyClosedException ex) {
                    throw ex;
                } catch (IllegalStateException ex) {
                    assertEquals("search context is already closed can't increment refCount current count [0]", ex.getMessage());
                } catch (SearchContextMissingException ex) {
                    // that's fine
                }
            }
        } finally {
            running.set(false);
            thread.join();
            semaphore.acquire(Integer.MAX_VALUE);
        }

        assertEquals(0, service.getActiveContexts());

        SearchStats.Stats totalStats = indexShard.searchStats().getTotal();
        assertEquals(0, totalStats.getQueryCurrent());
        assertEquals(0, totalStats.getScrollCurrent());
        assertEquals(0, totalStats.getFetchCurrent());
    }

    public void testSearchWhileIndexDeletedDoesNotLeakSearchContext() throws ExecutionException, InterruptedException {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();

        IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        IndexShard indexShard = indexService.getShard(0);

        MockSearchService service = (MockSearchService) getInstanceFromNode(SearchService.class);
        service.setOnPutContext(
            context -> {
                if (context.indexShard() == indexShard) {
                    assertAcked(client().admin().indices().prepareDelete("index"));
                }
            }
        );

        SearchRequest searchRequest = new SearchRequest().allowPartialSearchResults(true);
        SearchRequest scrollSearchRequest = new SearchRequest().allowPartialSearchResults(true)
            .scroll(new Scroll(TimeValue.timeValueMinutes(1)));

        // the scrolls are not explicitly freed, but should all be gone when the test finished.
        // for completeness, we also randomly test the regular search path.
        final boolean useScroll = randomBoolean();
        PlainActionFuture<SearchPhaseResult> result = new PlainActionFuture<>();
        ShardSearchLocalRequest shardRequest;
        if (useScroll) {
            shardRequest = new ShardScrollRequestTest(indexShard.shardId());
        } else {
            shardRequest = new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                new SearchSourceBuilder(), new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f,
                true, null, null);
        }
        service.executeQueryPhase(
            shardRequest,
            new SearchTask(123L, "", "", "", null, Collections.emptyMap()), result);

        try {
            result.get();
        } catch (Exception e) {
            // ok
        }

        expectThrows(IndexNotFoundException.class, () -> client().admin().indices().prepareGetIndex().setIndices("index").get());

        assertEquals(0, service.getActiveContexts());

        SearchStats.Stats totalStats = indexShard.searchStats().getTotal();
        assertEquals(0, totalStats.getQueryCurrent());
        assertEquals(0, totalStats.getScrollCurrent());
        assertEquals(0, totalStats.getFetchCurrent());
    }

    public void testTimeout() throws IOException {
        createIndex("index");
        final SearchService service = getInstanceFromNode(SearchService.class);
        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        final IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        final IndexShard indexShard = indexService.getShard(0);
        final SearchContext contextWithDefaultTimeout = service.createContext(
                new ShardSearchLocalRequest(
                        indexShard.shardId(),
                        1,
                        SearchType.DEFAULT,
                        new SearchSourceBuilder(),
                        new String[0],
                        false,
                        new AliasFilter(null, Strings.EMPTY_ARRAY),
                        1.0f, true, null, null)
        );
        try {
            // the search context should inherit the default timeout
            assertThat(contextWithDefaultTimeout.timeout(), equalTo(TimeValue.timeValueSeconds(5)));
        } finally {
            contextWithDefaultTimeout.decRef();
            service.freeContext(contextWithDefaultTimeout.id());
        }

        final long seconds = randomIntBetween(6, 10);
        final SearchContext context = service.createContext(
                new ShardSearchLocalRequest(
                        indexShard.shardId(),
                        1,
                        SearchType.DEFAULT,
                        new SearchSourceBuilder().timeout(TimeValue.timeValueSeconds(seconds)),
                        new String[0],
                        false,
                        new AliasFilter(null, Strings.EMPTY_ARRAY),
                        1.0f, true, null, null)
        );
        try {
            // the search context should inherit the query timeout
            assertThat(context.timeout(), equalTo(TimeValue.timeValueSeconds(seconds)));
        } finally {
            context.decRef();
            service.freeContext(context.id());
        }

    }

    /**
     * test that getting more than the allowed number of docvalue_fields throws an exception
     */
    public void testMaxDocvalueFieldsSearch() throws IOException {
        createIndex("index");
        final SearchService service = getInstanceFromNode(SearchService.class);
        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        final IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        final IndexShard indexShard = indexService.getShard(0);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        // adding the maximum allowed number of docvalue_fields to retrieve
        for (int i = 0; i < indexService.getIndexSettings().getMaxDocvalueFields(); i++) {
            searchSourceBuilder.docValueField("field" + i);
        }
        try (SearchContext context = service.createContext(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                searchSourceBuilder, new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f, true, null, null))) {
            assertNotNull(context);
            searchSourceBuilder.docValueField("one_field_too_much");
            IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
                    () -> service.createContext(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                            searchSourceBuilder, new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f,
                        true, null, null)));
            assertEquals(
                    "Trying to retrieve too many docvalue_fields. Must be less than or equal to: [100] but was [101]. "
                            + "This limit can be set by changing the [index.max_docvalue_fields_search] index level setting.",
                    ex.getMessage());
        }
    }

    /**
     * test that getting more than the allowed number of script_fields throws an exception
     */
    public void testMaxScriptFieldsSearch() throws IOException {
        createIndex("index");
        final SearchService service = getInstanceFromNode(SearchService.class);
        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        final IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        final IndexShard indexShard = indexService.getShard(0);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        // adding the maximum allowed number of script_fields to retrieve
        int maxScriptFields = indexService.getIndexSettings().getMaxScriptFields();
        for (int i = 0; i < maxScriptFields; i++) {
            searchSourceBuilder.scriptField("field" + i,
                    new Script(ScriptType.INLINE, MockScriptEngine.NAME, CustomScriptPlugin.DUMMY_SCRIPT, Collections.emptyMap()));
        }
        try (SearchContext context = service.createContext(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                searchSourceBuilder, new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f, true, null, null))) {
            assertNotNull(context);
            searchSourceBuilder.scriptField("anotherScriptField",
                    new Script(ScriptType.INLINE, MockScriptEngine.NAME, CustomScriptPlugin.DUMMY_SCRIPT, Collections.emptyMap()));
            IllegalArgumentException ex = expectThrows(IllegalArgumentException.class,
                    () -> service.createContext(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                            searchSourceBuilder, new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY),
                        1.0f, true, null, null)));
            assertEquals(
                    "Trying to retrieve too many script_fields. Must be less than or equal to: [" + maxScriptFields + "] but was ["
                            + (maxScriptFields + 1)
                            + "]. This limit can be set by changing the [index.max_script_fields] index level setting.",
                    ex.getMessage());
        }
    }

    public void testIgnoreScriptfieldIfSizeZero() throws IOException {
        createIndex("index");
        final SearchService service = getInstanceFromNode(SearchService.class);
        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        final IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        final IndexShard indexShard = indexService.getShard(0);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.scriptField("field" + 0,
                new Script(ScriptType.INLINE, MockScriptEngine.NAME, CustomScriptPlugin.DUMMY_SCRIPT, Collections.emptyMap()));
        searchSourceBuilder.size(0);
        try (SearchContext context = service.createContext(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.DEFAULT,
                searchSourceBuilder, new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1.0f, true, null, null))) {
                assertEquals(0, context.scriptFields().fields().size());
        }
    }

    /**
     * test that creating more than the allowed number of scroll contexts throws an exception
     */
    public void testMaxOpenScrollContexts() throws RuntimeException, IOException {
        createIndex("index");
        client().prepareIndex("index", "type", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();

        client().admin().cluster().prepareUpdateSettings()
            .setPersistentSettings(
                Settings.builder()
                    .put(SearchService.MAX_OPEN_SCROLL_CONTEXT.getKey(), 500))
            .get();
        try {

            LinkedList<String> clearScrollIds = new LinkedList<>();

            for (int i = 0; i < 500; i++) {
                SearchResponse searchResponse = client().prepareSearch("index").setSize(1).setScroll("1m").get();

                if (randomInt(4) == 0) clearScrollIds.addLast(searchResponse.getScrollId());
            }

            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.setScrollIds(clearScrollIds);
            client().clearScroll(clearScrollRequest);

            for (int i = 0; i < clearScrollIds.size(); i++) {
                client().prepareSearch("index").setSize(1).setScroll("1m").get();
            }
            ElasticsearchException ex = expectThrows(ElasticsearchException.class,
                () -> client().prepareSearch("index").setSize(1).setScroll("1m").get());
            assertThat(ex.getDetailedMessage(), containsString(
                "Trying to create too many scroll contexts. Must be less than or equal to: [500]. " +
                    "This limit can be set by changing the [search.max_open_scroll_context] setting."
                )
            );
            clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId("_all");
            client().clearScroll(clearScrollRequest);
        } finally {
            client().admin().cluster().prepareUpdateSettings()
                .setPersistentSettings(
                    Settings.builder()
                        .putNull(SearchService.MAX_OPEN_SCROLL_CONTEXT.getKey()))
                .get();
        }
    }

    public void testOpenScrollContextsConcurrently() throws Exception {
        final int maxScrollContexts = randomIntBetween(50, 200);
        assertAcked(client().admin().cluster().prepareUpdateSettings()
            .setTransientSettings(Settings.builder().put(SearchService.MAX_OPEN_SCROLL_CONTEXT.getKey(), maxScrollContexts)));
        try {
            createIndex("index");
            final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
            final IndexShard indexShard = indicesService.indexServiceSafe(resolveIndex("index")).getShard(0);

            final SearchService searchService = getInstanceFromNode(SearchService.class);
            Thread[] threads = new Thread[randomIntBetween(2, 8)];
            CountDownLatch latch = new CountDownLatch(threads.length);
            for (int i = 0; i < threads.length; i++) {
                threads[i] = new Thread(() -> {
                    latch.countDown();
                    try {
                        latch.await();
                        for (; ; ) {
                            try {
                                searchService.createAndPutContext(new ShardScrollRequestTest(indexShard.shardId()));
                            } catch (ElasticsearchException e) {
                                assertThat(e.getMessage(), equalTo(
                                    "Trying to create too many scroll contexts. Must be less than or equal to: " +
                                        "[" + maxScrollContexts + "]. " +
                                        "This limit can be set by changing the [search.max_open_scroll_context] setting."));
                                return;
                            }
                        }
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
                threads[i].setName("elasticsearch[node_s_0][search]");
                threads[i].start();
            }
            for (Thread thread : threads) {
                thread.join();
            }
            assertThat(searchService.getActiveContexts(), equalTo(maxScrollContexts));
            searchService.freeAllScrollContexts();
        } finally {
            assertAcked(client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(Settings.builder().putNull(SearchService.MAX_OPEN_SCROLL_CONTEXT.getKey())));
        }
    }

    public static class FailOnRewriteQueryPlugin extends Plugin implements SearchPlugin {
        @Override
        public List<QuerySpec<?>> getQueries() {
            return singletonList(new QuerySpec<>("fail_on_rewrite_query", FailOnRewriteQueryBuilder::new, parseContext -> {
                throw new UnsupportedOperationException("No query parser for this plugin");
            }));
        }
    }

    public static class FailOnRewriteQueryBuilder extends AbstractQueryBuilder<FailOnRewriteQueryBuilder> {

        public FailOnRewriteQueryBuilder(StreamInput in) throws IOException {
            super(in);
        }

        public FailOnRewriteQueryBuilder() {
        }

        @Override
        protected QueryBuilder doRewrite(QueryRewriteContext queryRewriteContext) {
            if (queryRewriteContext.convertToShardContext() != null) {
                throw new IllegalStateException("Fail on rewrite phase");
            }
            return this;
        }

        @Override
        protected void doWriteTo(StreamOutput out) {
        }

        @Override
        protected void doXContent(XContentBuilder builder, Params params) {
        }

        @Override
        protected Query doToQuery(QueryShardContext context) {
            return null;
        }

        @Override
        protected boolean doEquals(FailOnRewriteQueryBuilder other) {
            return false;
        }

        @Override
        protected int doHashCode() {
            return 0;
        }

        @Override
        public String getWriteableName() {
            return null;
        }
    }

    public static class ShardScrollRequestTest extends ShardSearchLocalRequest {
        private Scroll scroll;

        ShardScrollRequestTest(ShardId shardId) {
            super(shardId, 1, SearchType.DEFAULT, new SearchSourceBuilder(),
                new String[0], false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, true, null, null);

            this.scroll = new Scroll(TimeValue.timeValueMinutes(1));
        }

        @Override
        public Scroll scroll() {
            return this.scroll;
        }
    }

    public void testCanMatch() throws IOException {
        createIndex("index");
        final SearchService service = getInstanceFromNode(SearchService.class);
        final IndicesService indicesService = getInstanceFromNode(IndicesService.class);
        final IndexService indexService = indicesService.indexServiceSafe(resolveIndex("index"));
        final IndexShard indexShard = indexService.getShard(0);
        final boolean allowPartialSearchResults = true;
        assertTrue(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH, null,
            Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, allowPartialSearchResults, null, null)));

        assertTrue(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH,
            new SearchSourceBuilder(), Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f,
            allowPartialSearchResults, null, null)));

        assertTrue(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH,
            new SearchSourceBuilder().query(new MatchAllQueryBuilder()), Strings.EMPTY_ARRAY, false,
            new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, allowPartialSearchResults, null, null)));

        assertTrue(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH,
            new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
            .aggregation(new TermsAggregationBuilder("test", ValueType.STRING).minDocCount(0)), Strings.EMPTY_ARRAY, false,
            new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, allowPartialSearchResults,  null, null)));
        assertTrue(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH,
            new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
                .aggregation(new GlobalAggregationBuilder("test")), Strings.EMPTY_ARRAY, false,
            new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, allowPartialSearchResults, null, null)));

        assertFalse(service.canMatch(new ShardSearchLocalRequest(indexShard.shardId(), 1, SearchType.QUERY_THEN_FETCH,
            new SearchSourceBuilder().query(new MatchNoneQueryBuilder()), Strings.EMPTY_ARRAY, false,
            new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, allowPartialSearchResults, null, null)));
    }

    public void testCanRewriteToMatchNone() {
        assertFalse(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
            .aggregation(new GlobalAggregationBuilder("test"))));
        assertFalse(SearchService.canRewriteToMatchNone(new SearchSourceBuilder()));
        assertFalse(SearchService.canRewriteToMatchNone(null));
        assertFalse(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
            .aggregation(new TermsAggregationBuilder("test", ValueType.STRING).minDocCount(0))));
        assertTrue(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new TermQueryBuilder("foo", "bar"))));
        assertTrue(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
            .aggregation(new TermsAggregationBuilder("test", ValueType.STRING).minDocCount(1))));
        assertFalse(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new MatchNoneQueryBuilder())
            .aggregation(new TermsAggregationBuilder("test", ValueType.STRING).minDocCount(1))
            .suggest(new SuggestBuilder())));
        assertFalse(SearchService.canRewriteToMatchNone(new SearchSourceBuilder().query(new TermQueryBuilder("foo", "bar"))
            .suggest(new SuggestBuilder())));
    }

    public void testSetSearchThrottled() {
        createIndex("throttled_threadpool_index");
        client().execute(
            InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.INSTANCE,
            new InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.Request("throttled_threadpool_index",
                IndexSettings.INDEX_SEARCH_THROTTLED.getKey(), "true"))
            .actionGet();
        final SearchService service = getInstanceFromNode(SearchService.class);
        Index index = resolveIndex("throttled_threadpool_index");
        assertTrue(service.getIndicesService().indexServiceSafe(index).getIndexSettings().isSearchThrottled());
        client().prepareIndex("throttled_threadpool_index", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        SearchResponse searchResponse = client().prepareSearch("throttled_threadpool_index")
            .setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED).setSize(1).get();
        assertSearchHits(searchResponse, "1");
        // we add a search action listener in a plugin above to assert that this is actually used
        client().execute(
            InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.INSTANCE,
            new InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.Request("throttled_threadpool_index",
                IndexSettings.INDEX_SEARCH_THROTTLED.getKey(), "false"))
            .actionGet();

        IllegalArgumentException iae = expectThrows(IllegalArgumentException.class, () ->
            client().admin().indices().prepareUpdateSettings("throttled_threadpool_index").setSettings(Settings.builder().put(IndexSettings
                .INDEX_SEARCH_THROTTLED.getKey(), false)).get());
        assertEquals("can not update private setting [index.search.throttled]; this setting is managed by Elasticsearch",
            iae.getMessage());
        assertFalse(service.getIndicesService().indexServiceSafe(index).getIndexSettings().isSearchThrottled());
        ShardSearchLocalRequest req = new ShardSearchLocalRequest(new ShardId(index, 0), 1, SearchType.QUERY_THEN_FETCH, null,
            Strings.EMPTY_ARRAY, false, new AliasFilter(null, Strings.EMPTY_ARRAY), 1f, false, null, null);
        Thread currentThread = Thread.currentThread();
        // we still make sure can match is executed on the network thread
        service.canMatch(req, ActionListener.wrap(r -> assertSame(Thread.currentThread(), currentThread), e -> fail("unexpected")));
    }

    public void testExpandSearchThrottled() {
        createIndex("throttled_threadpool_index");
        client().execute(
            InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.INSTANCE,
            new InternalOrPrivateSettingsPlugin.UpdateInternalOrPrivateAction.Request("throttled_threadpool_index",
                IndexSettings.INDEX_SEARCH_THROTTLED.getKey(), "true"))
            .actionGet();

        client().prepareIndex("throttled_threadpool_index", "_doc", "1").setSource("field", "value").setRefreshPolicy(IMMEDIATE).get();
        assertHitCount(client().prepareSearch().get(), 0L);
        assertHitCount(client().prepareSearch().setIndicesOptions(IndicesOptions.STRICT_EXPAND_OPEN_FORBID_CLOSED).get(), 1L);
    }

    public void testCreateReduceContext() {
        final SearchService service = getInstanceFromNode(SearchService.class);
        {
            InternalAggregation.ReduceContext reduceContext = service.createReduceContext(true);
            expectThrows(MultiBucketConsumerService.TooManyBucketsException.class,
                () -> reduceContext.consumeBucketsAndMaybeBreak(MultiBucketConsumerService.SOFT_LIMIT_MAX_BUCKETS + 1));
        }
        {
            InternalAggregation.ReduceContext reduceContext = service.createReduceContext(false);
            reduceContext.consumeBucketsAndMaybeBreak(MultiBucketConsumerService.SOFT_LIMIT_MAX_BUCKETS + 1);
        }
    }

    public void testCreateSearchContext() throws IOException {
        String index = randomAlphaOfLengthBetween(5, 10).toLowerCase(Locale.ROOT);
        IndexService indexService = createIndex(index);
        final SearchService service = getInstanceFromNode(SearchService.class);
        ShardId shardId = new ShardId(indexService.index(), 0);
        long nowInMillis = System.currentTimeMillis();
        String clusterAlias = randomBoolean() ? null : randomAlphaOfLengthBetween(3, 10);
        SearchRequest searchRequest = new SearchRequest();
        searchRequest.allowPartialSearchResults(randomBoolean());
        ShardSearchTransportRequest request = new ShardSearchTransportRequest(OriginalIndices.NONE, searchRequest, shardId,
            indexService.numberOfShards(), AliasFilter.EMPTY, 1f, nowInMillis, clusterAlias, Strings.EMPTY_ARRAY);
        DefaultSearchContext searchContext = service.createSearchContext(request, new TimeValue(System.currentTimeMillis()));
        SearchShardTarget searchShardTarget = searchContext.shardTarget();
        QueryShardContext queryShardContext = searchContext.getQueryShardContext();
        String expectedIndexName = clusterAlias == null ? index : clusterAlias + ":" + index;
        assertEquals(expectedIndexName, queryShardContext.getFullyQualifiedIndex().getName());
        assertEquals(expectedIndexName, searchShardTarget.getFullyQualifiedIndexName());
        assertEquals(clusterAlias, searchShardTarget.getClusterAlias());
        assertEquals(shardId, searchShardTarget.getShardId());
        assertSame(searchShardTarget, searchContext.dfsResult().getSearchShardTarget());
        assertSame(searchShardTarget, searchContext.queryResult().getSearchShardTarget());
        assertSame(searchShardTarget, searchContext.fetchResult().getSearchShardTarget());
        assertThat(service.getOpenScrollContexts(), equalTo(0));
    }
}
