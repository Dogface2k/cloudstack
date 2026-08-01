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

import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@Component
@DB()
public class NsxVrfGatewayDaoImpl extends GenericDaoBase<NsxVrfGatewayVO, Long> implements NsxVrfGatewayDao {

    private final SearchBuilder<NsxVrfGatewayVO> allFieldsSearch;
    private final SearchBuilder<NsxVrfGatewayVO> unclaimedSearch;

    public NsxVrfGatewayDaoImpl() {
        super();

        allFieldsSearch = createSearchBuilder();
        allFieldsSearch.and("uuid", allFieldsSearch.entity().getUuid(), SearchCriteria.Op.EQ);
        allFieldsSearch.and("zone_id", allFieldsSearch.entity().getZoneId(), SearchCriteria.Op.EQ);
        allFieldsSearch.and("nsx_tier0_name", allFieldsSearch.entity().getNsxTier0Name(), SearchCriteria.Op.EQ);
        allFieldsSearch.and("account_id", allFieldsSearch.entity().getAccountId(), SearchCriteria.Op.EQ);
        allFieldsSearch.and("domain_id", allFieldsSearch.entity().getDomainId(), SearchCriteria.Op.EQ);
        allFieldsSearch.done();

        unclaimedSearch = createSearchBuilder();
        unclaimedSearch.and("zone_id", unclaimedSearch.entity().getZoneId(), SearchCriteria.Op.EQ);
        unclaimedSearch.and("account_id", unclaimedSearch.entity().getAccountId(), SearchCriteria.Op.NULL);
        unclaimedSearch.and("domain_id", unclaimedSearch.entity().getDomainId(), SearchCriteria.Op.NULL);
        unclaimedSearch.done();
    }

    @Override
    public NsxVrfGatewayVO findByUuid(String uuid) {
        SearchCriteria<NsxVrfGatewayVO> sc = allFieldsSearch.create();
        sc.setParameters("uuid", uuid);
        return findOneBy(sc);
    }

    @Override
    public NsxVrfGatewayVO findByZoneAndTier0Name(long zoneId, String tier0Name) {
        SearchCriteria<NsxVrfGatewayVO> sc = allFieldsSearch.create();
        sc.setParameters("zone_id", zoneId);
        sc.setParameters("nsx_tier0_name", tier0Name);
        return findOneBy(sc);
    }

    @Override
    public NsxVrfGatewayVO findByAccount(long zoneId, long accountId) {
        SearchCriteria<NsxVrfGatewayVO> sc = allFieldsSearch.create();
        sc.setParameters("zone_id", zoneId);
        sc.setParameters("account_id", accountId);
        return findOneBy(sc);
    }

    @Override
    public NsxVrfGatewayVO findByDomain(long zoneId, long domainId) {
        SearchCriteria<NsxVrfGatewayVO> sc = allFieldsSearch.create();
        sc.setParameters("zone_id", zoneId);
        sc.setParameters("domain_id", domainId);
        return findOneBy(sc);
    }

    @Override
    public List<NsxVrfGatewayVO> listByZone(long zoneId) {
        SearchCriteria<NsxVrfGatewayVO> sc = allFieldsSearch.create();
        sc.setParameters("zone_id", zoneId);
        return listBy(sc);
    }

    @Override
    public List<NsxVrfGatewayVO> listUnclaimed(long zoneId) {
        SearchCriteria<NsxVrfGatewayVO> sc = unclaimedSearch.create();
        sc.setParameters("zone_id", zoneId);
        return listBy(sc);
    }
}
