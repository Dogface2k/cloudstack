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

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.vm.UserVmVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.dao.UserVmDao;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterStartWorkerTest {

    @Mock
    private KubernetesClusterManagerImpl manager;
    @Mock
    private KubernetesCluster cluster;
    @Mock
    private KubernetesClusterDao clusterDao;
    @Mock
    private KubernetesClusterDetailsDao clusterDetailsDao;
    @Mock
    private KubernetesClusterVmMapDao clusterVmMapDao;
    @Mock
    private KubernetesSupportedVersionDao supportedVersionDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private NetworkOfferingDao networkOfferingDao;

    private KubernetesClusterStartWorker worker;

    @Before
    public void setUp() {
        manager.kubernetesClusterDao = clusterDao;
        manager.kubernetesClusterDetailsDao = clusterDetailsDao;
        manager.kubernetesClusterVmMapDao = clusterVmMapDao;
        manager.kubernetesSupportedVersionDao = supportedVersionDao;
        worker = Mockito.spy(new KubernetesClusterStartWorker(cluster, manager));
        worker.accountDao = accountDao;
        worker.networkDao = networkDao;
        worker.userVmDao = userVmDao;
        worker.networkOfferingDao = networkOfferingDao;
        Mockito.when(cluster.getAccountId()).thenReturn(2L);
        Mockito.when(cluster.getNetworkId()).thenReturn(3L);
    }

    @Test
    public void reconcileDirectAccessNetworkIsANoOp() throws Exception {
        AccountVO owner = Mockito.mock(AccountVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(accountDao.findById(2L)).thenReturn(owner);
        Mockito.when(networkDao.findById(3L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);
        Mockito.doNothing().when(worker).deleteManagedNetworkRulesNotIn(Mockito.anySet(), Mockito.eq(network));

        Assert.assertTrue(worker.reconcileKubernetesClusterNetworkRules());

        Mockito.verify(worker, Mockito.never()).getKubernetesClusterVMMaps();
        Mockito.verify(worker).deleteManagedNetworkRulesNotIn(Mockito.eq(java.util.Collections.emptySet()), Mockito.eq(network));
    }

    @Test
    public void reconcileVpcTierDelegatesToVpcRuleProvisioning() throws Exception {
        AccountVO owner = Mockito.mock(AccountVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        UserVmVO controlVm = Mockito.mock(UserVmVO.class);
        KubernetesClusterVmMapVO controlMap = new KubernetesClusterVmMapVO(1L, 12L, true);
        Mockito.when(accountDao.findById(2L)).thenReturn(owner);
        Mockito.when(networkDao.findById(3L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(4L);
        Mockito.when(network.getNetworkACLId()).thenReturn(com.cloud.network.vpc.NetworkACL.DEFAULT_ALLOW);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(5L);
        NetworkOfferingVO offering = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingDao.findById(5L)).thenReturn(offering);
        Mockito.doReturn(List.of(controlMap)).when(worker).getKubernetesClusterVMMaps();
        Mockito.when(userVmDao.findById(12L)).thenReturn(controlVm);
        Mockito.when(controlVm.getId()).thenReturn(12L);
        Mockito.doReturn(publicIp).when(worker).getPublicIp(network);
        Mockito.when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        Mockito.doNothing().when(worker).setupKubernetesClusterVpcTierRules(publicIp, network, List.of(12L));
        Mockito.doNothing().when(worker).deleteManagedNetworkRulesNotIn(Mockito.anySet(), Mockito.eq(network));

        Assert.assertTrue(worker.reconcileKubernetesClusterNetworkRules());

        org.mockito.InOrder ordering = Mockito.inOrder(worker);
        ordering.verify(worker).deleteManagedNetworkRulesNotIn(Mockito.anySet(), Mockito.eq(network));
        ordering.verify(worker).setupKubernetesClusterVpcTierRules(publicIp, network, List.of(12L));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void reconcileOrdersControlNodesBeforeWorkersAndHandlesEtcdSeparately() throws Exception {
        AccountVO owner = Mockito.mock(AccountVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        IpAddress publicIp = Mockito.mock(IpAddress.class);
        UserVmVO workerVm = Mockito.mock(UserVmVO.class);
        UserVmVO etcdVm = Mockito.mock(UserVmVO.class);
        UserVmVO controlVm = Mockito.mock(UserVmVO.class);
        KubernetesClusterVmMapVO workerMap = new KubernetesClusterVmMapVO(1L, 10L, false);
        KubernetesClusterVmMapVO etcdMap = new KubernetesClusterVmMapVO(1L, 11L, false);
        etcdMap.setEtcdNode(true);
        KubernetesClusterVmMapVO controlMap = new KubernetesClusterVmMapVO(1L, 12L, true);
        Mockito.when(accountDao.findById(2L)).thenReturn(owner);
        Mockito.when(networkDao.findById(3L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(null);
        Mockito.doReturn(List.of(workerMap, etcdMap, controlMap)).when(worker).getKubernetesClusterVMMaps();
        Mockito.when(userVmDao.findById(10L)).thenReturn(workerVm);
        Mockito.when(userVmDao.findById(11L)).thenReturn(etcdVm);
        Mockito.when(userVmDao.findById(12L)).thenReturn(controlVm);
        Mockito.doReturn(publicIp).when(worker).getPublicIp(network);
        Mockito.when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        Mockito.doNothing().when(worker).setupKubernetesClusterNetworkRules(Mockito.eq(network), Mockito.anyList(), Mockito.eq(publicIp));
        Mockito.doNothing().when(worker).setupKubernetesEtcdNetworkRules(Mockito.anyList(), Mockito.eq(network), Mockito.eq(publicIp));
        Mockito.doNothing().when(worker).deleteManagedNetworkRulesNotIn(Mockito.anySet(), Mockito.eq(network));

        Assert.assertTrue(worker.reconcileKubernetesClusterNetworkRules());

        ArgumentCaptor<List<UserVm>> clusterVms = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UserVm>> etcdVms = ArgumentCaptor.forClass(List.class);
        Mockito.verify(worker).setupKubernetesClusterNetworkRules(Mockito.eq(network), clusterVms.capture(), Mockito.eq(publicIp));
        Mockito.verify(worker).setupKubernetesEtcdNetworkRules(etcdVms.capture(), Mockito.eq(network), Mockito.eq(publicIp));
        Assert.assertEquals(List.of(controlVm, workerVm), clusterVms.getValue());
        Assert.assertEquals(List.of(etcdVm), etcdVms.getValue());
        ArgumentCaptor<Set<String>> desiredRoles = ArgumentCaptor.forClass(Set.class);
        Mockito.verify(worker).deleteManagedNetworkRulesNotIn(desiredRoles.capture(), Mockito.eq(network));
        Assert.assertEquals(Set.of("API_FIREWALL", "SSH_FIREWALL", "ETCD_SSH_FIREWALL:11", "API_LOAD_BALANCER",
                "SSH_PORT_FORWARD:10", "SSH_PORT_FORWARD:11", "SSH_PORT_FORWARD:12"), desiredRoles.getValue());
    }

    @Test
    public void managedClusterReconcilesRulesBeforeReadiness() {
        KubernetesClusterVO currentCluster = mockManagedCluster();
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);
        Mockito.doReturn(true).when(worker).reconcileKubernetesClusterNetworkRules();

        Assert.assertTrue(worker.reconcileManagedNetworkRulesBeforeReadiness());

        Mockito.verify(worker).reconcileKubernetesClusterNetworkRules();
    }

    @Test
    public void legacyClusterDoesNotClaimRulesDuringReadiness() {
        KubernetesClusterVO currentCluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(1L);
        Mockito.when(currentCluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(currentCluster.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);

        Assert.assertTrue(worker.reconcileManagedNetworkRulesBeforeReadiness());

        Mockito.verify(worker, Mockito.never()).reconcileKubernetesClusterNetworkRules();
    }

    @Test
    public void stoppedClusterStartsVmsThenReconcilesBeforeEndpointReadiness() {
        KubernetesClusterVO currentCluster = mockManagedCluster();
        Mockito.when(cluster.getName()).thenReturn("cluster");
        Mockito.when(cluster.getEndpoint()).thenReturn("not-a-url");
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);
        Mockito.doNothing().when(worker).init();
        Mockito.doReturn(true).when(worker).stateTransitTo(Mockito.anyLong(), Mockito.any());
        Mockito.doNothing().when(worker).startKubernetesClusterVMs(4L, 5L);
        Mockito.doReturn(true).when(worker).reconcileKubernetesClusterNetworkRules();
        Mockito.doThrow(new CloudRuntimeException("expected endpoint failure")).when(worker).logTransitStateAndThrow(
                Mockito.any(), Mockito.anyString(), Mockito.anyLong(), Mockito.any());

        try {
            worker.startStoppedKubernetesCluster(4L, 5L);
            Assert.fail("Expected endpoint validation to fail");
        } catch (CloudRuntimeException expected) {
            Assert.assertEquals("expected endpoint failure", expected.getMessage());
        }

        org.mockito.InOrder ordering = Mockito.inOrder(worker);
        ordering.verify(worker).startKubernetesClusterVMs(4L, 5L);
        ordering.verify(worker).reconcileKubernetesClusterNetworkRules();
    }

    @Test
    public void stoppedClusterDoesNotProbeReadinessWhenManagedRuleReconciliationFails() {
        KubernetesClusterVO currentCluster = mockManagedCluster();
        Mockito.when(cluster.getName()).thenReturn("cluster");
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);
        Mockito.doNothing().when(worker).init();
        Mockito.doReturn(true).when(worker).stateTransitTo(Mockito.anyLong(), Mockito.any());
        Mockito.doNothing().when(worker).startKubernetesClusterVMs(4L, 5L);
        Mockito.doReturn(false).when(worker).reconcileKubernetesClusterNetworkRules();
        Mockito.doThrow(new CloudRuntimeException("expected reconciliation failure")).when(worker).logTransitStateAndThrow(
                Mockito.any(), Mockito.anyString(), Mockito.anyLong(), Mockito.any(), Mockito.any());

        try {
            worker.startStoppedKubernetesCluster(4L, 5L);
            Assert.fail("Expected reconciliation failure");
        } catch (CloudRuntimeException expected) {
            Assert.assertEquals("expected reconciliation failure", expected.getMessage());
        }

        Mockito.verify(worker, Mockito.never()).getKubernetesClusterServerIpSshPort(Mockito.any());
    }

    @Test
    public void alertRecoveryReconcilesManagedRulesBeforeInspectingNodes() {
        KubernetesClusterVO currentCluster = mockManagedCluster();
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);
        Mockito.doNothing().when(worker).init();
        Mockito.doReturn(true).when(worker).reconcileKubernetesClusterNetworkRules();
        Mockito.doReturn(java.util.Collections.emptyList()).when(worker).getKubernetesClusterVMMaps();

        Assert.assertFalse(worker.reconcileAlertCluster());

        org.mockito.InOrder ordering = Mockito.inOrder(worker);
        ordering.verify(worker).reconcileKubernetesClusterNetworkRules();
        ordering.verify(worker).getKubernetesClusterVMMaps();
    }

    @Test
    public void alertRecoveryStopsBeforeNodeChecksWhenManagedRuleReconciliationFails() {
        KubernetesClusterVO currentCluster = mockManagedCluster();
        Mockito.when(clusterDao.findById(1L)).thenReturn(currentCluster);
        Mockito.doNothing().when(worker).init();
        Mockito.doReturn(false).when(worker).reconcileKubernetesClusterNetworkRules();

        Assert.assertFalse(worker.reconcileAlertCluster());

        Mockito.verify(worker, Mockito.never()).getKubernetesClusterVMMaps();
    }

    private KubernetesClusterVO mockManagedCluster() {
        KubernetesClusterVO currentCluster = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(1L);
        Mockito.when(currentCluster.getClusterType()).thenReturn(KubernetesCluster.ClusterType.CloudManaged);
        Mockito.when(currentCluster.getNetworkRuleOwnershipState()).thenReturn(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        return currentCluster;
    }
}
