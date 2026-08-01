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
package org.apache.cloudstack.api.command;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.NsxVrfGatewayResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.service.NsxProviderService;

/**
 * Registers a VRF, or dedicated, tier-0 gateway that the operator has already staged in
 * NSX. CloudStack never creates the gateway itself: its uplink interfaces and BGP peerings
 * are provisioned alongside the physical network and cannot be automated from here.
 */
@APICommand(name = AddNsxVrfGatewayCmd.APINAME, description = "Registers an existing NSX VRF or dedicated tier-0 gateway with CloudStack, so tenant tier-1 gateways can be attached to it",
        responseObject = NsxVrfGatewayResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0")
public class AddNsxVrfGatewayCmd extends BaseCmd {
    public static final String APINAME = "addNsxVrfGateway";

    @Inject
    NsxProviderService nsxProviderService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class, required = true,
            description = "the ID of the zone the tier-0 gateway belongs to")
    private Long zoneId;

    @Parameter(name = ApiConstants.TIER0_GATEWAY, type = CommandType.STRING, required = true,
            description = "name of the VRF or dedicated tier-0 gateway as it exists in NSX")
    private String tier0Gateway;

    @Parameter(name = ApiConstants.EDGE_CLUSTER, type = CommandType.STRING, required = true,
            description = "name of the edge cluster the tier-0 gateway lives on; may differ from the zone default")
    private String edgeCluster;

    @Parameter(name = ApiConstants.PARENT_TIER0_GATEWAY, type = CommandType.STRING,
            description = "parent tier-0 gateway when registering a VRF gateway; omit for a dedicated tier-0")
    private String parentTier0Gateway;

    public Long getZoneId() {
        return zoneId;
    }

    public String getTier0Gateway() {
        return tier0Gateway;
    }

    public String getEdgeCluster() {
        return edgeCluster;
    }

    public String getParentTier0Gateway() {
        return parentTier0Gateway;
    }

    @Override
    public void execute() throws ServerApiException {
        NsxVrfGatewayResponse response = nsxProviderService.addNsxVrfGateway(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
