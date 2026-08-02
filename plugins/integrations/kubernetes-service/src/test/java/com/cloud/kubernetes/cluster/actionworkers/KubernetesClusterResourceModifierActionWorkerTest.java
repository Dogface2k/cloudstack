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

import java.util.List;
import java.util.Map;

import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.firewall.FirewallService;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesService;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLService;
import com.cloud.user.Account;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterResourceModifierActionWorkerTest {
    @Mock
    private KubernetesClusterDao kubernetesClusterDaoMock;

    @Mock
    private KubernetesClusterDetailsDao kubernetesClusterDetailsDaoMock;

    @Mock
    private KubernetesClusterVmMapDao kubernetesClusterVmMapDaoMock;

    @Mock
    private KubernetesSupportedVersionDao kubernetesSupportedVersionDaoMock;

    @Mock
    private KubernetesClusterManagerImpl kubernetesClusterManagerMock;

    @Mock
    private KubernetesCluster kubernetesClusterMock;

    @Mock
    private FirewallRulesDao firewallRulesDaoMock;

    @Mock
    private FirewallService firewallServiceMock;

    @Mock
    private PortForwardingRulesDao portForwardingRulesDaoMock;

    @Mock
    private RulesService rulesServiceMock;

    @Mock
    private NetworkACLItemDao networkACLItemDaoMock;

    @Mock
    private NetworkACLService networkACLServiceMock;

    @Mock
    private LoadBalancerDao loadBalancerDaoMock;

    @Mock
    private LoadBalancerVMMapDao loadBalancerVMMapDaoMock;

    @Mock
    private LoadBalancingRulesService loadBalancingRulesServiceMock;

    private KubernetesClusterResourceModifierActionWorker kubernetesClusterResourceModifierActionWorker;

    @Before
    public void setUp() {
        kubernetesClusterManagerMock.kubernetesClusterDao = kubernetesClusterDaoMock;
        kubernetesClusterManagerMock.kubernetesSupportedVersionDao = kubernetesSupportedVersionDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterDetailsDao = kubernetesClusterDetailsDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterVmMapDao = kubernetesClusterVmMapDaoMock;

        kubernetesClusterResourceModifierActionWorker = new KubernetesClusterResourceModifierActionWorker(kubernetesClusterMock, kubernetesClusterManagerMock);
        kubernetesClusterResourceModifierActionWorker.firewallRulesDao = firewallRulesDaoMock;
        kubernetesClusterResourceModifierActionWorker.firewallService = firewallServiceMock;
        kubernetesClusterResourceModifierActionWorker.portForwardingRulesDao = portForwardingRulesDaoMock;
        kubernetesClusterResourceModifierActionWorker.rulesService = rulesServiceMock;
        kubernetesClusterResourceModifierActionWorker.networkACLItemDao = networkACLItemDaoMock;
        kubernetesClusterResourceModifierActionWorker.networkACLService = networkACLServiceMock;
        kubernetesClusterResourceModifierActionWorker.loadBalancerDao = loadBalancerDaoMock;
        kubernetesClusterResourceModifierActionWorker.loadBalancerVMMapDao = loadBalancerVMMapDaoMock;
        kubernetesClusterResourceModifierActionWorker.lbService = loadBalancingRulesServiceMock;
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestReturnOriginalPrefixWhenNamingAllRequirementsAreMet() {
        String originalPrefix = "k8s-cluster-01";
        String expectedPrefix = "k8s-cluster-01";

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        Assert.assertEquals(expectedPrefix, kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldOnlyContainLowerCaseCharacters() {
        String originalPrefix = "k8s-CLUSTER-01";
        String expectedPrefix = "k8s-cluster-01";

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        Assert.assertEquals(expectedPrefix, kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldBeTruncatedWhenRequired() {
        int maxPrefixLength = 43;

        String originalPrefix = "c".repeat(maxPrefixLength + 1);
        String expectedPrefix = "c".repeat(maxPrefixLength);

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        String normalizedPrefix = kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix();
        Assert.assertEquals(expectedPrefix, normalizedPrefix);
        Assert.assertEquals(maxPrefixLength, normalizedPrefix.length());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldBeTruncatedWhenRequiredAndWhenOriginalPrefixIsInvalid() {
        int maxPrefixLength = 43;

        String originalPrefix = "1!" + "c".repeat(maxPrefixLength);
        String expectedPrefix = "k8s-1" + "c".repeat(maxPrefixLength - 5);

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        String normalizedPrefix = kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix();
        Assert.assertEquals(expectedPrefix, normalizedPrefix);
        Assert.assertEquals(maxPrefixLength, normalizedPrefix.length());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldOnlyIncludeAlphanumericCharactersAndHyphen() {
        String originalPrefix = "Cluster!@#$%^&*()_+?.-01|<>";
        String expectedPrefix = "k8s-cluster-01";

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        Assert.assertEquals(expectedPrefix, kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldContainClusterUuidWhenAllCharactersAreInvalid() {
        String clusterUuid = "2699b547-cb56-4a59-a2c6-331cfb21d2e4";
        String originalPrefix = "!@#$%^&*()_+?.|<>";
        String expectedPrefix = "k8s-" + clusterUuid;

        Mockito.when(kubernetesClusterMock.getUuid()).thenReturn(clusterUuid);
        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        Assert.assertEquals(expectedPrefix, kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix());
    }

    @Test
    public void getKubernetesClusterNodeNamePrefixTestNormalizedPrefixShouldNotStartWithADigit() {
        String originalPrefix = "1 cluster";
        String expectedPrefix = "k8s-1cluster";

        Mockito.when(kubernetesClusterMock.getName()).thenReturn(originalPrefix);
        Assert.assertEquals(expectedPrefix, kubernetesClusterResourceModifierActionWorker.getKubernetesClusterNodeNamePrefix());
    }

    @Test
    public void provisionFirewallRulesReusesAnExistingMatchingRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        FirewallRuleVO existingRule = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(existingRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS));
        Mockito.when(firewallRulesDaoMock.listByIpPurposePortsProtocolAndNotRevoked(10L, 6443, 6443,
                NetUtils.TCP_PROTO, FirewallRule.Purpose.Firewall)).thenReturn(List.of(existingRule));

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443);

        Mockito.verify(firewallRulesDaoMock).loadSourceCidrs(existingRule);
        Mockito.verify(kubernetesClusterResourceModifierActionWorker.firewallService).applyIngressFwRules(10L, account);
        Mockito.verify(kubernetesClusterResourceModifierActionWorker.firewallService, Mockito.never()).createIngressFirewallRule(Mockito.any());
    }

    @Test
    public void provisionPortForwardingRuleReusesAnExistingMatchingRule() throws Exception {
        long publicIpId = 10L;
        long networkId = 20L;
        long accountId = 30L;
        long vmId = 40L;
        int sourcePort = 2222;
        int destinationPort = 22;
        Ip vmIp = new Ip("10.1.1.10");
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        Nic nic = Mockito.mock(Nic.class);
        PortForwardingRuleVO existingRule = Mockito.mock(PortForwardingRuleVO.class);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(publicIp.getId()).thenReturn(publicIpId);
        Mockito.when(network.getId()).thenReturn(networkId);
        Mockito.when(account.getId()).thenReturn(accountId);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(vmId, networkId)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn(vmIp.addr());
        Mockito.when(portForwardingRulesDaoMock.listByIpAndNotRevoked(publicIpId)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(sourcePort);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(sourcePort);
        Mockito.when(existingRule.getDestinationPortStart()).thenReturn(destinationPort);
        Mockito.when(existingRule.getDestinationPortEnd()).thenReturn(destinationPort);
        Mockito.when(existingRule.getVirtualMachineId()).thenReturn(vmId);
        Mockito.when(existingRule.getNetworkId()).thenReturn(networkId);
        Mockito.when(existingRule.getAccountId()).thenReturn(accountId);
        Mockito.when(existingRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getDestinationIpAddress()).thenReturn(vmIp);

        kubernetesClusterResourceModifierActionWorker.provisionPublicIpPortForwardingRule(publicIp, network, account,
                vmId, sourcePort, destinationPort);

        Mockito.verify(rulesServiceMock).applyPortForwardingRules(publicIpId, account);
        Mockito.verify(portForwardingRulesDaoMock, Mockito.never()).persist(Mockito.any());
    }

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionPortForwardingRuleRejectsAnOverlappingRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        Nic nic = Mockito.mock(Nic.class);
        PortForwardingRuleVO existingRule = Mockito.mock(PortForwardingRuleVO.class);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(40L, 20L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.1.1.10");
        Mockito.when(portForwardingRulesDaoMock.listByIpAndNotRevoked(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(2222);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(2222);
        Mockito.when(existingRule.getDestinationPortStart()).thenReturn(80);

        kubernetesClusterResourceModifierActionWorker.provisionPublicIpPortForwardingRule(publicIp, network, account,
                40L, 2222, 22);
    }

    @Test
    public void provisionVpcTierAclRuleReusesAnExistingMatchingRule() throws Exception {
        Network network = Mockito.mock(Network.class);
        NetworkACLItemVO existingRule = Mockito.mock(NetworkACLItemVO.class);
        Mockito.when(network.getNetworkACLId()).thenReturn(50L);
        Mockito.when(networkACLItemDaoMock.listByACL(50L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getState()).thenReturn(NetworkACLItem.State.Active);
        Mockito.when(existingRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(existingRule.getTrafficType()).thenReturn(NetworkACLItem.TrafficType.Ingress);
        Mockito.when(existingRule.getAction()).thenReturn(NetworkACLItem.Action.Allow);
        Mockito.when(existingRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS));
        Mockito.when(existingRule.getAclId()).thenReturn(50L);

        kubernetesClusterResourceModifierActionWorker.provisionVpcTierAllowPortACLRule(network, 6443, 6443);

        Mockito.verify(networkACLServiceMock).applyNetworkACL(50L);
        Mockito.verify(networkACLServiceMock, Mockito.never()).createNetworkACLItem(Mockito.any());
    }

    @SuppressWarnings("unchecked")
    @Test
    public void provisionLoadBalancerRuleReusesRuleAndAddsOnlyMissingControlNodeMapping() throws Exception {
        long publicIpId = 10L;
        long networkId = 20L;
        long accountId = 30L;
        long existingVmId = 40L;
        long missingVmId = 41L;
        int port = 6443;
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        LoadBalancerVMMapVO existingMapping = Mockito.mock(LoadBalancerVMMapVO.class);
        KubernetesClusterVmMapVO existingVmMap = Mockito.mock(KubernetesClusterVmMapVO.class);
        KubernetesClusterVmMapVO missingVmMap = Mockito.mock(KubernetesClusterVmMapVO.class);
        Nic existingNic = Mockito.mock(Nic.class);
        Nic missingNic = Mockito.mock(Nic.class);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(publicIp.getId()).thenReturn(publicIpId);
        Mockito.when(network.getId()).thenReturn(networkId);
        Mockito.when(account.getId()).thenReturn(accountId);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(kubernetesClusterMock.getNetworkId()).thenReturn(networkId);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(publicIpId)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getId()).thenReturn(60L);
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(port);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(port);
        Mockito.when(existingRule.getDefaultPortStart()).thenReturn(port);
        Mockito.when(existingRule.getDefaultPortEnd()).thenReturn(port);
        Mockito.when(existingRule.getNetworkId()).thenReturn(networkId);
        Mockito.when(existingRule.getAccountId()).thenReturn(accountId);
        Mockito.when(existingRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getLbProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getName()).thenReturn("api-lb");
        Mockito.when(existingRule.getAlgorithm()).thenReturn("roundrobin");
        Mockito.when(loadBalancerVMMapDaoMock.listByLoadBalancerId(60L, false)).thenReturn(List.of(existingMapping));
        Mockito.when(existingMapping.getInstanceId()).thenReturn(existingVmId);
        Mockito.when(existingMapping.getInstanceIp()).thenReturn("10.1.1.10");
        Mockito.when(kubernetesClusterVmMapDaoMock.listByClusterId(1L)).thenReturn(List.of(existingVmMap, missingVmMap));
        Mockito.when(existingVmMap.getVmId()).thenReturn(existingVmId);
        Mockito.when(missingVmMap.getVmId()).thenReturn(missingVmId);
        Mockito.when(existingVmMap.isControlNode()).thenReturn(true);
        Mockito.when(missingVmMap.isControlNode()).thenReturn(true);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(existingVmId, networkId)).thenReturn(existingNic);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(missingVmId, networkId)).thenReturn(missingNic);
        Mockito.when(existingNic.getIPv4Address()).thenReturn("10.1.1.10");
        Mockito.when(missingNic.getIPv4Address()).thenReturn("10.1.1.11");
        Mockito.when(loadBalancingRulesServiceMock.assignToLoadBalancer(Mockito.eq(60L), Mockito.isNull(), Mockito.anyMap(),
                Mockito.isNull(), Mockito.eq(false))).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, network, account,
                List.of(existingVmId, missingVmId), port);

        Mockito.verify(loadBalancingRulesServiceMock, Mockito.never()).createPublicLoadBalancerRule(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyLong(), Mockito.any(), Mockito.any(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyBoolean(), Mockito.any(), Mockito.anyBoolean());
        ArgumentCaptor<Map<Long, List<String>>> mappings = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(loadBalancingRulesServiceMock).assignToLoadBalancer(Mockito.eq(60L), Mockito.isNull(), mappings.capture(),
                Mockito.isNull(), Mockito.eq(false));
        Assert.assertEquals(Map.of(missingVmId, List.of("10.1.1.11")), mappings.getValue());
    }

    @Test
    public void provisionLoadBalancerRuleReappliesExistingCompleteRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getId()).thenReturn(60L);
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(existingRule.getDefaultPortStart()).thenReturn(6443);
        Mockito.when(existingRule.getDefaultPortEnd()).thenReturn(6443);
        Mockito.when(existingRule.getNetworkId()).thenReturn(20L);
        Mockito.when(existingRule.getAccountId()).thenReturn(30L);
        Mockito.when(existingRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getLbProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getName()).thenReturn("api-lb");
        Mockito.when(existingRule.getAlgorithm()).thenReturn("roundrobin");
        Mockito.when(loadBalancerVMMapDaoMock.listByLoadBalancerId(60L, false)).thenReturn(List.of());
        Mockito.when(kubernetesClusterVmMapDaoMock.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(loadBalancingRulesServiceMock.applyLoadBalancerConfig(60L)).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, network, account, List.of(), 6443);

        Mockito.verify(loadBalancingRulesServiceMock).applyLoadBalancerConfig(60L);
        Mockito.verify(loadBalancingRulesServiceMock, Mockito.never()).assignToLoadBalancer(Mockito.anyLong(), Mockito.any(), Mockito.anyMap(),
                Mockito.any(), Mockito.anyBoolean());
    }

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionLoadBalancerRuleRejectsAnOverlappingRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(existingRule.getDefaultPortStart()).thenReturn(80);

        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, network, account, List.of(40L), 6443);
    }
}
