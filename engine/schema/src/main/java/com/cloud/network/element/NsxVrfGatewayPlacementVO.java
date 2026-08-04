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
package com.cloud.network.element;

import java.util.Date;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.InternalIdentity;

@Entity
@Table(name = "nsx_vrf_gateway_placements")
public class NsxVrfGatewayPlacementVO implements InternalIdentity {

    public enum State {
        PENDING_CREATE,
        ACTIVE,
        PENDING_DELETE,
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "gateway_id")
    private Long gatewayId;

    @Column(name = "zone_id")
    private long zoneId;

    @Column(name = "domain_id")
    private long domainId;

    @Column(name = "account_id")
    private long accountId;

    @Column(name = "vpc_id")
    private Long vpcId;

    @Column(name = "network_id")
    private Long networkId;

    @Column(name = "tier0_name")
    private String tier0Name;

    @Column(name = "state")
    private String state;

    @Column(name = "created")
    private Date created;

    @Column(name = "updated")
    private Date updated;

    protected NsxVrfGatewayPlacementVO() {
    }

    public NsxVrfGatewayPlacementVO(Long gatewayId, long zoneId, long domainId, long accountId,
            Long vpcId, Long networkId, String tier0Name) {
        if ((vpcId == null) == (networkId == null)) {
            throw new IllegalArgumentException("Exactly one of vpcId or networkId is required");
        }
        this.gatewayId = gatewayId;
        this.zoneId = zoneId;
        this.domainId = domainId;
        this.accountId = accountId;
        this.vpcId = vpcId;
        this.networkId = networkId;
        this.tier0Name = tier0Name;
        this.state = State.PENDING_CREATE.name();
        this.created = new Date();
        this.updated = this.created;
    }

    @Override
    public long getId() {
        return id;
    }

    public Long getGatewayId() {
        return gatewayId;
    }

    public long getZoneId() {
        return zoneId;
    }

    public long getDomainId() {
        return domainId;
    }

    public long getAccountId() {
        return accountId;
    }

    public Long getVpcId() {
        return vpcId;
    }

    public Long getNetworkId() {
        return networkId;
    }

    public String getTier0Name() {
        return tier0Name;
    }

    public String getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state.name();
        this.updated = new Date();
    }

    public Date getCreated() {
        return created;
    }

    public Date getUpdated() {
        return updated;
    }

    public void setUpdated(Date updated) {
        this.updated = updated;
    }
}
