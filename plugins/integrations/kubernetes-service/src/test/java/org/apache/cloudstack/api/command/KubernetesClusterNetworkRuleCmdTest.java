// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.apache.cloudstack.api.command;

import java.lang.reflect.Field;
import java.util.List;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterService;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ACL;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.command.admin.kubernetes.cluster.AdoptKubernetesClusterNetworkRulesCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.AddNodesToKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.CreateKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.DeleteKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.ReconcileKubernetesClusterNetworkRulesCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.RemoveNodesFromKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.ScaleKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.StartKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.StopKubernetesClusterCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.UpgradeKubernetesClusterCmd;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

public class KubernetesClusterNetworkRuleCmdTest {

    private static final long CLUSTER_ID = 11L;
    private static final long NETWORK_ID = 22L;

    @Test
    public void testReconcileCommandSynchronizesOnClusterNetwork() {
        ReconcileKubernetesClusterNetworkRulesCmd cmd = new ReconcileKubernetesClusterNetworkRulesCmd();
        cmd.kubernetesClusterService = mockClusterService();
        ReflectionTestUtils.setField(cmd, "id", CLUSTER_ID);

        Assert.assertEquals(BaseAsyncCmd.networkSyncObject, cmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(NETWORK_ID), cmd.getSyncObjId());
    }

    @Test
    public void testAdoptCommandSynchronizesOnClusterNetwork() {
        AdoptKubernetesClusterNetworkRulesCmd cmd = new AdoptKubernetesClusterNetworkRulesCmd();
        cmd.kubernetesClusterService = mockClusterService();
        ReflectionTestUtils.setField(cmd, "id", CLUSTER_ID);

        Assert.assertEquals(BaseAsyncCmd.networkSyncObject, cmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(NETWORK_ID), cmd.getSyncObjId());
    }

    @Test
    public void testMissingClusterDoesNotCreateInvalidSyncQueue() {
        ReconcileKubernetesClusterNetworkRulesCmd cmd = new ReconcileKubernetesClusterNetworkRulesCmd();
        cmd.kubernetesClusterService = Mockito.mock(KubernetesClusterService.class);
        ReflectionTestUtils.setField(cmd, "id", CLUSTER_ID);

        Assert.assertNull(cmd.getSyncObjId());
    }

    @Test
    public void testAllLifecycleCommandsSynchronizeWithNetworkRuleCommands() {
        CreateKubernetesClusterCmd createCmd = new CreateKubernetesClusterCmd();
        createCmd.kubernetesClusterService = mockClusterService();
        createCmd.setEntityId(CLUSTER_ID);
        Assert.assertEquals(BaseAsyncCmd.networkSyncObject, createCmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(NETWORK_ID), createCmd.getSyncObjId());

        assertCommandSynchronizesOnClusterNetwork(new AddNodesToKubernetesClusterCmd(), "clusterId");
        assertCommandSynchronizesOnClusterNetwork(new RemoveNodesFromKubernetesClusterCmd(), "clusterId");
        assertCommandSynchronizesOnClusterNetwork(new DeleteKubernetesClusterCmd(), "id");
        assertCommandSynchronizesOnClusterNetwork(new ScaleKubernetesClusterCmd(), "id");
        assertCommandSynchronizesOnClusterNetwork(new StartKubernetesClusterCmd(), "id");
        assertCommandSynchronizesOnClusterNetwork(new StopKubernetesClusterCmd(), "id");
        assertCommandSynchronizesOnClusterNetwork(new UpgradeKubernetesClusterCmd(), "id");
    }

    @Test
    public void testCreateCommandFallsBackToRequestedNetworkBeforeEntityLookup() {
        CreateKubernetesClusterCmd cmd = new CreateKubernetesClusterCmd();
        cmd.kubernetesClusterService = Mockito.mock(KubernetesClusterService.class);
        ReflectionTestUtils.setField(cmd, "networkId", NETWORK_ID);

        Assert.assertEquals(Long.valueOf(NETWORK_ID), cmd.getSyncObjId());
    }

    @Test
    public void testLifecycleClusterIdsRequireOperateAccessBeforeQueueing() throws Exception {
        List<Class<?>> commandClasses = List.of(DeleteKubernetesClusterCmd.class,
                ScaleKubernetesClusterCmd.class, StartKubernetesClusterCmd.class,
                StopKubernetesClusterCmd.class, UpgradeKubernetesClusterCmd.class,
                ReconcileKubernetesClusterNetworkRulesCmd.class);

        for (Class<?> commandClass : commandClasses) {
            Field idField = commandClass.getDeclaredField("id");
            ACL acl = idField.getAnnotation(ACL.class);
            Assert.assertNotNull(commandClass.getSimpleName() + " must authorize its cluster before queueing", acl);
            Assert.assertEquals(SecurityChecker.AccessType.OperateEntry, acl.accessType());
        }
    }

    @Test
    public void testMissingClusterDoesNotCreateLifecycleSyncQueue() {
        AddNodesToKubernetesClusterCmd cmd = new AddNodesToKubernetesClusterCmd();
        cmd.kubernetesClusterService = Mockito.mock(KubernetesClusterService.class);
        ReflectionTestUtils.setField(cmd, "clusterId", CLUSTER_ID);

        Assert.assertNull(cmd.getSyncObjId());
    }

    private void assertCommandSynchronizesOnClusterNetwork(BaseAsyncCmd cmd, String clusterIdField) {
        ReflectionTestUtils.setField(cmd, "kubernetesClusterService", mockClusterService());
        ReflectionTestUtils.setField(cmd, clusterIdField, CLUSTER_ID);

        Assert.assertEquals(BaseAsyncCmd.networkSyncObject, cmd.getSyncObjType());
        Assert.assertEquals(Long.valueOf(NETWORK_ID), cmd.getSyncObjId());
    }

    private KubernetesClusterService mockClusterService() {
        KubernetesClusterService service = Mockito.mock(KubernetesClusterService.class);
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getNetworkId()).thenReturn(NETWORK_ID);
        Mockito.when(service.findById(CLUSTER_ID)).thenReturn(cluster);
        return service;
    }
}
