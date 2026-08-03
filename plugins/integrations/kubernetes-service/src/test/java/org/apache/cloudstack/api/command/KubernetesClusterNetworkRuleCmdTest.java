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

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterService;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.command.admin.kubernetes.cluster.AdoptKubernetesClusterNetworkRulesCmd;
import org.apache.cloudstack.api.command.user.kubernetes.cluster.ReconcileKubernetesClusterNetworkRulesCmd;
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

    private KubernetesClusterService mockClusterService() {
        KubernetesClusterService service = Mockito.mock(KubernetesClusterService.class);
        KubernetesCluster cluster = Mockito.mock(KubernetesCluster.class);
        Mockito.when(cluster.getNetworkId()).thenReturn(NETWORK_ID);
        Mockito.when(service.findById(CLUSTER_ID)).thenReturn(cluster);
        return service;
    }
}
