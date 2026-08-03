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
import java.util.Set;

import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkACLItemMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleLifecycleState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleRole;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterFirewallRuleMapDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterNetworkACLItemMapDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
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
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.network.vpc.dao.NetworkACLDao;
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
    private static class TestKubernetesClusterResourceModifierActionWorker extends KubernetesClusterResourceModifierActionWorker {
        private int provisionAclRuleCalls;

        TestKubernetesClusterResourceModifierActionWorker(KubernetesCluster kubernetesCluster, KubernetesClusterManagerImpl clusterManager) {
            super(kubernetesCluster, clusterManager);
        }

        @Override
        protected void provisionVpcTierAllowPortACLRule(Network network, int startPort, int endPort, String logicalRole) {
            provisionAclRuleCalls++;
        }
    }

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
    private KubernetesClusterVO lockedKubernetesClusterMock;

    @Mock
    private KubernetesClusterFirewallRuleMapDao kubernetesClusterFirewallRuleMapDaoMock;

    @Mock
    private KubernetesClusterNetworkACLItemMapDao kubernetesClusterNetworkACLItemMapDaoMock;

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
    private NetworkDao networkDaoMock;
    @Mock
    private NetworkACLDao networkACLDaoMock;

    @Mock
    private LoadBalancerDao loadBalancerDaoMock;

    @Mock
    private LoadBalancerVMMapDao loadBalancerVMMapDaoMock;

    @Mock
    private LoadBalancingRulesService loadBalancingRulesServiceMock;

    private KubernetesClusterResourceModifierActionWorker kubernetesClusterResourceModifierActionWorker;

    @Before
    public void setUp() throws Exception {
        kubernetesClusterManagerMock.kubernetesClusterDao = kubernetesClusterDaoMock;
        kubernetesClusterManagerMock.kubernetesSupportedVersionDao = kubernetesSupportedVersionDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterDetailsDao = kubernetesClusterDetailsDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterVmMapDao = kubernetesClusterVmMapDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterFirewallRuleMapDao = kubernetesClusterFirewallRuleMapDaoMock;
        kubernetesClusterManagerMock.kubernetesClusterNetworkACLItemMapDao = kubernetesClusterNetworkACLItemMapDaoMock;

        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(kubernetesClusterDaoMock.lockRow(1L, true)).thenReturn(lockedKubernetesClusterMock);
        Mockito.when(lockedKubernetesClusterMock.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        kubernetesClusterResourceModifierActionWorker = new KubernetesClusterResourceModifierActionWorker(kubernetesClusterMock, kubernetesClusterManagerMock);
        kubernetesClusterResourceModifierActionWorker.firewallRulesDao = firewallRulesDaoMock;
        kubernetesClusterResourceModifierActionWorker.firewallService = firewallServiceMock;
        kubernetesClusterResourceModifierActionWorker.portForwardingRulesDao = portForwardingRulesDaoMock;
        kubernetesClusterResourceModifierActionWorker.rulesService = rulesServiceMock;
        kubernetesClusterResourceModifierActionWorker.networkACLItemDao = networkACLItemDaoMock;
        kubernetesClusterResourceModifierActionWorker.networkACLService = networkACLServiceMock;
        kubernetesClusterResourceModifierActionWorker.networkDao = networkDaoMock;
        kubernetesClusterResourceModifierActionWorker.networkACLDao = networkACLDaoMock;
        kubernetesClusterResourceModifierActionWorker.loadBalancerDao = loadBalancerDaoMock;
        kubernetesClusterResourceModifierActionWorker.loadBalancerVMMapDao = loadBalancerVMMapDaoMock;
        kubernetesClusterResourceModifierActionWorker.lbService = loadBalancingRulesServiceMock;

        Mockito.when(firewallServiceMock.applyIngressFwRules(Mockito.anyLong(), Mockito.any())).thenReturn(true);
        Mockito.when(rulesServiceMock.applyPortForwardingRules(Mockito.anyLong(), Mockito.any())).thenReturn(true);
        Mockito.when(networkACLServiceMock.applyNetworkACL(Mockito.anyLong())).thenReturn(true);
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

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionFirewallRulesRejectsUnownedLegacyRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        FirewallRuleVO existingRule = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(existingRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS));
        Mockito.when(firewallRulesDaoMock.listByIpPurposePortsProtocolAndNotRevoked(10L, 6443, 6443,
                NetUtils.TCP_PROTO, FirewallRule.Purpose.Firewall)).thenReturn(List.of(existingRule));

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE);
    }

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionPortForwardingRuleRejectsUnownedLegacyRule() throws Exception {
        long publicIpId = 10L;
        long networkId = 20L;
        long accountId = 30L;
        long vmId = 40L;
        int sourcePort = 2222;
        int destinationPort = 22;
        Ip vmIp = new Ip("10.1.1.10");
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        com.cloud.network.dao.NetworkVO network = Mockito.mock(com.cloud.network.dao.NetworkVO.class);
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

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionVpcTierAclRuleRejectsUnownedLegacyRule() throws Exception {
        com.cloud.network.dao.NetworkVO network = Mockito.mock(com.cloud.network.dao.NetworkVO.class);
        NetworkACLItemVO existingRule = Mockito.mock(NetworkACLItemVO.class);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(network.getNetworkACLId()).thenReturn(50L);
        Mockito.when(networkDaoMock.findById(20L)).thenReturn(network);
        Mockito.when(networkACLItemDaoMock.listByACL(50L)).thenReturn(List.of(existingRule));
        Mockito.when(existingRule.getState()).thenReturn(NetworkACLItem.State.Active);
        Mockito.when(existingRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(existingRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(existingRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(existingRule.getTrafficType()).thenReturn(NetworkACLItem.TrafficType.Ingress);
        Mockito.when(existingRule.getAction()).thenReturn(NetworkACLItem.Action.Allow);
        Mockito.when(existingRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS));

        kubernetesClusterResourceModifierActionWorker.provisionVpcTierAllowPortACLRule(network, 6443, 6443,
                KubernetesClusterActionWorker.API_ACL_ROLE);
    }

    @Test(expected = ManagementServerException.class)
    public void createVpcTierAclRulesWithoutAclFails() throws Exception {
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getNetworkACLId()).thenReturn(null);
        TestKubernetesClusterResourceModifierActionWorker worker =
                new TestKubernetesClusterResourceModifierActionWorker(kubernetesClusterMock, kubernetesClusterManagerMock);

        worker.createVpcTierAclRules(network);
    }

    @Test
    public void createVpcTierAclRulesWithDefaultAllowDoesNotProvisionRules() throws Exception {
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getNetworkACLId()).thenReturn(NetworkACL.DEFAULT_ALLOW);
        TestKubernetesClusterResourceModifierActionWorker worker =
                new TestKubernetesClusterResourceModifierActionWorker(kubernetesClusterMock, kubernetesClusterManagerMock);

        worker.createVpcTierAclRules(network);

        Assert.assertEquals(0, worker.provisionAclRuleCalls);
    }

    @Test
    public void provisionFirewallRulesReusesOnlyManifestedRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Account account = Mockito.mock(Account.class);
        FirewallRuleVO ownedRule = Mockito.mock(FirewallRuleVO.class);
        KubernetesClusterFirewallRuleMapVO ownership = mockFirewallOwnership(20L,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE, KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(account.getDomainId()).thenReturn(40L);
        Mockito.when(kubernetesClusterMock.getNetworkId()).thenReturn(20L);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE)).thenReturn(ownership);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByFirewallRuleId(20L)).thenReturn(ownership);
        Mockito.when(firewallRulesDaoMock.findById(20L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getId()).thenReturn(20L);
        Mockito.when(ownedRule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(ownedRule.getSourceIpAddressId()).thenReturn(10L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getNetworkId()).thenReturn(20L);
        Mockito.when(ownedRule.getAccountId()).thenReturn(30L);
        Mockito.when(ownedRule.getDomainId()).thenReturn(40L);
        Mockito.when(ownedRule.getPurpose()).thenReturn(FirewallRule.Purpose.Firewall);
        Mockito.when(ownedRule.getTrafficType()).thenReturn(FirewallRule.TrafficType.Ingress);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS));
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionFirewallRules(publicIp, account, 6443, 6443,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE);

        Mockito.verify(firewallServiceMock).applyIngressFwRules(10L, account);
        Mockito.verify(firewallServiceMock, Mockito.never()).createIngressFirewallRule(Mockito.any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void ownershipForAnotherClusterFailsClosed() {
        KubernetesClusterFirewallRuleMapVO foreignOwnership = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Mockito.when(foreignOwnership.getClusterId()).thenReturn(2L);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByFirewallRuleId(20L)).thenReturn(foreignOwnership);

        kubernetesClusterResourceModifierActionWorker.recordManagedFirewallRule(20L,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE);
    }

    @Test(expected = CloudRuntimeException.class)
    public void firewallRuleCreationFailsWhenOwnershipCannotBePersisted() {
        kubernetesClusterResourceModifierActionWorker.recordManagedFirewallRule(20L,
                KubernetesClusterActionWorker.API_FIREWALL_ROLE);
    }

    @Test(expected = CloudRuntimeException.class)
    public void networkAclItemCreationFailsWhenOwnershipCannotBePersisted() {
        kubernetesClusterResourceModifierActionWorker.recordManagedNetworkAclItem(20L,
                KubernetesClusterActionWorker.API_ACL_ROLE);
    }

    @Test
    public void deleteManagedNetworkRulesNotInDeletesOnlyStaleOwnership() throws Exception {
        Network network = Mockito.mock(Network.class);
        String desiredRole = KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD.toLogicalRole(40L);
        String staleRole = KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD.toLogicalRole(41L);
        KubernetesClusterFirewallRuleMapVO desired = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        KubernetesClusterFirewallRuleMapVO stale = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Mockito.when(desired.getFirewallRuleId()).thenReturn(20L);
        Mockito.when(desired.getLogicalRole()).thenReturn(desiredRole);
        Mockito.when(stale.getFirewallRuleId()).thenReturn(21L);
        Mockito.when(stale.getLogicalRole()).thenReturn(staleRole);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.listByClusterId(1L)).thenReturn(List.of(desired, stale));
        KubernetesClusterResourceModifierActionWorker spyWorker = Mockito.spy(kubernetesClusterResourceModifierActionWorker);
        Mockito.doNothing().when(spyWorker).deleteManagedFirewallRuleByRole(Mockito.any(), Mockito.eq(network));

        spyWorker.deleteManagedNetworkRulesNotIn(Set.of(desiredRole), network);

        Mockito.verify(spyWorker).deleteManagedFirewallRuleByRole(stale, network);
        Mockito.verify(spyWorker, Mockito.never()).deleteManagedFirewallRuleByRole(desired, network);
    }

    @Test
    public void provisionPortForwardingRuleReusesOnlyManifestedRule() throws Exception {
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        Network network = Mockito.mock(Network.class);
        Account account = Mockito.mock(Account.class);
        Nic nic = Mockito.mock(Nic.class);
        PortForwardingRuleVO ownedRule = Mockito.mock(PortForwardingRuleVO.class);
        KubernetesClusterFirewallRuleMapVO ownership = mockFirewallOwnership(21L,
                KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 40L,
                KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(account.getDomainId()).thenReturn(40L);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 40L)).thenReturn(ownership);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByFirewallRuleId(21L)).thenReturn(ownership);
        Mockito.when(portForwardingRulesDaoMock.findById(21L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getId()).thenReturn(21L);
        Mockito.when(ownedRule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(ownedRule.getSourceIpAddressId()).thenReturn(10L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(2222);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(2222);
        Mockito.when(ownedRule.getDestinationPortStart()).thenReturn(22);
        Mockito.when(ownedRule.getDestinationPortEnd()).thenReturn(22);
        Mockito.when(ownedRule.getVirtualMachineId()).thenReturn(40L);
        Mockito.when(ownedRule.getNetworkId()).thenReturn(20L);
        Mockito.when(ownedRule.getAccountId()).thenReturn(30L);
        Mockito.when(ownedRule.getDomainId()).thenReturn(40L);
        Mockito.when(ownedRule.getPurpose()).thenReturn(FirewallRule.Purpose.PortForwarding);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getDestinationIpAddress()).thenReturn(new Ip("10.1.1.10"));
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(40L, 20L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.1.1.10");
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);

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
        KubernetesClusterFirewallRuleMapVO ownership = mockFirewallOwnership(41L,
                KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 40L,
                KubernetesClusterNetworkRuleLifecycleState.PENDING_APPLY);
        kubernetesClusterResourceModifierActionWorker.networkModel = Mockito.mock(NetworkModel.class);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(account.getId()).thenReturn(30L);
        Mockito.when(account.getDomainId()).thenReturn(40L);
        Mockito.when(kubernetesClusterResourceModifierActionWorker.networkModel.getNicInNetwork(40L, 20L)).thenReturn(nic);
        Mockito.when(nic.getIPv4Address()).thenReturn("10.1.1.10");
        Mockito.when(portForwardingRulesDaoMock.listByIpAndNotRevoked(10L)).thenReturn(List.of());
        Mockito.when(portForwardingRulesDaoMock.persist(Mockito.any(PortForwardingRuleVO.class))).thenReturn(createdRule);
        Mockito.when(createdRule.getId()).thenReturn(41L);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.persist(Mockito.any(KubernetesClusterFirewallRuleMapVO.class))).thenReturn(ownership);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByFirewallRuleId(41L)).thenReturn(null, ownership);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionPublicIpPortForwardingRule(publicIp, network, account, 40L, 2222, 22);

        Mockito.verify(kubernetesClusterFirewallRuleMapDaoMock).persist(Mockito.argThat(mapping -> mapping.getClusterId() == 1L
                && mapping.getFirewallRuleId() == 41L
                && (KubernetesClusterActionWorker.SSH_PORT_FORWARD_ROLE_PREFIX + 40L).equals(mapping.getLogicalRole())));
        Mockito.verify(rulesServiceMock).applyPortForwardingRules(10L, account);
    }

    @Test
    public void provisionVpcTierAclRuleReloadsTierBeforeReusingManifestedRule() throws Exception {
        Network staleNetwork = Mockito.mock(Network.class);
        com.cloud.network.dao.NetworkVO effectiveNetwork = Mockito.mock(com.cloud.network.dao.NetworkVO.class);
        NetworkACLItemVO ownedRule = Mockito.mock(NetworkACLItemVO.class);
        KubernetesClusterNetworkACLItemMapVO ownership = mockAclOwnership(22L, KubernetesClusterActionWorker.API_ACL_ROLE,
                KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(staleNetwork.getId()).thenReturn(20L);
        Mockito.when(effectiveNetwork.getNetworkACLId()).thenReturn(50L);
        Mockito.when(networkDaoMock.findById(20L)).thenReturn(effectiveNetwork);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.API_ACL_ROLE)).thenReturn(ownership);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findByNetworkAclItemId(22L)).thenReturn(ownership);
        Mockito.when(networkACLItemDaoMock.findById(22L)).thenReturn(ownedRule);
        Mockito.when(ownedRule.getId()).thenReturn(22L);
        Mockito.when(ownedRule.getState()).thenReturn(NetworkACLItem.State.Active);
        Mockito.when(ownedRule.getAclId()).thenReturn(50L);
        Mockito.when(ownedRule.getSourcePortStart()).thenReturn(6443);
        Mockito.when(ownedRule.getSourcePortEnd()).thenReturn(6443);
        Mockito.when(ownedRule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(ownedRule.getTrafficType()).thenReturn(NetworkACLItem.TrafficType.Ingress);
        Mockito.when(ownedRule.getAction()).thenReturn(NetworkACLItem.Action.Allow);
        Mockito.when(ownedRule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS));
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.provisionVpcTierAllowPortACLRule(staleNetwork, 6443, 6443,
                KubernetesClusterActionWorker.API_ACL_ROLE);

        Mockito.verify(networkACLServiceMock).applyNetworkACL(50L);
        Mockito.verify(networkACLServiceMock, Mockito.never()).createNetworkACLItem(Mockito.any());
        Mockito.verify(staleNetwork, Mockito.never()).getNetworkACLId();
    }

    @Test(expected = CloudRuntimeException.class)
    public void deleteManagedAclItemRejectsRuleWhoseAclWasAttachedToAnotherTier() throws Exception {
        Network network = Mockito.mock(Network.class);
        com.cloud.network.dao.NetworkVO otherTier = Mockito.mock(com.cloud.network.dao.NetworkVO.class);
        NetworkACLItemVO item = Mockito.mock(NetworkACLItemVO.class);
        NetworkACLVO acl = Mockito.mock(NetworkACLVO.class);
        KubernetesClusterNetworkACLItemMapVO ownership = mockAclOwnership(22L,
                KubernetesClusterActionWorker.API_ACL_ROLE, KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(otherTier.getId()).thenReturn(21L);
        Mockito.when(networkACLItemDaoMock.findById(22L)).thenReturn(item);
        Mockito.when(item.getId()).thenReturn(22L);
        Mockito.when(item.getAclId()).thenReturn(50L);
        Mockito.when(networkACLDaoMock.findById(50L)).thenReturn(acl);
        Mockito.when(acl.getVpcId()).thenReturn(10L);
        Mockito.when(networkDaoMock.listByAclId(50L)).thenReturn(List.of(otherTier));

        try {
            kubernetesClusterResourceModifierActionWorker.deleteManagedNetworkAclItem(ownership, network);
        } finally {
            Mockito.verify(networkACLServiceMock, Mockito.never()).revokeNetworkACLItem(Mockito.anyLong());
        }
    }

    @Test
    public void deleteManagedAclItemForgetsOwnershipWhenItemIsAlreadyMissing() throws Exception {
        Network network = Mockito.mock(Network.class);
        KubernetesClusterNetworkACLItemMapVO ownership = mockAclOwnership(22L,
                KubernetesClusterActionWorker.API_ACL_ROLE, KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findByNetworkAclItemId(22L)).thenReturn(ownership);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.update(1022L, ownership)).thenReturn(true);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findById(1022L)).thenReturn(ownership);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.remove(1022L)).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.deleteManagedNetworkAclItem(ownership, network);

        Mockito.verify(networkACLServiceMock, Mockito.never()).revokeNetworkACLItem(Mockito.anyLong());
        Mockito.verify(kubernetesClusterNetworkACLItemMapDaoMock).remove(1022L);
    }

    @Test
    public void deleteManagedAclItemRevokesExactItemAndForgetsOwnership() throws Exception {
        com.cloud.network.dao.NetworkVO network = Mockito.mock(com.cloud.network.dao.NetworkVO.class);
        NetworkACLItemVO item = Mockito.mock(NetworkACLItemVO.class);
        NetworkACLVO acl = Mockito.mock(NetworkACLVO.class);
        KubernetesClusterNetworkACLItemMapVO ownership = mockAclOwnership(22L,
                KubernetesClusterActionWorker.API_ACL_ROLE, KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(network.getId()).thenReturn(20L);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(item.getId()).thenReturn(22L);
        Mockito.when(item.getAclId()).thenReturn(50L);
        Mockito.when(networkACLItemDaoMock.findById(22L)).thenReturn(item, item, null);
        Mockito.when(networkACLDaoMock.findById(50L)).thenReturn(acl);
        Mockito.when(acl.getVpcId()).thenReturn(10L);
        Mockito.when(networkDaoMock.listByAclId(50L)).thenReturn(List.of(network));
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findByNetworkAclItemId(22L)).thenReturn(ownership);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.update(1022L, ownership)).thenReturn(true);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.findById(1022L)).thenReturn(ownership);
        Mockito.when(kubernetesClusterNetworkACLItemMapDaoMock.remove(1022L)).thenReturn(true);
        Mockito.when(networkACLServiceMock.revokeNetworkACLItem(22L)).thenReturn(true);

        kubernetesClusterResourceModifierActionWorker.deleteManagedNetworkAclItem(ownership, network);

        Mockito.verify(networkACLServiceMock).revokeNetworkACLItem(22L);
        Mockito.verify(kubernetesClusterNetworkACLItemMapDaoMock).remove(1022L);
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

    @Test(expected = NetworkRuleConflictException.class)
    public void provisionLoadBalancerRuleRejectsUnownedLegacyRule() throws Exception {
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
        KubernetesClusterFirewallRuleMapVO ownership = mockFirewallOwnership(23L,
                KubernetesClusterActionWorker.API_LOAD_BALANCER_ROLE, KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(publicIp.getId()).thenReturn(10L);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByClusterIdAndLogicalRole(1L,
                KubernetesClusterActionWorker.API_LOAD_BALANCER_ROLE)).thenReturn(ownership);
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.findByFirewallRuleId(23L)).thenReturn(ownership);
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
        Mockito.when(kubernetesClusterFirewallRuleMapDaoMock.update(Mockito.anyLong(), Mockito.any())).thenReturn(true);

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

    private KubernetesClusterFirewallRuleMapVO mockFirewallOwnership(long ruleId, String logicalRole,
            KubernetesClusterNetworkRuleLifecycleState lifecycleState) {
        KubernetesClusterFirewallRuleMapVO ownership = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Mockito.when(ownership.getId()).thenReturn(ruleId + 1000L);
        Mockito.when(ownership.getClusterId()).thenReturn(1L);
        Mockito.when(ownership.getFirewallRuleId()).thenReturn(ruleId);
        Mockito.when(ownership.getLifecycleState()).thenReturn(lifecycleState);
        return ownership;
    }

    private KubernetesClusterNetworkACLItemMapVO mockAclOwnership(long itemId, String logicalRole,
            KubernetesClusterNetworkRuleLifecycleState lifecycleState) {
        KubernetesClusterNetworkACLItemMapVO ownership = Mockito.mock(KubernetesClusterNetworkACLItemMapVO.class);
        Mockito.when(ownership.getId()).thenReturn(itemId + 1000L);
        Mockito.when(ownership.getClusterId()).thenReturn(1L);
        Mockito.when(ownership.getNetworkAclItemId()).thenReturn(itemId);
        Mockito.when(ownership.getLogicalRole()).thenReturn(logicalRole);
        Mockito.when(ownership.getLifecycleState()).thenReturn(lifecycleState);
        return ownership;
    }
}
