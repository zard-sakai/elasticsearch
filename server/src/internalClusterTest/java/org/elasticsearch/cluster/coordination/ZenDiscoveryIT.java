/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.cluster.coordination;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryStats;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.TestCustomMetadata;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.NodeRoles.dataNode;
import static org.elasticsearch.test.NodeRoles.masterOnlyNode;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0)
public class ZenDiscoveryIT extends ESIntegTestCase {

    public void testNoShardRelocationsOccurWhenElectedMasterNodeFails() throws Exception {
        Settings masterNodeSettings = masterOnlyNode();
        internalCluster().startNodes(2, masterNodeSettings);
        Settings dateNodeSettings = dataNode();
        internalCluster().startNodes(2, dateNodeSettings);
        ClusterHealthResponse clusterHealthResponse = client().admin()
            .cluster()
            .prepareHealth()
            .setWaitForEvents(Priority.LANGUID)
            .setWaitForNodes("4")
            .setWaitForNoRelocatingShards(true)
            .get();
        assertThat(clusterHealthResponse.isTimedOut(), is(false));

        createIndex("test");
        ensureSearchable("test");
        RecoveryResponse r = client().admin().indices().prepareRecoveries("test").get();
        int numRecoveriesBeforeNewMaster = r.shardRecoveryStates().get("test").size();

        final String oldMaster = internalCluster().getMasterName();
        internalCluster().stopCurrentMasterNode();
        assertBusy(() -> {
            String current = internalCluster().getMasterName();
            assertThat(current, notNullValue());
            assertThat(current, not(equalTo(oldMaster)));
        });
        ensureSearchable("test");

        r = client().admin().indices().prepareRecoveries("test").get();
        int numRecoveriesAfterNewMaster = r.shardRecoveryStates().get("test").size();
        assertThat(numRecoveriesAfterNewMaster, equalTo(numRecoveriesBeforeNewMaster));
    }

    public void testHandleNodeJoin_incompatibleClusterState() {
        String masterNode = internalCluster().startMasterOnlyNode();
        String node1 = internalCluster().startNode();
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, node1);
        final ClusterState state = clusterService.state();
        Metadata.Builder mdBuilder = Metadata.builder(state.metadata());
        mdBuilder.putCustom(CustomMetadata.TYPE, new CustomMetadata("data"));
        ClusterState stateWithCustomMetadata = ClusterState.builder(state).metadata(mdBuilder).build();

        final PlainActionFuture<Void> future = new PlainActionFuture<>();

        internalCluster().getInstance(TransportService.class, masterNode)
            .sendRequest(
                state.nodes().getLocalNode(),
                JoinHelper.JOIN_VALIDATE_ACTION_NAME,
                new ValidateJoinRequest(stateWithCustomMetadata),
                new ActionListenerResponseHandler<>(new ActionListener<>() {
                    @Override
                    public void onResponse(TransportResponse.Empty unused) {
                        fail("onResponse should not be called");
                    }

                    @Override
                    public void onFailure(Exception t) {
                        assertThat(t, instanceOf(RemoteTransportException.class));
                        assertThat(t.getCause(), instanceOf(IllegalArgumentException.class));
                        assertThat(t.getCause().getMessage(), containsString("Unknown NamedWriteable"));
                        future.onResponse(null);
                    }
                }, i -> TransportResponse.Empty.INSTANCE, ThreadPool.Names.GENERIC)
            );

        future.actionGet(10, TimeUnit.SECONDS);
    }

    public static class CustomMetadata extends TestCustomMetadata {
        public static final String TYPE = "custom_md";

        CustomMetadata(String data) {
            super(data);
        }

        @Override
        public String getWriteableName() {
            return TYPE;
        }

        @Override
        public Version getMinimalSupportedVersion() {
            return Version.CURRENT;
        }

        @Override
        public EnumSet<Metadata.XContentContext> context() {
            return EnumSet.of(Metadata.XContentContext.GATEWAY, Metadata.XContentContext.SNAPSHOT);
        }
    }

    public void testDiscoveryStats() throws Exception {
        internalCluster().startNode();
        ensureGreen(); // ensures that all events are processed (in particular state recovery fully completed)
        assertBusy(
            () -> assertThat(
                internalCluster().clusterService(internalCluster().getMasterName()).getMasterService().numberOfPendingTasks(),
                equalTo(0)
            )
        ); // see https://github.com/elastic/elasticsearch/issues/24388

        logger.info("--> request node discovery stats");
        NodesStatsResponse statsResponse = client().admin().cluster().prepareNodesStats().clear().setDiscovery(true).get();
        assertThat(statsResponse.getNodes().size(), equalTo(1));

        DiscoveryStats stats = statsResponse.getNodes().get(0).getDiscoveryStats();
        assertThat(stats.getQueueStats(), notNullValue());
        assertThat(stats.getQueueStats().getTotal(), equalTo(0));
        assertThat(stats.getQueueStats().getCommitted(), equalTo(0));
        assertThat(stats.getQueueStats().getPending(), equalTo(0));

        assertThat(stats.getPublishStats(), notNullValue());
        assertThat(stats.getPublishStats().getFullClusterStateReceivedCount(), greaterThanOrEqualTo(0L));
        assertThat(stats.getPublishStats().getIncompatibleClusterStateDiffReceivedCount(), equalTo(0L));
        assertThat(stats.getPublishStats().getCompatibleClusterStateDiffReceivedCount(), greaterThanOrEqualTo(0L));

        XContentBuilder builder = XContentFactory.jsonBuilder().prettyPrint();
        builder.startObject();
        stats.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.endObject();
    }
}
