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
package com.cloud.kubernetes.cluster.actionworkers;

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterAddWorkerTest {

    @Mock
    private KubernetesCluster cluster;
    @Mock
    private KubernetesClusterManagerImpl manager;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private Network network;
    @Mock
    private IpAddress publicIp;
    @Mock
    private Account account;

    private KubernetesClusterAddWorker worker;

    @Before
    public void setUp() {
        worker = Mockito.spy(new KubernetesClusterAddWorker(cluster, manager));
        worker.networkModel = networkModel;
        worker.owner = account;
    }

    @Test
    public void directNodeAccessUsesClusterNicWithoutCreatingPublicRules() throws Exception {
        Nic nic = Mockito.mock(Nic.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(networkModel.getNicInNetwork(3L, 2L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("192.0.2.10");

        Pair<String, Integer> endpoint = worker.prepareNodeAccess(network, null, account, 3L, 2222);

        Assert.assertEquals("192.0.2.10", endpoint.first());
        Assert.assertEquals(Integer.valueOf(KubernetesClusterActionWorker.DEFAULT_SSH_PORT), endpoint.second());
        Mockito.verify(worker, Mockito.never()).provisionFirewallRules(Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyString());
        Mockito.verify(worker, Mockito.never()).provisionPublicIpPortForwardingRule(Mockito.any(), Mockito.any(),
                Mockito.any(), Mockito.anyLong(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    public void isolatedNodeAccessPreservesFirewallAndPortForwardingPath() throws Exception {
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(null);
        Mockito.when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        Mockito.doNothing().when(worker).provisionFirewallRules(publicIp, account, 2222, 2222,
                KubernetesClusterActionWorker.EXTERNAL_SSH_FIREWALL_ROLE_PREFIX + 3L);
        Mockito.doNothing().when(worker).provisionPublicIpPortForwardingRule(publicIp, network, account, 3L,
                2222, KubernetesClusterActionWorker.DEFAULT_SSH_PORT);

        Pair<String, Integer> endpoint = worker.prepareNodeAccess(network, publicIp, account, 3L, 2222);

        Assert.assertEquals("203.0.113.10", endpoint.first());
        Assert.assertEquals(Integer.valueOf(2222), endpoint.second());
        Mockito.verify(worker).provisionFirewallRules(publicIp, account, 2222, 2222,
                KubernetesClusterActionWorker.EXTERNAL_SSH_FIREWALL_ROLE_PREFIX + 3L);
        Mockito.verify(worker).provisionPublicIpPortForwardingRule(publicIp, network, account, 3L, 2222,
                KubernetesClusterActionWorker.DEFAULT_SSH_PORT);
    }
}
