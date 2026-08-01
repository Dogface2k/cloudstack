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
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.dao.KubernetesClusterDao;
import com.cloud.utils.exception.CloudRuntimeException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class KubernetesClusterStartWorkerTest {

    @Mock
    private KubernetesClusterDao kubernetesClusterDaoMock;

    @Mock
    private KubernetesClusterManagerImpl kubernetesClusterManagerMock;

    @Mock
    private KubernetesCluster kubernetesClusterMock;

    private KubernetesClusterStartWorker kubernetesClusterStartWorker;

    @Before
    public void setUp() {
        kubernetesClusterManagerMock.kubernetesClusterDao = kubernetesClusterDaoMock;

        kubernetesClusterStartWorker = new KubernetesClusterStartWorker(kubernetesClusterMock, kubernetesClusterManagerMock);
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateKubernetesClusterEntryEndpointTestThrowsWhenClusterNoLongerExists() {
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(kubernetesClusterDaoMock.findById(1L)).thenReturn(null);

        kubernetesClusterStartWorker.updateKubernetesClusterEntryEndpoint();
    }

    @Test
    public void updateKubernetesClusterEntryEndpointTestUpdatesEndpoint() {
        KubernetesClusterVO kubernetesClusterVO = Mockito.mock(KubernetesClusterVO.class);
        Mockito.when(kubernetesClusterMock.getId()).thenReturn(1L);
        Mockito.when(kubernetesClusterDaoMock.findById(1L)).thenReturn(kubernetesClusterVO);
        kubernetesClusterStartWorker.publicIpAddress = "10.1.1.1";

        kubernetesClusterStartWorker.updateKubernetesClusterEntryEndpoint();

        Mockito.verify(kubernetesClusterVO).setEndpoint(String.format("https://10.1.1.1:%d/", KubernetesClusterActionWorker.CLUSTER_API_PORT));
        Mockito.verify(kubernetesClusterDaoMock).update(1L, kubernetesClusterVO);
    }
}
