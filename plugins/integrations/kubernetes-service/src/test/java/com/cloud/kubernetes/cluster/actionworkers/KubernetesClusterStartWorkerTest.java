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

import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDetailsDao;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterVmMapDao;
import com.cloud.kubernetes.version.dao.KubernetesSupportedVersionDao;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
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
        Mockito.when(cluster.getAccountId()).thenReturn(2L);
        Mockito.when(cluster.getNetworkId()).thenReturn(3L);
        Mockito.when(cluster.getName()).thenReturn("test-cluster");
    }

    @Test
    public void reconcileDirectAccessNetworkIsANoOp() {
        AccountVO owner = Mockito.mock(AccountVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(accountDao.findById(2L)).thenReturn(owner);
        Mockito.when(networkDao.findById(3L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(true);

        Assert.assertTrue(worker.reconcileKubernetesClusterNetworkRules());

        Mockito.verify(worker, Mockito.never()).getKubernetesClusterVMMaps();
    }

    @Test(expected = CloudRuntimeException.class)
    public void reconcileVpcTierWithoutAclFailsClearly() {
        AccountVO owner = Mockito.mock(AccountVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        Mockito.when(accountDao.findById(2L)).thenReturn(owner);
        Mockito.when(networkDao.findById(3L)).thenReturn(network);
        Mockito.when(manager.isDirectAccess(network)).thenReturn(false);
        Mockito.when(network.getVpcId()).thenReturn(4L);
        Mockito.when(network.getNetworkACLId()).thenReturn(null);

        worker.reconcileKubernetesClusterNetworkRules();
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
        Mockito.doReturn(List.of(workerMap, etcdMap, controlMap)).when(worker).getKubernetesClusterVMMaps();
        Mockito.when(userVmDao.findById(10L)).thenReturn(workerVm);
        Mockito.when(userVmDao.findById(11L)).thenReturn(etcdVm);
        Mockito.when(userVmDao.findById(12L)).thenReturn(controlVm);
        Mockito.doReturn(publicIp).when(worker).getPublicIp(network);
        Mockito.when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        Mockito.doNothing().when(worker).setupKubernetesClusterNetworkRules(Mockito.eq(network), Mockito.anyList(), Mockito.eq(publicIp));
        Mockito.doNothing().when(worker).setupKubernetesEtcdNetworkRules(Mockito.anyList(), Mockito.eq(network), Mockito.eq(publicIp));

        Assert.assertTrue(worker.reconcileKubernetesClusterNetworkRules());

        ArgumentCaptor<List<UserVm>> clusterVms = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<UserVm>> etcdVms = ArgumentCaptor.forClass(List.class);
        Mockito.verify(worker).setupKubernetesClusterNetworkRules(Mockito.eq(network), clusterVms.capture(), Mockito.eq(publicIp));
        Mockito.verify(worker).setupKubernetesEtcdNetworkRules(etcdVms.capture(), Mockito.eq(network), Mockito.eq(publicIp));
        Assert.assertEquals(List.of(controlVm, workerVm), clusterVms.getValue());
        Assert.assertEquals(List.of(etcdVm), etcdVms.getValue());
    }
}
