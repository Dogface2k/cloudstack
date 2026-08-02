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

import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
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
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.Nic;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
    public void provisionFirewallRulesLeavesUnownedLegacyRuleInPlace() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        FirewallRuleVO existingRule = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(existingRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS));
        Mockito.when(firewallRulesDaoMock.listByIpPurposePortsProtocolAndNotRevoked(10L, 6443, 6443,
                NetUtils.TCP_PROTO, FirewallRule.Purpose.Firewall)).thenReturn(List.of(existingRule));

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443);

        Mockito.verify(firewallRulesDaoMock).loadSourceCidrs(existingRule);
        Mockito.verify(kubernetesClusterResourceModifierActionWorker.firewallService, Mockito.never()).applyIngressFwRules(10L, account);
        Mockito.verify(kubernetesClusterResourceModifierActionWorker.firewallService, Mockito.never()).createIngressFirewallRule(Mockito.any());
    }

    @Test
    public void provisionPortForwardingRuleLeavesUnownedLegacyRuleInPlace() throws Exception {
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

        Mockito.verify(rulesServiceMock, Mockito.never()).applyPortForwardingRules(publicIpId, account);
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
    public void provisionVpcTierAclRuleLeavesUnownedLegacyRuleInPlace() throws Exception {
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

        kubernetesClusterResourceModifierActionWorker.provisionVpcTierAllowPortACLRule(network, 6443, 6443);

        Mockito.verify(networkACLServiceMock, Mockito.never()).applyNetworkACL(Mockito.anyLong());
        Mockito.verify(networkACLServiceMock, Mockito.never()).createNetworkACLItem(Mockito.any());
    }

    @Test
    public void provisionFirewallRulesReusesOnlyManifestedRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        FirewallRuleVO ownedRule = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(kubernetesClusterDetailsDaoMock.findDetail(1L, KubernetesClusterActionWorker.MANAGED_FIREWALL_RULE_IDS))
                .thenReturn(new com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO(1L, KubernetesClusterActionWorker.MANAGED_FIREWALL_RULE_IDS, "20", false));
        Mockito.when(firewallRulesDaoMock.findById(20L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(ownedRule.getSourceIpAddressId()).thenReturn(10L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getPurpose()).thenReturn(FirewallRule.Purpose.Firewall);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443);

        Mockito.verify(firewallServiceMock).applyIngressFwRules(10L, account);
        Mockito.verify(firewallServiceMock, Mockito.never()).createIngressFirewallRule(Mockito.any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void malformedNetworkRuleManifestFailsClosed() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(kubernetesClusterMock.getName()).thenReturn("cluster");
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(kubernetesClusterDetailsDaoMock.findDetail(1L, KubernetesClusterActionWorker.MANAGED_FIREWALL_RULE_IDS))
                .thenReturn(new com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO(1L,
                        KubernetesClusterActionWorker.MANAGED_FIREWALL_RULE_IDS, "not-an-id", false));

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443);
    }

    @Test
    public void cleanupDoesNotTouchUnmanifestedFirewallRule() {
        IpAddress publicIp = Mockito.mock(IpAddress.class);

        Assert.assertNull(kubernetesClusterResourceModifierActionWorker.removeApiFirewallRule(publicIp));
        Mockito.verifyNoInteractions(firewallServiceMock, firewallRulesDaoMock);
    }

    @Test
    public void cleanupDoesNotTouchUnmanifestedLoadBalancerRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);

        kubernetesClusterResourceModifierActionWorker.removeLoadBalancingRule(publicIp, network, account);

        Mockito.verifyNoInteractions(loadBalancerDaoMock, loadBalancingRulesServiceMock);
    }

    @Test
    public void provisionPortForwardingRuleReusesOnlyManifestedRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        Nic nic = Mockito.mock(Nic.class);
        PortForwardingRuleVO ownedRule = Mockito.mock(PortForwardingRuleVO.class);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(kubernetesClusterDetailsDaoMock.findDetail(1L, KubernetesClusterActionWorker.MANAGED_PORT_FORWARDING_RULE_IDS))
                .thenReturn(new com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO(1L, KubernetesClusterActionWorker.MANAGED_PORT_FORWARDING_RULE_IDS, "21", false));
        Mockito.when(portForwardingRulesDaoMock.findById(21L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(ownedRule.getSourceIpAddressId()).thenReturn(10L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(2222);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(2222);
        Mockito.when(ownedRule.getDestinationPortStart()).thenReturn(22);
        Mockito.when(ownedRule.getDestinationPortEnd()).thenReturn(22);
        Mockito.when(ownedRule.getVirtualMachineId()).thenReturn(40L);
        Mockito.when(ownedRule.getNetworkId()).thenReturn(20L);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getDestinationIpAddress()).thenReturn(new Ip("10.1.1.10"));
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(40L, 20L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.1.1.10");

        kubernetesClusterResourceModifierActionWorker.provisionPublicIpPortForwardingRule(publicIp, network, account, 40L, 2222, 22);

        Mockito.verify(rulesServiceMock).applyPortForwardingRules(10L, account);
        Mockito.verify(portForwardingRulesDaoMock, Mockito.never()).persist(Mockito.any());
    }

    @Test
    public void provisionPortForwardingRuleRecordsNewRuleOwnership() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        Nic nic = Mockito.mock(Nic.class);
        PortForwardingRuleVO createdRule = Mockito.mock(PortForwardingRuleVO.class);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(account.getDomainId()).thenReturn(40L);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(40L, 20L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.1.1.10");
        Mockito.when(portForwardingRulesDaoMock.listByIpAndNotRevoked(10L)).thenReturn(List.of());
        Mockito.when(portForwardingRulesDaoMock.persist(Mockito.any(PortForwardingRuleVO.class))).thenReturn(createdRule);
        Mockito.when(createdRule.getId()).thenReturn(41L);

        kubernetesClusterResourceModifierActionWorker.provisionPublicIpPortForwardingRule(publicIp, network, account, 40L, 2222, 22);

        Mockito.verify(kubernetesClusterDetailsDaoMock).addDetail(1L, KubernetesClusterActionWorker.MANAGED_PORT_FORWARDING_RULE_IDS, "41", false);
        Mockito.verify(rulesServiceMock).applyPortForwardingRules(10L, account);
    }

    @Test
    public void provisionVpcTierAclRuleReusesOnlyManifestedRule() throws Exception {
        Network network = Mockito.mock(Network.class);
        NetworkACLItemVO ownedRule = Mockito.mock(NetworkACLItemVO.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(network.getNetworkACLId()).thenReturn(50L);
        Mockito.when(kubernetesClusterDetailsDaoMock.findDetail(1L, KubernetesClusterActionWorker.MANAGED_NETWORK_ACL_ITEM_IDS))
                .thenReturn(new com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO(1L, KubernetesClusterActionWorker.MANAGED_NETWORK_ACL_ITEM_IDS, "22", false));
        Mockito.when(networkACLItemDaoMock.findById(22L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getState()).thenReturn(NetworkACLItem.State.Active);
        Mockito.when(ownedRule.getAclId()).thenReturn(50L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getTrafficType()).thenReturn(NetworkACLItem.TrafficType.Ingress);
        Mockito.when(ownedRule.getAction()).thenReturn(NetworkACLItem.Action.Allow);

        kubernetesClusterResourceModifierActionWorker.provisionVpcTierAllowPortACLRule(network, 6443, 6443);

        Mockito.verify(networkACLServiceMock).applyNetworkACL(50L);
        Mockito.verify(networkACLServiceMock, Mockito.never()).createNetworkACLItem(Mockito.any());
    }

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionLoadBalancerRuleRejectsAnOverlappingRuleWithDifferentShape() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getId()).thenReturn(60L);
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);
        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, Mockito.mock(Network.class), Mockito.mock(Account.class),
                List.of(), 6443);
    }

    @Test
    public void provisionLoadBalancerRuleLeavesUnownedLegacyRuleInPlace() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getState()).thenReturn(FirewallRule.State.Active);
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
        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, network, account, List.of(), 6443);
        Mockito.verify(loadBalancingRulesServiceMock, Mockito.never()).createPublicLoadBalancerRule(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyLong(), Mockito.any(), Mockito.any(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyBoolean(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test
    public void provisionLoadBalancerRuleReusesOnlyManifestedRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        LoadBalancerVO ownedRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(kubernetesClusterDetailsDaoMock.findDetail(1L, KubernetesClusterActionWorker.MANAGED_LOAD_BALANCER_RULE_IDS))
                .thenReturn(new com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO(1L, KubernetesClusterActionWorker.MANAGED_LOAD_BALANCER_RULE_IDS, "23", false));
        Mockito.when(loadBalancerDaoMock.findById(23L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(ownedRule.getSourceIpAddressId()).thenReturn(10L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getDefaultPortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getDefaultPortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getNetworkId()).thenReturn(20L);
        Mockito.when(ownedRule.getAccountId()).thenReturn(30L);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getLbProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getName()).thenReturn("api-lb");
        Mockito.when(ownedRule.getAlgorithm()).thenReturn("roundrobin");
        Mockito.when(ownedRule.getId()).thenReturn(23L);
        Mockito.when(network.getId()).thenReturn(20L);
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(loadBalancerVMMapDaoMock.listByLoadBalancerId(23L, false)).thenReturn(List.of());
        Mockito.when(loadBalancingRulesServiceMock.applyLoadBalancerConfig(23L)).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, network, account, List.of(), 6443);

        Mockito.verify(loadBalancingRulesServiceMock).applyLoadBalancerConfig(23L);
        Mockito.verify(loadBalancingRulesServiceMock, Mockito.never()).createPublicLoadBalancerRule(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyLong(), Mockito.any(), Mockito.any(),
                Mockito.anyLong(), Mockito.anyLong(), Mockito.anyBoolean(), Mockito.any(), Mockito.anyBoolean());
    }

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionLoadBalancerRuleRejectsAnOverlappingRuleWithMinimalMetadata() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        LoadBalancerVO existingRule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(loadBalancerDaoMock.listByIpAddress(10L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);

        kubernetesClusterResourceModifierActionWorker.provisionLoadBalancerRule(publicIp, Mockito.mock(Network.class), Mockito.mock(Account.class), List.of(), 6443);
    }
}
