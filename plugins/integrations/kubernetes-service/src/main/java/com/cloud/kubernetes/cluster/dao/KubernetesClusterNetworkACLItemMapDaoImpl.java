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
package com.cloud.kubernetes.cluster.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.kubernetes.cluster.KubernetesClusterNetworkACLItemMapVO;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
public class KubernetesClusterNetworkACLItemMapDaoImpl extends GenericDaoBase<KubernetesClusterNetworkACLItemMapVO, Long>
        implements KubernetesClusterNetworkACLItemMapDao {

    private final SearchBuilder<KubernetesClusterNetworkACLItemMapVO> clusterIdSearch;
    private final SearchBuilder<KubernetesClusterNetworkACLItemMapVO> networkAclItemIdSearch;
    private final SearchBuilder<KubernetesClusterNetworkACLItemMapVO> clusterRoleSearch;

    public KubernetesClusterNetworkACLItemMapDaoImpl() {
        clusterIdSearch = createSearchBuilder();
        clusterIdSearch.and("clusterId", clusterIdSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        clusterIdSearch.done();

        networkAclItemIdSearch = createSearchBuilder();
        networkAclItemIdSearch.and("networkAclItemId", networkAclItemIdSearch.entity().getNetworkAclItemId(), SearchCriteria.Op.EQ);
        networkAclItemIdSearch.done();

        clusterRoleSearch = createSearchBuilder();
        clusterRoleSearch.and("clusterId", clusterRoleSearch.entity().getClusterId(), SearchCriteria.Op.EQ);
        clusterRoleSearch.and("logicalRole", clusterRoleSearch.entity().getLogicalRole(), SearchCriteria.Op.EQ);
        clusterRoleSearch.done();
    }

    @Override
    public List<KubernetesClusterNetworkACLItemMapVO> listByClusterId(long clusterId) {
        SearchCriteria<KubernetesClusterNetworkACLItemMapVO> criteria = clusterIdSearch.create();
        criteria.setParameters("clusterId", clusterId);
        return listBy(criteria);
    }

    @Override
    public KubernetesClusterNetworkACLItemMapVO findByNetworkAclItemId(long networkAclItemId) {
        SearchCriteria<KubernetesClusterNetworkACLItemMapVO> criteria = networkAclItemIdSearch.create();
        criteria.setParameters("networkAclItemId", networkAclItemId);
        return findOneBy(criteria);
    }

    @Override
    public KubernetesClusterNetworkACLItemMapVO findByClusterIdAndLogicalRole(long clusterId, String logicalRole) {
        SearchCriteria<KubernetesClusterNetworkACLItemMapVO> criteria = clusterRoleSearch.create();
        criteria.setParameters("clusterId", clusterId);
        criteria.setParameters("logicalRole", logicalRole);
        return findOneBy(criteria);
    }
}
