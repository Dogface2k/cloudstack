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
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterFirewallRuleMapDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.utils.Pair;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmVO;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterRemoveWorkerTest {

    @Mock
    private KubernetesCluster cluster;
    @Mock
    private KubernetesClusterManagerImpl manager;
    @Mock
    private KubernetesClusterDao clusterDao;
    @Mock
    private KubernetesClusterFirewallRuleMapDao firewallRuleMapDao;
    @Mock
    private PortForwardingRulesDao portForwardingRulesDao;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private Network network;
    @Mock
    private IpAddress publicIp;
    @Mock
    private UserVmVO vm;

    private KubernetesClusterRemoveWorker worker;

    @Before
    public void setUp() {
        manager.kubernetesClusterDao = clusterDao;
        manager.kubernetesClusterFirewallRuleMapDao = firewallRuleMapDao;
        worker = Mockito.spy(new KubernetesClusterRemoveWorker(cluster, manager));
        worker.portForwardingRulesDao = portForwardingRulesDao;
        worker.networkModel = networkModel;
        Mockito.when(cluster.getId()).thenReturn(1L);
    }

    @Test
    public void directRemovedNodeUsesClusterNicWithoutPortForwarding() throws Exception {
        Nic nic = Mockito.mock(Nic.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(networkModel.getNicInNetwork(3L, 2L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("192.0.2.10");

        Pair<String, Integer> endpoint = worker.getRemovedNodeIpSshPort(3L, network, null);

        Assert.assertEquals("192.0.2.10", endpoint.first());
        Assert.assertEquals(Integer.valueOf(KubernetesClusterActionWorker.DEFAULT_SSH_PORT), endpoint.second());
        Mockito.verifyNoInteractions(firewallRuleMapDao, portForwardingRulesDao);
    }

    @Test
    public void nonDirectRemovedNodeUsesItsOwnedPortForwardingRule() throws Exception {
        KubernetesClusterFirewallRuleMapVO mapping = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        PortForwardingRuleVO rule = Mockito.mock(PortForwardingRuleVO.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(publicIp.getId()).thenReturn(4L);
        Mockito.when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        Mockito.when(firewallRuleMapDao.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 3L)).thenReturn(mapping);
        Mockito.when(mapping.getFirewallRuleId()).thenReturn(5L);
        Mockito.when(portForwardingRulesDao.findById(5L)).thenReturn(rule);
        Mockito.when(rule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(rule.getSourceIpAddressId()).thenReturn(4L);
        Mockito.when(rule.getNetworkId()).thenReturn(2L);
        Mockito.when(rule.getDestinationPortStart()).thenReturn(KubernetesClusterActionWorker.DEFAULT_SSH_PORT);
        Mockito.when(rule.getVirtualMachineId()).thenReturn(3L);
        Mockito.when(rule.getSourcePortStart()).thenReturn(2222);

        Pair<String, Integer> endpoint = worker.getRemovedNodeIpSshPort(3L, network, publicIp);

        Assert.assertEquals("203.0.113.10", endpoint.first());
        Assert.assertEquals(Integer.valueOf(2222), endpoint.second());
    }

    @Test
    public void legacyDirectRemovalNeverDeletesUnownedRules() {
        KubernetesClusterVO persisted = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.when(clusterDao.findById(1L)).thenReturn(persisted);
        Mockito.when(persisted.getNetworkRuleOwnershipState())
                .thenReturn(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);

        Assert.assertTrue(worker.removeNodePortForwardingRules(3L, network, vm));

        Mockito.verifyNoInteractions(firewallRuleMapDao);
    }

    @Test
    public void managedDirectRemovalDeletesOnlyExplicitMappedLeftovers() throws Exception {
        KubernetesClusterVO persisted = Mockito.mock(KubernetesClusterVO.class);
        KubernetesClusterFirewallRuleMapVO portForwarding = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        KubernetesClusterFirewallRuleMapVO firewall = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.when(network.getVpcId()).thenReturn(null);
        Mockito.when(clusterDao.findById(1L)).thenReturn(persisted);
        Mockito.when(persisted.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        Mockito.when(firewallRuleMapDao.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 3L)).thenReturn(portForwarding);
        Mockito.when(firewallRuleMapDao.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.EXTERNAL_SSH_FIREWALL_ROLE_PREFIX + 3L)).thenReturn(firewall);
        Mockito.doNothing().when(worker).deleteManagedPortForwardingRule(portForwarding);
        Mockito.doNothing().when(worker).deleteManagedFirewallRule(firewall);

        Assert.assertTrue(worker.removeNodePortForwardingRules(3L, network, vm));

        Mockito.verify(worker).deleteManagedPortForwardingRule(portForwarding);
        Mockito.verify(worker).deleteManagedFirewallRule(firewall);
    }
}
