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
package com.cloud.network.dao;

import java.util.List;

import org.springframework.stereotype.Component;

import com.cloud.network.element.NsxVrfGatewayPlacementVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
@DB()
public class NsxVrfGatewayPlacementDaoImpl extends GenericDaoBase<NsxVrfGatewayPlacementVO, Long>
        implements NsxVrfGatewayPlacementDao {

    private final SearchBuilder<NsxVrfGatewayPlacementVO> resourceSearch;
    private final GenericSearchBuilder<NsxVrfGatewayPlacementVO, Long> gatewayCountSearch;
    private final SearchBuilder<NsxVrfGatewayPlacementVO> zoneSearch;

    public NsxVrfGatewayPlacementDaoImpl() {
        resourceSearch = createSearchBuilder();
        resourceSearch.and("vpc_id", resourceSearch.entity().getVpcId(), SearchCriteria.Op.EQ);
        resourceSearch.and("network_id", resourceSearch.entity().getNetworkId(), SearchCriteria.Op.EQ);
        resourceSearch.done();

        gatewayCountSearch = createSearchBuilder(Long.class);
        gatewayCountSearch.select(null, SearchCriteria.Func.COUNT, gatewayCountSearch.entity().getId());
        gatewayCountSearch.and("gateway_id", gatewayCountSearch.entity().getGatewayId(), SearchCriteria.Op.EQ);
        gatewayCountSearch.done();

        zoneSearch = createSearchBuilder();
        zoneSearch.and("zone_id", zoneSearch.entity().getZoneId(), SearchCriteria.Op.EQ);
        zoneSearch.done();
    }

    @Override
    public NsxVrfGatewayPlacementVO findByVpcId(long vpcId) {
        SearchCriteria<NsxVrfGatewayPlacementVO> sc = resourceSearch.create();
        sc.setParameters("vpc_id", vpcId);
        return findOneBy(sc);
    }

    @Override
    public NsxVrfGatewayPlacementVO findByNetworkId(long networkId) {
        SearchCriteria<NsxVrfGatewayPlacementVO> sc = resourceSearch.create();
        sc.setParameters("network_id", networkId);
        return findOneBy(sc);
    }

    @Override
    public long countByGatewayId(long gatewayId) {
        SearchCriteria<Long> sc = gatewayCountSearch.create();
        sc.setParameters("gateway_id", gatewayId);
        List<Long> counts = customSearch(sc, null);
        return counts.isEmpty() || counts.get(0) == null ? 0L : counts.get(0);
    }

    @Override
    public List<NsxVrfGatewayPlacementVO> listByZone(long zoneId) {
        SearchCriteria<NsxVrfGatewayPlacementVO> sc = zoneSearch.create();
        sc.setParameters("zone_id", zoneId);
        return listBy(sc);
    }
}
