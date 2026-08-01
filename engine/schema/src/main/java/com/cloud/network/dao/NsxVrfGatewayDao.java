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

import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.utils.db.GenericDao;

public interface NsxVrfGatewayDao extends GenericDao<NsxVrfGatewayVO, Long> {

    NsxVrfGatewayVO findByUuid(String uuid);

    NsxVrfGatewayVO findByZoneAndTier0Name(long zoneId, String tier0Name);

    /** The gateway claimed by this exact account, if any. */
    NsxVrfGatewayVO findByAccount(long zoneId, long accountId);

    /** The gateway claimed by this exact domain, if any — no ancestor walking. */
    NsxVrfGatewayVO findByDomain(long zoneId, long domainId);

    List<NsxVrfGatewayVO> listByZone(long zoneId);

    /** Pool members in this zone that no tenant has claimed. */
    List<NsxVrfGatewayVO> listUnclaimed(long zoneId);
}
