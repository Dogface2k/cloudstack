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
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import org.apache.cloudstack.api.InternalIdentity;

/**
 * A VRF (or dedicated) Tier-0 gateway that an operator has staged in NSX and registered
 * with CloudStack, so that a tenant's Tier-1 gateways attach to it instead of to the
 * zone-wide Tier-0 recorded in {@code nsx_providers}.
 *
 * CloudStack never creates or deletes the gateway in NSX: the uplink interfaces and BGP
 * peerings a Tier-0 needs are provisioned with the operator's physical network and cannot
 * be automated from here. A row is a claim on something that already exists.
 *
 * A row with neither {@code accountId} nor {@code domainId} is an unclaimed pool member.
 */
@Entity
@Table(name = "nsx_vrf_gateways")
public class NsxVrfGatewayVO implements InternalIdentity {

    /** How a staged gateway is matched to a tenant. */
    public enum Scope {
        ACCOUNT, DOMAIN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @Column(name = "uuid")
    private String uuid;

    @Column(name = "zone_id")
    private long zoneId;

    @Column(name = "nsx_tier0_name")
    private String nsxTier0Name;

    @Column(name = "edge_cluster")
    private String edgeCluster;

    @Column(name = "parent_tier0")
    private String parentTier0;

    @Column(name = "scope")
    private String scope;

    @Column(name = "domain_id")
    private Long domainId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "public_vlan_db_id")
    private Long publicVlanDbId;

    @Column(name = "created")
    private Date created;

    @Column(name = "removed")
    private Date removed;

    public NsxVrfGatewayVO() {
        this.uuid = UUID.randomUUID().toString();
    }

    public NsxVrfGatewayVO(long zoneId, String nsxTier0Name, String edgeCluster, String parentTier0) {
        this();
        this.zoneId = zoneId;
        this.nsxTier0Name = nsxTier0Name;
        this.edgeCluster = edgeCluster;
        this.parentTier0 = parentTier0;
        this.created = new Date();
    }

    @Override
    public long getId() {
        return id;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public long getZoneId() {
        return zoneId;
    }

    public void setZoneId(long zoneId) {
        this.zoneId = zoneId;
    }

    public String getNsxTier0Name() {
        return nsxTier0Name;
    }

    public void setNsxTier0Name(String nsxTier0Name) {
        this.nsxTier0Name = nsxTier0Name;
    }

    public String getEdgeCluster() {
        return edgeCluster;
    }

    public void setEdgeCluster(String edgeCluster) {
        this.edgeCluster = edgeCluster;
    }

    public String getParentTier0() {
        return parentTier0;
    }

    public void setParentTier0(String parentTier0) {
        this.parentTier0 = parentTier0;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public Long getDomainId() {
        return domainId;
    }

    public void setDomainId(Long domainId) {
        this.domainId = domainId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public Long getPublicVlanDbId() {
        return publicVlanDbId;
    }

    public void setPublicVlanDbId(Long publicVlanDbId) {
        this.publicVlanDbId = publicVlanDbId;
    }

    public Date getCreated() {
        return created;
    }

    public Date getRemoved() {
        return removed;
    }

    /** True when no tenant has claimed this gateway yet. */
    public boolean isUnclaimed() {
        return accountId == null && domainId == null;
    }

    // NOTE: do not add a helper here that clears the assignment by writing the fields
    // directly. Entities are CGLIB-enhanced and GenericDaoBase builds its UPDATE from
    // setter calls intercepted by UpdateBuilder, so direct field writes persist nothing
    // and the update silently succeeds having changed no columns. Callers must use the
    // setters (see NsxProviderServiceImpl.releaseNsxVrfGateway).

    @Override
    public String toString() {
        return String.format("NsxVrfGateway {id: %d, uuid: %s, tier0: %s, edgeCluster: %s, scope: %s}",
                id, uuid, nsxTier0Name, edgeCluster, scope);
    }
}
