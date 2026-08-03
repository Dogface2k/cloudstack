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

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkACLItemMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleAdoptionSpec;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleLifecycleState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleRole;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterFirewallRuleMapDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterNetworkACLItemMapDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.IpAddress;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterNetworkRuleOwnershipValidatorTest {

    @Mock
    private KubernetesClusterManagerImpl manager;
    @Mock
    private KubernetesCluster cluster;
    @Mock
    private KubernetesClusterDao clusterDao;
    @Mock
    private KubernetesClusterDetailsDao clusterDetailsDao;
    @Mock
    private KubernetesClusterVmMapDao vmMapDao;
    @Mock
    private KubernetesClusterFirewallRuleMapDao firewallRuleMapDao;
    @Mock
    private KubernetesClusterNetworkACLItemMapDao aclItemMapDao;
    @Mock
    private KubernetesSupportedVersionDao supportedVersionDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private NetworkACLDao networkACLDao;
    @Mock
    private NetworkOfferingDao networkOfferingDao;
    @Mock
    private FirewallRulesDao firewallRulesDao;
    @Mock
    private PortForwardingRulesDao portForwardingRulesDao;
    @Mock
    private LoadBalancerDao loadBalancerDao;
    @Mock
    private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Mock
    private NetworkACLItemDao networkACLItemDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private IpAddress publicIp;

    private KubernetesClusterStartWorker worker;
    private KubernetesClusterNetworkRuleOwnershipValidator validator;

    @Before
    public void setUp() {
        manager.kubernetesClusterDao = clusterDao;
        manager.kubernetesClusterDetailsDao = clusterDetailsDao;
        manager.kubernetesClusterVmMapDao = vmMapDao;
        manager.kubernetesClusterFirewallRuleMapDao = firewallRuleMapDao;
        manager.kubernetesClusterNetworkACLItemMapDao = aclItemMapDao;
        manager.kubernetesSupportedVersionDao = supportedVersionDao;
        worker = Mockito.spy(new KubernetesClusterStartWorker(cluster, manager));
        worker.networkDao = networkDao;
        worker.networkACLDao = networkACLDao;
        worker.networkOfferingDao = networkOfferingDao;
        worker.firewallRulesDao = firewallRulesDao;
        worker.portForwardingRulesDao = portForwardingRulesDao;
        worker.loadBalancerDao = loadBalancerDao;
        worker.loadBalancerVMMapDao = loadBalancerVMMapDao;
        worker.networkACLItemDao = networkACLItemDao;
        worker.vmInstanceDao = vmInstanceDao;
        worker.networkModel = networkModel;
        validator = new KubernetesClusterNetworkRuleOwnershipValidator(worker);
        Mockito.when(cluster.getId()).thenReturn(1L);
        Mockito.when(cluster.getName()).thenReturn("cluster");
    }

    @Test
    public void directNetworkHasNoManagedRuleTopology() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);

        Assert.assertTrue(validator.getExpectedLogicalRoles(network, List.of()).isEmpty());
    }

    @Test
    public void nonConserveVpcUsesClusterScopedApiPortForwardingRole() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        NetworkOfferingVO offering = Mockito.mock(NetworkOfferingVO.class);
        KubernetesClusterVmMapVO control = new KubernetesClusterVmMapVO(1L, 20L, true);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(network.getNetworkACLId()).thenReturn(NetworkACL.DEFAULT_ALLOW);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(30L);
        Mockito.when(networkOfferingDao.findById(30L)).thenReturn(offering);
        Mockito.when(offering.isConserveMode()).thenReturn(false);

        Set<String> roles = validator.getExpectedLogicalRoles(network, List.of(control));

        Assert.assertTrue(roles.contains("API_PORT_FORWARD"));
        Assert.assertFalse(roles.contains("API_PORT_FORWARD:20"));
        Assert.assertTrue(roles.contains("SSH_PORT_FORWARD:20"));
    }

    @Test
    public void isolatedTopologyIncludesEveryNodeClassWithoutDuplicateRoles() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        KubernetesClusterVmMapVO control = new KubernetesClusterVmMapVO(1L, 20L, true);
        KubernetesClusterVmMapVO workerNode = new KubernetesClusterVmMapVO(1L, 21L, false);
        KubernetesClusterVmMapVO etcd = new KubernetesClusterVmMapVO(1L, 30L, false);
        etcd.setEtcdNode(true);
        KubernetesClusterVmMapVO external = new KubernetesClusterVmMapVO(1L, 40L, false);
        external.setExternalNode(true);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(null);

        Set<String> roles = validator.getExpectedLogicalRoles(network, List.of(control, workerNode, etcd, external));

        Assert.assertEquals(Set.of("API_FIREWALL", "SSH_FIREWALL", "EXTERNAL_SSH_FIREWALL:40",
                "ETCD_SSH_FIREWALL:30", "API_LOAD_BALANCER", "SSH_PORT_FORWARD:20",
                "SSH_PORT_FORWARD:21", "SSH_PORT_FORWARD:30", "SSH_PORT_FORWARD:40"), roles);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void defaultDenyVpcCannotBeAdoptedOrReconciled() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        KubernetesClusterVmMapVO control = new KubernetesClusterVmMapVO(1L, 20L, true);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(network.getNetworkACLId()).thenReturn(NetworkACL.DEFAULT_DENY);

        validator.getExpectedLogicalRoles(network, List.of(control));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void nullAclVpcCannotBeAdoptedOrReconciled() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        KubernetesClusterVmMapVO control = new KubernetesClusterVmMapVO(1L, 20L, true);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(network.getNetworkACLId()).thenReturn(null);

        validator.getExpectedLogicalRoles(network, List.of(control));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void sharedVpcAclCannotBeAdoptedOrReconciled() {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        NetworkVO otherTier = Mockito.mock(NetworkVO.class);
        NetworkACLVO acl = Mockito.mock(NetworkACLVO.class);
        KubernetesClusterVmMapVO control = new KubernetesClusterVmMapVO(1L, 20L, true);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(network.getNetworkACLId()).thenReturn(50L);
        Mockito.when(networkACLDao.findById(50L)).thenReturn(acl);
        Mockito.when(acl.getVpcId()).thenReturn(10L);
        Mockito.when(otherTier.getId()).thenReturn(3L);
        Mockito.when(networkDao.listByAclId(50L)).thenReturn(List.of(network, otherTier));

        validator.getExpectedLogicalRoles(network, List.of(control));
    }

    @Test
    public void emptyDirectManifestTransitionsLegacyClusterToManaged() {
        KubernetesClusterVO locked = Mockito.mock(KubernetesClusterVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(clusterDao.lockRow(1L, true)).thenReturn(locked);
        Mockito.when(locked.getId()).thenReturn(1L);
        Mockito.when(locked.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(locked.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(locked.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);
        Mockito.when(locked.getNetworkId()).thenReturn(2L);
        Mockito.when(networkDao.findById(2L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.when(vmMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(firewallRuleMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(aclItemMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(clusterDao.update(1L, locked)).thenReturn(true);

        Assert.assertTrue(validator.adopt(List.of()));

        Mockito.verify(locked).setNetworkRuleOwnershipState(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        Mockito.verify(clusterDao).update(1L, locked);
        Mockito.verifyNoInteractions(networkOfferingDao, networkACLDao);
    }

    @Test
    public void adoptionPersistsExactIsolatedManifestAcrossFirewallPortForwardingAndLoadBalancerResources()
            throws ManagementServerException {
        LegacySingleControlTopology topology = mockLegacySingleControlTopology(null, null, false);
        FirewallRuleVO apiFirewall = mockFirewallRule(101L, "api-firewall",
                KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT);
        FirewallRuleVO sshFirewall = mockFirewallRule(102L, "ssh-firewall",
                KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT,
                KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT);
        LoadBalancerVO loadBalancer = mockApiLoadBalancer(103L, "api-load-balancer");
        Long controlVmId = topology.vm.getId();
        String controlVmUuid = topology.vm.getUuid();
        String controlIp = topology.nic.getIPv4Address();
        PortForwardingRuleVO portForwardingRule = mockSshPortForwardingRule(104L, "control-ssh",
                KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT, controlVmId, controlIp);
        LoadBalancerVMMapVO backend = Mockito.mock(LoadBalancerVMMapVO.class);
        Mockito.when(backend.getInstanceId()).thenReturn(controlVmId);
        Mockito.when(backend.getInstanceIp()).thenReturn(controlIp);
        mockFirewallResource(apiFirewall);
        mockFirewallResource(sshFirewall);
        mockLoadBalancerResource(loadBalancer);
        Mockito.when(portForwardingRulesDao.findByUuid(portForwardingRule.getUuid())).thenReturn(portForwardingRule);
        Mockito.when(firewallRulesDao.lockRow(portForwardingRule.getId(), true)).thenReturn(portForwardingRule);
        Mockito.when(portForwardingRulesDao.findById(portForwardingRule.getId())).thenReturn(portForwardingRule);
        Mockito.when(loadBalancerVMMapDao.listByLoadBalancerId(loadBalancer.getId(), false)).thenReturn(List.of(backend));
        Mockito.when(firewallRuleMapDao.persist(Mockito.any(KubernetesClusterFirewallRuleMapVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(clusterDao.update(1L, topology.cluster)).thenReturn(true);

        List<KubernetesClusterNetworkRuleAdoptionSpec> specs = List.of(
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.FIREWALL,
                        KubernetesClusterNetworkRuleRole.API_FIREWALL, apiFirewall.getUuid(), null),
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.FIREWALL,
                        KubernetesClusterNetworkRuleRole.SSH_FIREWALL, sshFirewall.getUuid(), null),
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.LOAD_BALANCER,
                        KubernetesClusterNetworkRuleRole.API_LOAD_BALANCER, loadBalancer.getUuid(), null),
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                        KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, portForwardingRule.getUuid(),
                        controlVmUuid));

        Assert.assertTrue(validator.adopt(specs));

        verifyPersistedFirewallOwnership(apiFirewall.getId(), "API_FIREWALL");
        verifyPersistedFirewallOwnership(sshFirewall.getId(), "SSH_FIREWALL");
        verifyPersistedFirewallOwnership(loadBalancer.getId(), "API_LOAD_BALANCER");
        verifyPersistedFirewallOwnership(portForwardingRule.getId(), "SSH_PORT_FORWARD:20");
        Mockito.verify(topology.cluster).setNetworkRuleOwnershipState(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
    }

    @Test
    public void adoptionPersistsExactDedicatedVpcAclManifest() throws ManagementServerException {
        LegacySingleControlTopology topology = mockLegacySingleControlTopology(10L, 50L, false);
        NetworkACLVO acl = Mockito.mock(NetworkACLVO.class);
        Mockito.when(acl.getVpcId()).thenReturn(10L);
        Mockito.when(networkACLDao.findById(50L)).thenReturn(acl);
        Mockito.when(networkDao.listByAclId(50L)).thenReturn(List.of(topology.network));
        NetworkACLItemVO apiAcl = mockNetworkAclItem(201L, "api-acl",
                KubernetesClusterActionWorker.CLUSTER_API_PORT);
        NetworkACLItemVO sshAcl = mockNetworkAclItem(202L, "ssh-acl",
                KubernetesClusterActionWorker.DEFAULT_SSH_PORT);
        mockNetworkAclItemResource(apiAcl);
        mockNetworkAclItemResource(sshAcl);
        Mockito.when(portForwardingRulesDao.listByIpAndNotRevoked(100L)).thenReturn(List.of());
        Mockito.when(aclItemMapDao.persist(Mockito.any(KubernetesClusterNetworkACLItemMapVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(clusterDao.update(1L, topology.cluster)).thenReturn(true);

        List<KubernetesClusterNetworkRuleAdoptionSpec> specs = List.of(
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.NETWORK_ACL_ITEM,
                        KubernetesClusterNetworkRuleRole.API_ACL, apiAcl.getUuid(), null),
                new KubernetesClusterNetworkRuleAdoptionSpec(
                        KubernetesClusterNetworkRuleRole.ResourceType.NETWORK_ACL_ITEM,
                        KubernetesClusterNetworkRuleRole.SSH_ACL, sshAcl.getUuid(), null));

        Assert.assertTrue(validator.adopt(specs));

        verifyPersistedAclOwnership(apiAcl.getId(), "API_ACL");
        verifyPersistedAclOwnership(sshAcl.getId(), "SSH_ACL");
        Mockito.verify(topology.cluster).setNetworkRuleOwnershipState(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void adoptionRejectsMissingVmMappings() {
        KubernetesClusterVO locked = mockLegacyClusterForVmValidation(List.of());
        Mockito.when(locked.getTotalNodeCount()).thenReturn(2L);

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void adoptionRejectsMisclassifiedVmMappings() {
        KubernetesClusterVmMapVO firstControl = new KubernetesClusterVmMapVO(1L, 20L, true);
        KubernetesClusterVmMapVO secondControl = new KubernetesClusterVmMapVO(1L, 21L, true);
        KubernetesClusterVO locked = mockLegacyClusterForVmValidation(List.of(firstControl, secondControl));
        Mockito.when(locked.getTotalNodeCount()).thenReturn(2L);
        Mockito.when(locked.getControlNodeCount()).thenReturn(1L);
        mockOwnedVm(20L);
        mockOwnedVm(21L);

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void adoptionRejectsOverlappingVmRoles() {
        KubernetesClusterVmMapVO conflicting = new KubernetesClusterVmMapVO(1L, 20L, true);
        conflicting.setEtcdNode(true);
        KubernetesClusterVO locked = mockLegacyClusterForVmValidation(List.of(conflicting));
        Mockito.when(locked.getTotalNodeCount()).thenReturn(1L);

        validator.adopt(List.of());
    }

    @Test
    public void adoptionUsesPersistedEtcdSshPortWhenConfigurationChanged() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(true, 51000);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(topology.rule.getId(), true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(topology.rule.getId())).thenReturn(topology.rule);
        Mockito.when(firewallRuleMapDao.persist(Mockito.any(KubernetesClusterFirewallRuleMapVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(clusterDao.update(1L, topology.cluster)).thenReturn(true);

        Assert.assertTrue(validator.adopt(List.of(spec)));

        Mockito.verify(firewallRuleMapDao).persist(Mockito.argThat(mapping ->
                mapping.getFirewallRuleId() == topology.rule.getId()
                        && "SSH_PORT_FORWARD:30".equals(mapping.getLogicalRole())));
        Mockito.verify(topology.cluster).setNetworkRuleOwnershipState(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void omittedEtcdSshRuleIsDetectedEvenWhenConfigurationChanged() throws ManagementServerException {
        mockLegacyDynamicSshVpcTopology(true, 51000);

        validator.adopt(List.of());
    }

    @Test
    public void adoptionUsesPersistedExternalSshPortAfterPortHistoryDevelopsGap() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(topology.rule.getId(), true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(topology.rule.getId())).thenReturn(topology.rule);
        Mockito.when(firewallRuleMapDao.persist(Mockito.any(KubernetesClusterFirewallRuleMapVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.when(clusterDao.update(1L, topology.cluster)).thenReturn(true);

        Assert.assertTrue(validator.adopt(List.of(spec)));

        Mockito.verify(firewallRuleMapDao).persist(Mockito.argThat(mapping ->
                mapping.getFirewallRuleId() == topology.rule.getId()
                        && "SSH_PORT_FORWARD:30".equals(mapping.getLogicalRole())));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void adoptionRejectsResourceOwnedByAnotherCluster() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        KubernetesClusterFirewallRuleMapVO ownership = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(topology.rule.getId(), true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(topology.rule.getId())).thenReturn(topology.rule);
        Mockito.when(ownership.getClusterId()).thenReturn(2L);
        Mockito.when(ownership.getLogicalRole()).thenReturn("SSH_PORT_FORWARD:30");
        Mockito.when(firewallRuleMapDao.findByFirewallRuleId(topology.rule.getId())).thenReturn(ownership);

        validator.adopt(List.of(spec));
    }

    @Test
    public void managedAdoptionAcceptsOnlyExactIdempotentReplay() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        KubernetesClusterFirewallRuleMapVO ownership = Mockito.mock(KubernetesClusterFirewallRuleMapVO.class);
        Long ruleId = topology.rule.getId();
        Mockito.when(topology.cluster.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(ruleId, true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(ruleId)).thenReturn(topology.rule);
        Mockito.when(ownership.getClusterId()).thenReturn(1L);
        Mockito.when(ownership.getLogicalRole()).thenReturn("SSH_PORT_FORWARD:30");
        Mockito.when(ownership.getFirewallRuleId()).thenReturn(ruleId);
        Mockito.when(ownership.getLifecycleState()).thenReturn(KubernetesClusterNetworkRuleLifecycleState.ACTIVE);
        Mockito.when(firewallRuleMapDao.findByFirewallRuleId(ruleId)).thenReturn(ownership);
        Mockito.when(firewallRuleMapDao.findByClusterIdAndLogicalRole(1L, "SSH_PORT_FORWARD:30")).thenReturn(ownership);
        Mockito.when(firewallRuleMapDao.listByClusterId(1L)).thenReturn(List.of(ownership));

        Assert.assertTrue(validator.adopt(List.of(spec)));

        Mockito.verify(firewallRuleMapDao, Mockito.never()).persist(Mockito.any());
        Mockito.verify(clusterDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void managedAdoptionRejectsNonExactReplay() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        Long ruleId = topology.rule.getId();
        Mockito.when(topology.cluster.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(ruleId, true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(ruleId)).thenReturn(topology.rule);

        try {
            validator.adopt(List.of(spec));
        } finally {
            Mockito.verify(firewallRuleMapDao, Mockito.never()).persist(Mockito.any());
            Mockito.verify(aclItemMapDao, Mockito.never()).persist(Mockito.any());
            Mockito.verify(clusterDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void adoptionDoesNotUpdateClusterWhenOwnershipPersistenceFails() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(
                KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING,
                KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, topology.rule.getUuid(), topology.vm.getUuid());
        Mockito.when(portForwardingRulesDao.findByUuid(topology.rule.getUuid())).thenReturn(topology.rule);
        Mockito.when(firewallRulesDao.lockRow(topology.rule.getId(), true)).thenReturn(topology.rule);
        Mockito.when(portForwardingRulesDao.findById(topology.rule.getId())).thenReturn(topology.rule);
        Mockito.when(firewallRuleMapDao.persist(Mockito.any(KubernetesClusterFirewallRuleMapVO.class))).thenReturn(null);

        try {
            validator.adopt(List.of(spec));
        } finally {
            Mockito.verify(clusterDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void omittedExternalSshRuleIsDetectedAfterPortHistoryDevelopsGap() throws ManagementServerException {
        mockLegacyDynamicSshVpcTopology(false, 2226);

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void multipleExternalSshRulesTargetingSameVmAreRejected() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        PortForwardingRuleVO duplicate = mockSshPortForwardingRule(201L, "external-rule-duplicate", 2227);
        Mockito.when(portForwardingRulesDao.listByIpAndNotRevoked(100L)).thenReturn(List.of(topology.rule, duplicate));

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void stagedExternalSshRuleCannotBeOmittedFromAdoption() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        Mockito.when(topology.rule.getState()).thenReturn(FirewallRule.State.Add);

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void staleDestinationExternalSshRuleCannotBeOmittedFromAdoption() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        Mockito.when(topology.rule.getDestinationIpAddress()).thenReturn(new Ip("10.0.0.99"));
        Assert.assertEquals("10.0.0.99", topology.rule.getDestinationIpAddress().addr());

        validator.adopt(List.of());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void activeAndStagedExternalSshRulesTargetingSameVmAreRejected() throws ManagementServerException {
        LegacyDynamicSshTopology topology = mockLegacyDynamicSshVpcTopology(false, 2226);
        PortForwardingRuleVO stagedDuplicate = mockSshPortForwardingRule(201L, "external-rule-staged", 2227);
        Mockito.when(stagedDuplicate.getState()).thenReturn(FirewallRule.State.Add);
        Mockito.when(portForwardingRulesDao.listByIpAndNotRevoked(100L)).thenReturn(List.of(topology.rule, stagedDuplicate));

        validator.adopt(List.of());
    }

    private LegacySingleControlTopology mockLegacySingleControlTopology(Long vpcId, Long networkAclId,
            boolean conserveMode) throws ManagementServerException {
        KubernetesClusterVO locked = Mockito.mock(KubernetesClusterVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        KubernetesClusterVmMapVO controlMap = new KubernetesClusterVmMapVO(1L, 20L, true);
        VMInstanceVO controlVm = Mockito.mock(VMInstanceVO.class);
        Nic controlNic = Mockito.mock(Nic.class);

        Mockito.when(clusterDao.lockRow(1L, true)).thenReturn(locked);
        Mockito.when(locked.getId()).thenReturn(1L);
        Mockito.when(locked.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(locked.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(locked.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);
        Mockito.when(locked.getNetworkId()).thenReturn(2L);
        Mockito.when(locked.getAccountId()).thenReturn(3L);
        Mockito.when(locked.getDomainId()).thenReturn(4L);
        Mockito.when(locked.getControlNodeCount()).thenReturn(1L);
        Mockito.when(locked.getNodeCount()).thenReturn(0L);
        Mockito.when(locked.getEtcdNodeCount()).thenReturn(0L);
        Mockito.when(locked.getTotalNodeCount()).thenReturn(1L);
        Mockito.when(cluster.getAccountId()).thenReturn(3L);
        Mockito.when(cluster.getDomainId()).thenReturn(4L);
        Mockito.when(networkDao.findById(2L)).thenReturn(network);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(network.getVpcId()).thenReturn(vpcId);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        if (vpcId != null) {
            NetworkOfferingVO offering = Mockito.mock(NetworkOfferingVO.class);
            Mockito.when(network.getNetworkACLId()).thenReturn(networkAclId);
            Mockito.when(network.getNetworkOfferingId()).thenReturn(40L);
            Mockito.when(networkOfferingDao.findById(40L)).thenReturn(offering);
            Mockito.when(offering.isConserveMode()).thenReturn(conserveMode);
        }
        Mockito.when(vmMapDao.listByClusterId(1L)).thenReturn(List.of(controlMap));
        Mockito.when(controlVm.getAccountId()).thenReturn(3L);
        Mockito.when(controlVm.getDomainId()).thenReturn(4L);
        Mockito.when(controlVm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vmInstanceDao.findById(20L)).thenReturn(controlVm);
        if (vpcId == null) {
            Mockito.when(controlVm.getId()).thenReturn(20L);
            Mockito.when(controlVm.getUuid()).thenReturn("control-vm");
            Mockito.when(vmInstanceDao.findByUuid("control-vm")).thenReturn(controlVm);
            Mockito.when(controlNic.getIPv4Address()).thenReturn("10.0.0.20");
            Mockito.when(networkModel.getNicInNetwork(20L, 2L)).thenReturn(controlNic);
        }
        Mockito.doReturn(publicIp).when(worker).getPublicIp(network);
        Mockito.when(publicIp.getId()).thenReturn(100L);
        Mockito.when(publicIp.getAllocatedToAccountId()).thenReturn(3L);
        Mockito.when(publicIp.getAllocatedInDomainId()).thenReturn(4L);
        Mockito.when(publicIp.getAssociatedWithNetworkId()).thenReturn(2L);
        Mockito.when(firewallRuleMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(aclItemMapDao.listByClusterId(1L)).thenReturn(List.of());
        return new LegacySingleControlTopology(locked, network, controlVm, controlNic);
    }

    private FirewallRuleVO mockFirewallRule(long id, String uuid, int startPort, int endPort) {
        FirewallRuleVO rule = Mockito.mock(FirewallRuleVO.class);
        Mockito.when(rule.getId()).thenReturn(id);
        Mockito.when(rule.getUuid()).thenReturn(uuid);
        Mockito.when(rule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(rule.getPurpose()).thenReturn(FirewallRule.Purpose.Firewall);
        Mockito.when(rule.getSourceIpAddressId()).thenReturn(100L);
        Mockito.when(rule.getNetworkId()).thenReturn(2L);
        Mockito.when(rule.getAccountId()).thenReturn(3L);
        Mockito.when(rule.getDomainId()).thenReturn(4L);
        Mockito.when(rule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(rule.getSourcePortStart()).thenReturn(startPort);
        Mockito.when(rule.getSourcePortEnd()).thenReturn(endPort);
        Mockito.when(rule.getTrafficType()).thenReturn(FirewallRule.TrafficType.Ingress);
        Mockito.when(rule.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS));
        return rule;
    }

    private LoadBalancerVO mockApiLoadBalancer(long id, String uuid) {
        LoadBalancerVO rule = Mockito.mock(LoadBalancerVO.class);
        Mockito.when(rule.getId()).thenReturn(id);
        Mockito.when(rule.getUuid()).thenReturn(uuid);
        Mockito.when(rule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(rule.getPurpose()).thenReturn(FirewallRule.Purpose.LoadBalancing);
        Mockito.when(rule.getSourceIpAddressId()).thenReturn(100L);
        Mockito.when(rule.getNetworkId()).thenReturn(2L);
        Mockito.when(rule.getAccountId()).thenReturn(3L);
        Mockito.when(rule.getDomainId()).thenReturn(4L);
        Mockito.when(rule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(rule.getSourcePortStart()).thenReturn(KubernetesClusterActionWorker.CLUSTER_API_PORT);
        Mockito.when(rule.getSourcePortEnd()).thenReturn(KubernetesClusterActionWorker.CLUSTER_API_PORT);
        Mockito.when(rule.getDefaultPortStart()).thenReturn(KubernetesClusterActionWorker.CLUSTER_API_PORT);
        Mockito.when(rule.getDefaultPortEnd()).thenReturn(KubernetesClusterActionWorker.CLUSTER_API_PORT);
        Mockito.when(rule.getName()).thenReturn("api-lb");
        Mockito.when(rule.getAlgorithm()).thenReturn("roundrobin");
        Mockito.when(rule.getLbProtocol()).thenReturn(NetUtils.TCP_PROTO);
        return rule;
    }

    private void mockFirewallResource(FirewallRuleVO rule) {
        Mockito.when(firewallRulesDao.findByUuid(rule.getUuid())).thenReturn(rule);
        Mockito.when(firewallRulesDao.lockRow(rule.getId(), true)).thenReturn(rule);
        Mockito.when(firewallRulesDao.findById(rule.getId())).thenReturn(rule);
    }

    private void mockLoadBalancerResource(LoadBalancerVO rule) {
        Mockito.when(loadBalancerDao.findByUuid(rule.getUuid())).thenReturn(rule);
        Mockito.when(firewallRulesDao.lockRow(rule.getId(), true)).thenReturn(rule);
        Mockito.when(loadBalancerDao.findById(rule.getId())).thenReturn(rule);
    }

    private NetworkACLItemVO mockNetworkAclItem(long id, String uuid, int port) {
        NetworkACLItemVO item = Mockito.mock(NetworkACLItemVO.class);
        Mockito.when(item.getId()).thenReturn(id);
        Mockito.when(item.getUuid()).thenReturn(uuid);
        Mockito.when(item.getAclId()).thenReturn(50L);
        Mockito.when(item.getState()).thenReturn(NetworkACLItem.State.Active);
        Mockito.when(item.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(item.getSourcePortStart()).thenReturn(port);
        Mockito.when(item.getSourcePortEnd()).thenReturn(port);
        Mockito.when(item.getTrafficType()).thenReturn(NetworkACLItem.TrafficType.Ingress);
        Mockito.when(item.getAction()).thenReturn(NetworkACLItem.Action.Allow);
        Mockito.when(item.getSourceCidrList()).thenReturn(List.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS));
        return item;
    }

    private void mockNetworkAclItemResource(NetworkACLItemVO item) {
        Mockito.when(networkACLItemDao.findByUuid(item.getUuid())).thenReturn(item);
        Mockito.when(networkACLItemDao.lockRow(item.getId(), true)).thenReturn(item);
        Mockito.when(networkACLItemDao.findById(item.getId())).thenReturn(item);
    }

    private void verifyPersistedFirewallOwnership(long resourceId, String logicalRole) {
        Mockito.verify(firewallRuleMapDao).persist(Mockito.argThat(mapping ->
                mapping.getFirewallRuleId() == resourceId
                        && logicalRole.equals(mapping.getLogicalRole())
                        && KubernetesClusterNetworkRuleLifecycleState.ACTIVE.equals(mapping.getLifecycleState())));
    }

    private void verifyPersistedAclOwnership(long resourceId, String logicalRole) {
        Mockito.verify(aclItemMapDao).persist(Mockito.argThat(mapping ->
                mapping.getNetworkAclItemId() == resourceId
                        && logicalRole.equals(mapping.getLogicalRole())
                        && KubernetesClusterNetworkRuleLifecycleState.ACTIVE.equals(mapping.getLifecycleState())));
    }

    private LegacyDynamicSshTopology mockLegacyDynamicSshVpcTopology(boolean etcdNode, int sourcePort)
            throws ManagementServerException {
        KubernetesClusterVO locked = Mockito.mock(KubernetesClusterVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        NetworkOfferingVO offering = Mockito.mock(NetworkOfferingVO.class);
        KubernetesClusterVmMapVO controlMap = new KubernetesClusterVmMapVO(1L, 20L, true);
        KubernetesClusterVmMapVO dynamicMap = new KubernetesClusterVmMapVO(1L, 30L, false);
        dynamicMap.setEtcdNode(etcdNode);
        dynamicMap.setExternalNode(!etcdNode);
        VMInstanceVO controlVm = Mockito.mock(VMInstanceVO.class);
        VMInstanceVO dynamicVm = Mockito.mock(VMInstanceVO.class);
        PortForwardingRuleVO dynamicRule = mockSshPortForwardingRule(200L, "dynamic-rule", sourcePort);
        Nic dynamicNic = Mockito.mock(Nic.class);

        Mockito.when(clusterDao.lockRow(1L, true)).thenReturn(locked);
        Mockito.when(locked.getId()).thenReturn(1L);
        Mockito.when(locked.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(locked.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(locked.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);
        Mockito.when(locked.getNetworkId()).thenReturn(2L);
        Mockito.when(locked.getAccountId()).thenReturn(3L);
        Mockito.when(locked.getDomainId()).thenReturn(4L);
        Mockito.when(locked.getControlNodeCount()).thenReturn(1L);
        Mockito.when(locked.getNodeCount()).thenReturn(etcdNode ? 0L : 1L);
        Mockito.when(locked.getEtcdNodeCount()).thenReturn(etcdNode ? 1L : 0L);
        Mockito.when(locked.getTotalNodeCount()).thenReturn(2L);
        Mockito.when(cluster.getAccountId()).thenReturn(3L);
        Mockito.when(cluster.getDomainId()).thenReturn(4L);
        Mockito.when(networkDao.findById(2L)).thenReturn(network);
        Mockito.when(network.getId()).thenReturn(2L);
        Mockito.when(network.getVpcId()).thenReturn(10L);
        Mockito.when(network.getNetworkACLId()).thenReturn(NetworkACL.DEFAULT_ALLOW);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(40L);
        Mockito.when(networkOfferingDao.findById(40L)).thenReturn(offering);
        Mockito.when(offering.isConserveMode()).thenReturn(false);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(vmMapDao.listByClusterId(1L)).thenReturn(List.of(controlMap, dynamicMap));

        Mockito.when(controlVm.getAccountId()).thenReturn(3L);
        Mockito.when(controlVm.getDomainId()).thenReturn(4L);
        Mockito.when(controlVm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(dynamicVm.getId()).thenReturn(30L);
        Mockito.when(dynamicVm.getUuid()).thenReturn("dynamic-vm");
        Mockito.when(dynamicVm.getAccountId()).thenReturn(3L);
        Mockito.when(dynamicVm.getDomainId()).thenReturn(4L);
        Mockito.when(dynamicVm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vmInstanceDao.findById(20L)).thenReturn(controlVm);
        Mockito.when(vmInstanceDao.findById(30L)).thenReturn(dynamicVm);
        Mockito.when(vmInstanceDao.findByUuid("dynamic-vm")).thenReturn(dynamicVm);

        Mockito.doReturn(publicIp).when(worker).getPublicIp(network);
        Mockito.when(publicIp.getId()).thenReturn(100L);
        Mockito.when(publicIp.getAllocatedToAccountId()).thenReturn(3L);
        Mockito.when(publicIp.getAllocatedInDomainId()).thenReturn(4L);
        Mockito.when(publicIp.getAssociatedWithNetworkId()).thenReturn(2L);

        Mockito.when(portForwardingRulesDao.listByIpAndNotRevoked(100L)).thenReturn(List.of(dynamicRule));
        Mockito.when(dynamicNic.getIPv4Address()).thenReturn("10.0.0.30");
        Mockito.when(networkModel.getNicInNetwork(30L, 2L)).thenReturn(dynamicNic);
        Mockito.when(firewallRuleMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(aclItemMapDao.listByClusterId(1L)).thenReturn(List.of());

        return new LegacyDynamicSshTopology(locked, dynamicVm, dynamicRule);
    }

    private KubernetesClusterVO mockLegacyClusterForVmValidation(List<KubernetesClusterVmMapVO> vmMaps) {
        KubernetesClusterVO locked = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(clusterDao.lockRow(1L, true)).thenReturn(locked);
        Mockito.when(locked.getId()).thenReturn(1L);
        Mockito.when(locked.getName()).thenReturn("cluster");
        Mockito.when(locked.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(locked.getState()).thenReturn(KubernetesCluster.State.Running);
        Mockito.when(locked.getNetworkRuleOwnershipState()).thenReturn(
                KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);
        Mockito.when(locked.getNetworkId()).thenReturn(2L);
        Mockito.when(locked.getAccountId()).thenReturn(3L);
        Mockito.when(locked.getDomainId()).thenReturn(4L);
        Mockito.when(networkDao.findById(2L)).thenReturn(Mockito.mock(NetworkVO.class));
        Mockito.when(vmMapDao.listByClusterId(1L)).thenReturn(vmMaps);
        return locked;
    }

    private void mockOwnedVm(long vmId) {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getAccountId()).thenReturn(3L);
        Mockito.when(vm.getDomainId()).thenReturn(4L);
        Mockito.when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        Mockito.when(vmInstanceDao.findById(vmId)).thenReturn(vm);
    }

    private PortForwardingRuleVO mockSshPortForwardingRule(long id, String uuid, int sourcePort) {
        return mockSshPortForwardingRule(id, uuid, sourcePort, 30L, "10.0.0.30");
    }

    private PortForwardingRuleVO mockSshPortForwardingRule(long id, String uuid, int sourcePort,
            long vmId, String destinationIp) {
        PortForwardingRuleVO rule = Mockito.mock(PortForwardingRuleVO.class);
        Mockito.when(rule.getId()).thenReturn(id);
        Mockito.when(rule.getUuid()).thenReturn(uuid);
        Mockito.when(rule.getState()).thenReturn(FirewallRule.State.Active);
        Mockito.when(rule.getPurpose()).thenReturn(FirewallRule.Purpose.PortForwarding);
        Mockito.when(rule.getSourceIpAddressId()).thenReturn(100L);
        Mockito.when(rule.getNetworkId()).thenReturn(2L);
        Mockito.when(rule.getAccountId()).thenReturn(3L);
        Mockito.when(rule.getDomainId()).thenReturn(4L);
        Mockito.when(rule.getProtocol()).thenReturn(NetUtils.TCP_PROTO);
        Mockito.when(rule.getSourcePortStart()).thenReturn(sourcePort);
        Mockito.when(rule.getSourcePortEnd()).thenReturn(sourcePort);
        Mockito.when(rule.getDestinationPortStart()).thenReturn(22);
        Mockito.when(rule.getDestinationPortEnd()).thenReturn(22);
        Mockito.when(rule.getVirtualMachineId()).thenReturn(vmId);
        Mockito.when(rule.getDestinationIpAddress()).thenReturn(new Ip(destinationIp));
        return rule;
    }

    private static final class LegacySingleControlTopology {
        private final KubernetesClusterVO cluster;
        private final NetworkVO network;
        private final VMInstanceVO vm;
        private final Nic nic;

        private LegacySingleControlTopology(KubernetesClusterVO cluster, NetworkVO network, VMInstanceVO vm,
                Nic nic) {
            this.cluster = cluster;
            this.network = network;
            this.vm = vm;
            this.nic = nic;
        }
    }

    private static final class LegacyDynamicSshTopology {
        private final KubernetesClusterVO cluster;
        private final VMInstanceVO vm;
        private final PortForwardingRuleVO rule;

        private LegacyDynamicSshTopology(KubernetesClusterVO cluster, VMInstanceVO vm,
                PortForwardingRuleVO rule) {
            this.cluster = cluster;
            this.vm = vm;
            this.rule = rule;
        }
    }
}
