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
package org.apache.cloudstack.api.response;

import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.serializer.Param;
import com.google.gson.annotations.SerializedName;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseResponse;
import org.apache.cloudstack.api.EntityReference;

@EntityReference(value = {NsxVrfGatewayVO.class})
public class NsxVrfGatewayResponse extends BaseResponse {

    @SerializedName(ApiConstants.ID)
    @Param(description = "ID of the NSX VRF gateway")
    private String id;

    @SerializedName(ApiConstants.ZONE_ID)
    @Param(description = "ID of the zone the VRF gateway belongs to")
    private String zoneId;

    @SerializedName(ApiConstants.ZONE_NAME)
    @Param(description = "Name of the zone the VRF gateway belongs to")
    private String zoneName;

    @SerializedName(ApiConstants.TIER0_GATEWAY)
    @Param(description = "Name of the VRF, or dedicated, tier-0 gateway as it exists in NSX")
    private String tier0Gateway;

    @SerializedName(ApiConstants.EDGE_CLUSTER)
    @Param(description = "Name of the edge cluster this tier-0 gateway lives on")
    private String edgeCluster;

    @SerializedName(ApiConstants.PARENT_TIER0_GATEWAY)
    @Param(description = "Parent tier-0 gateway for a VRF gateway; empty for a dedicated tier-0")
    private String parentTier0Gateway;

    @SerializedName(ApiConstants.SCOPE)
    @Param(description = "Whether the gateway is assigned per ACCOUNT or per DOMAIN; empty while unassigned")
    private String scope;

    @SerializedName(ApiConstants.ACCOUNT_ID)
    @Param(description = "ID of the account the gateway is assigned to")
    private String accountId;

    @SerializedName(ApiConstants.ACCOUNT)
    @Param(description = "Name of the account the gateway is assigned to")
    private String accountName;

    @SerializedName(ApiConstants.DOMAIN_ID)
    @Param(description = "ID of the domain the gateway is assigned to")
    private String domainId;

    @SerializedName(ApiConstants.DOMAIN)
    @Param(description = "Name of the domain the gateway is assigned to")
    private String domainName;

    @SerializedName(ApiConstants.ALLOCATED)
    @Param(description = "Whether the gateway has been assigned to a tenant")
    private boolean allocated;

    @SerializedName(ApiConstants.VLAN_ID)
    @Param(description = "ID of the public IP range advertised by this tier-0")
    private String publicVlanId;

    public NsxVrfGatewayResponse() {
        setObjectName("nsxvrfgateway");
    }

    public void setId(String id) {
        this.id = id;
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
    }

    public void setZoneName(String zoneName) {
        this.zoneName = zoneName;
    }

    public void setTier0Gateway(String tier0Gateway) {
        this.tier0Gateway = tier0Gateway;
    }

    public void setEdgeCluster(String edgeCluster) {
        this.edgeCluster = edgeCluster;
    }

    public void setParentTier0Gateway(String parentTier0Gateway) {
        this.parentTier0Gateway = parentTier0Gateway;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public void setAllocated(boolean allocated) {
        this.allocated = allocated;
    }

    public void setPublicVlanId(String publicVlanId) {
        this.publicVlanId = publicVlanId;
    }

    public String getId() {
        return id;
    }

    public String getTier0Gateway() {
        return tier0Gateway;
    }

    public String getEdgeCluster() {
        return edgeCluster;
    }

    public String getScope() {
        return scope;
    }

    public boolean isAllocated() {
        return allocated;
    }
}
