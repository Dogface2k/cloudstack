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

import com.cloud.exception.ManagementServerException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterFirewallRuleMapDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterNetworkACLItemMapDao;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.RemoteAccessVpnVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.Site2SiteVpnGatewayVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.utils.net.Ip;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterDestroyWorkerTest {

    @Mock
    private KubernetesCluster cluster;
    @Mock
    private KubernetesClusterManagerImpl manager;
    @Mock
    private KubernetesClusterFirewallRuleMapDao firewallRuleMapDao;
    @Mock
    private KubernetesClusterNetworkACLItemMapDao aclItemMapDao;
    @Mock
    private KubernetesClusterDetailsDao clusterDetailsDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private NetworkVO network;
    @Mock
    private NetworkService networkService;
    @Mock
    private FirewallRulesDao firewallRulesDao;
    @Mock
    private RemoteAccessVpnDao remoteAccessVpnDao;
    @Mock
    private Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;
    private IPAddressVO publicIp;

    private KubernetesClusterDestroyWorker worker;

    @Before
    public void setUp() {
        manager.kubernetesClusterDetailsDao = clusterDetailsDao;
        worker = Mockito.spy(new KubernetesClusterDestroyWorker(cluster, manager));
        worker.kubernetesClusterFirewallRuleMapDao = firewallRuleMapDao;
        worker.kubernetesClusterNetworkACLItemMapDao = aclItemMapDao;
        worker.networkService = networkService;
        worker.firewallRulesDao = firewallRulesDao;
        worker.remoteAccessVpnDao = remoteAccessVpnDao;
        worker.site2SiteVpnGatewayDao = site2SiteVpnGatewayDao;
        worker.networkDao = networkDao;
        Mockito.when(cluster.getId()).thenReturn(1L);
        Mockito.when(cluster.getName()).thenReturn("cluster");
    }

    @Test
    public void managedClusterDeletesExactRulesForAnyNetworkType() throws ManagementServerException {
        Mockito.doNothing().when(worker).deleteAllManagedNetworkRules(network);

        worker.deleteManagedNetworkRulesIfPresent(network, KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(worker).deleteAllManagedNetworkRules(network);
    }

    @Test
    public void legacyClusterNeverDeletesUnownedRules() throws ManagementServerException {
        worker.deleteManagedNetworkRulesIfPresent(network, KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);

        Mockito.verify(worker, Mockito.never()).deleteAllManagedNetworkRules(Mockito.any());
        Mockito.verifyNoInteractions(firewallRuleMapDao, aclItemMapDao);
    }

    @Test(expected = ManagementServerException.class)
    public void missingNetworkWithOwnedRulesFailsClosed() throws ManagementServerException {
        Mockito.when(firewallRuleMapDao.listByClusterId(1L))
                .thenReturn(List.of(Mockito.mock(KubernetesClusterFirewallRuleMapVO.class)));
        Mockito.when(aclItemMapDao.listByClusterId(1L)).thenReturn(List.of());

        worker.deleteManagedNetworkRulesIfPresent(null, KubernetesClusterNetworkRuleOwnershipState.MANAGED);
    }

    @Test
    public void missingNetworkWithoutOwnedRulesIsAlreadyClean() throws ManagementServerException {
        Mockito.when(firewallRuleMapDao.listByClusterId(1L)).thenReturn(List.of());
        Mockito.when(aclItemMapDao.listByClusterId(1L)).thenReturn(List.of());

        worker.deleteManagedNetworkRulesIfPresent(null, KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(worker, Mockito.never()).deleteAllManagedNetworkRules(Mockito.any());
    }

    private void prepareVpcPublicIp() {
        publicIp = Mockito.spy(new IPAddressVO(new Ip("203.0.113.10"), 1L, 1L, 1L, false));
        Mockito.when(cluster.getNetworkId()).thenReturn(2L);
        Mockito.when(network.getVpcId()).thenReturn(3L);
        Mockito.when(networkDao.findById(2L)).thenReturn(network);
        Mockito.doReturn(publicIp).when(worker).getVpcTierKubernetesPublicIp(network);
        Mockito.doReturn(4L).when(publicIp).getId();
    }

    @Test
    public void managedVpcPublicIpIsReleasedOnlyAfterAllResourcesAreGone() throws Exception {
        prepareVpcPublicIp();
        Mockito.when(firewallRulesDao.listByIpAndNotRevoked(4L)).thenReturn(List.of());
        Mockito.when(remoteAccessVpnDao.findByPublicIpAddress(4L)).thenReturn(null);
        Mockito.when(site2SiteVpnGatewayDao.findByPublicIpAddress(4L)).thenReturn(null);
        Mockito.when(networkService.releaseIpAddress(4L)).thenReturn(true);

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(worker).getVpcTierKubernetesPublicIp(network);
        Mockito.verify(firewallRulesDao).listByIpAndNotRevoked(4L);
        Mockito.verify(remoteAccessVpnDao).findByPublicIpAddress(4L);
        Mockito.verify(site2SiteVpnGatewayDao).findByPublicIpAddress(4L);
        Mockito.verify(networkService).releaseIpAddress(4L);
        Mockito.verify(clusterDetailsDao).removeDetail(1L, org.apache.cloudstack.api.ApiConstants.PUBLIC_IP_ID);
    }

    @Test
    public void managedVpcPublicIpWithUnownedFirewallRuleIsRetained() throws Exception {
        prepareVpcPublicIp();
        Mockito.when(firewallRulesDao.listByIpAndNotRevoked(4L))
                .thenReturn(List.of(Mockito.mock(FirewallRuleVO.class)));

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(networkService, Mockito.never()).releaseIpAddress(Mockito.anyLong());
        Mockito.verify(clusterDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void managedVpcPublicIpWithStaticNatBindingIsRetained() throws Exception {
        prepareVpcPublicIp();
        publicIp.setOneToOneNat(true);

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(networkService, Mockito.never()).releaseIpAddress(Mockito.anyLong());
        Mockito.verify(clusterDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }

    @Test
    public void managedVpcPublicIpWithRemoteAccessVpnIsRetained() throws Exception {
        prepareVpcPublicIp();
        Mockito.when(firewallRulesDao.listByIpAndNotRevoked(4L)).thenReturn(List.of());
        Mockito.when(remoteAccessVpnDao.findByPublicIpAddress(4L)).thenReturn(Mockito.mock(RemoteAccessVpnVO.class));

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(networkService, Mockito.never()).releaseIpAddress(Mockito.anyLong());
        Mockito.verify(remoteAccessVpnDao).findByPublicIpAddress(4L);
    }

    @Test
    public void managedVpcPublicIpWithSiteToSiteVpnGatewayIsRetained() throws Exception {
        prepareVpcPublicIp();
        Mockito.when(firewallRulesDao.listByIpAndNotRevoked(4L)).thenReturn(List.of());
        Mockito.when(site2SiteVpnGatewayDao.findByPublicIpAddress(4L)).thenReturn(Mockito.mock(Site2SiteVpnGatewayVO.class));

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.MANAGED);

        Mockito.verify(networkService, Mockito.never()).releaseIpAddress(Mockito.anyLong());
        Mockito.verify(site2SiteVpnGatewayDao).findByPublicIpAddress(4L);
    }

    @Test
    public void legacyVpcPublicIpIsRetainedWithoutInspectingOrDeletingResources() throws Exception {
        prepareVpcPublicIp();

        worker.releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED);

        Mockito.verifyNoInteractions(firewallRulesDao, remoteAccessVpnDao, site2SiteVpnGatewayDao, networkService);
        Mockito.verify(clusterDetailsDao, Mockito.never()).removeDetail(Mockito.anyLong(), Mockito.anyString());
    }
}
