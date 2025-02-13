/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.ccr;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.admin.cluster.remote.RemoteInfoAction;
import org.elasticsearch.action.admin.cluster.remote.RemoteInfoRequest;
import org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.transport.RemoteClusterConnection;
import org.elasticsearch.transport.RemoteConnectionInfo;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.CcrIntegTestCase;
import org.elasticsearch.xpack.core.ccr.action.PauseFollowAction;
import org.elasticsearch.xpack.core.ccr.action.PutFollowAction;
import org.elasticsearch.xpack.core.ccr.action.UnfollowAction;

import java.util.List;
import java.util.Locale;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

public class RestartIndexFollowingIT extends CcrIntegTestCase {

    @Override
    protected int numberOfNodesPerCluster() {
        return 1;
    }

    @Override
    protected boolean configureRemoteClusterViaNodeSettings() {
        return false;
    }

    @Override
    protected Settings followerClusterSettings() {
        final Settings.Builder settings = Settings.builder().put(super.followerClusterSettings());
        if (randomBoolean()) {
            settings.put(RemoteClusterConnection.REMOTE_MAX_PENDING_CONNECTION_LISTENERS.getKey(), 1);
        }
        return settings.build();
    }

    public void testFollowIndex() throws Exception {
        final String leaderIndexSettings = getIndexSettings(1, 0,
            singletonMap(IndexSettings.INDEX_SOFT_DELETES_SETTING.getKey(), "true"));
        assertAcked(leaderClient().admin().indices().prepareCreate("index1").setSource(leaderIndexSettings, XContentType.JSON));
        ensureLeaderGreen("index1");
        setupRemoteCluster();

        final PutFollowAction.Request followRequest = putFollow("index1", "index2");
        followerClient().execute(PutFollowAction.INSTANCE, followRequest).get();

        final long firstBatchNumDocs = randomIntBetween(2, 64);
        logger.info("Indexing [{}] docs as first batch", firstBatchNumDocs);
        for (int i = 0; i < firstBatchNumDocs; i++) {
            final String source = String.format(Locale.ROOT, "{\"f\":%d}", i);
            leaderClient().prepareIndex("index1", "doc", Integer.toString(i)).setSource(source, XContentType.JSON).get();
        }

        assertBusy(() -> {
            assertThat(followerClient().prepareSearch("index2").get().getHits().totalHits, equalTo(firstBatchNumDocs));
        });

        getFollowerCluster().fullRestart();
        ensureFollowerGreen("index2");

        final long secondBatchNumDocs = randomIntBetween(2, 64);
        for (int i = 0; i < secondBatchNumDocs; i++) {
            leaderClient().prepareIndex("index1", "doc").setSource("{}", XContentType.JSON).get();
        }

        assertBusy(() -> {
            assertThat(followerClient().prepareSearch("index2").get().getHits().totalHits,
                equalTo(firstBatchNumDocs + secondBatchNumDocs));
        });

        cleanRemoteCluster();
        getLeaderCluster().fullRestart();
        ensureLeaderGreen("index1");
        // Remote connection needs to be re-configured, because all the nodes in leader cluster have been restarted:
        setupRemoteCluster();

        final long thirdBatchNumDocs = randomIntBetween(2, 64);
        for (int i = 0; i < thirdBatchNumDocs; i++) {
            leaderClient().prepareIndex("index1", "doc").setSource("{}", XContentType.JSON).get();
        }

        assertBusy(() -> assertThat(
                followerClient().prepareSearch("index2").get().getHits().getTotalHits(),
                equalTo(firstBatchNumDocs + secondBatchNumDocs + thirdBatchNumDocs)));

        cleanRemoteCluster();
        assertAcked(followerClient().execute(PauseFollowAction.INSTANCE, new PauseFollowAction.Request("index2")).actionGet());
        assertAcked(followerClient().admin().indices().prepareClose("index2"));

        final ActionFuture<AcknowledgedResponse> unfollowFuture
                = followerClient().execute(UnfollowAction.INSTANCE, new UnfollowAction.Request("index2"));
        final ElasticsearchException elasticsearchException = expectThrows(ElasticsearchException.class, unfollowFuture::actionGet);
        assertThat(elasticsearchException.getMessage(), containsString("no such remote cluster"));
        assertThat(elasticsearchException.getMetadataKeys(), hasItem("es.failed_to_remove_retention_leases"));
    }

    private void setupRemoteCluster() throws Exception {
        ClusterUpdateSettingsRequest updateSettingsRequest = new ClusterUpdateSettingsRequest();
        String masterNode = getLeaderCluster().getMasterName();
        String address = getLeaderCluster().getInstance(TransportService.class, masterNode).boundAddress().publishAddress().toString();
        updateSettingsRequest.persistentSettings(Settings.builder().put("cluster.remote.leader_cluster.seeds", address));
        assertAcked(followerClient().admin().cluster().updateSettings(updateSettingsRequest).actionGet());

        assertBusy(() -> {
            List<RemoteConnectionInfo> infos =
                followerClient().execute(RemoteInfoAction.INSTANCE, new RemoteInfoRequest()).get().getInfos();
            assertThat(infos.size(), equalTo(1));
            assertThat(infos.get(0).getNumNodesConnected(), greaterThanOrEqualTo(1));
        });
    }

    private void cleanRemoteCluster() throws Exception {
        ClusterUpdateSettingsRequest updateSettingsRequest = new ClusterUpdateSettingsRequest();
        updateSettingsRequest.persistentSettings(Settings.builder().put("cluster.remote.leader_cluster.seeds", (String) null));
        assertAcked(followerClient().admin().cluster().updateSettings(updateSettingsRequest).actionGet());

        assertBusy(() -> {
            List<RemoteConnectionInfo> infos =
                followerClient().execute(RemoteInfoAction.INSTANCE, new RemoteInfoRequest()).get().getInfos();
            assertThat(infos.size(), equalTo(0));
        });
    }

}
