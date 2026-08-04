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

import java.util.List;

import javax.inject.Inject;

import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseListCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.NsxVrfGatewayResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.service.NsxProviderService;

@APICommand(name = ListNsxVrfGatewaysCmd.APINAME, description = "Lists the NSX VRF gateways registered with CloudStack",
        responseObject = NsxVrfGatewayResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0", authorized = {RoleType.Admin})
public class ListNsxVrfGatewaysCmd extends BaseListCmd {
    public static final String APINAME = "listNsxVrfGateways";

    @Inject
    NsxProviderService nsxProviderService;

    @Parameter(name = ApiConstants.ZONE_ID, type = CommandType.UUID, entityType = ZoneResponse.class,
            description = "list gateways in this zone only")
    private Long zoneId;

    @Parameter(name = ApiConstants.ACCOUNT_ID, type = CommandType.UUID, entityType = AccountResponse.class,
            description = "list the gateway assigned to this account only")
    private Long accountId;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "list the gateway assigned to this domain only")
    private Long domainId;

    @Parameter(name = ApiConstants.ALLOCATED_ONLY, type = CommandType.BOOLEAN,
            description = "when true, list only gateways already assigned to a tenant; when false, only unassigned ones")
    private Boolean allocatedOnly;

    public Long getZoneId() {
        return zoneId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getDomainId() {
        return domainId;
    }

    public Boolean getAllocatedOnly() {
        return allocatedOnly;
    }

    @Override
    public void execute() throws ServerApiException {
        List<NsxVrfGatewayResponse> gateways = nsxProviderService.listNsxVrfGateways(this);
        ListResponse<NsxVrfGatewayResponse> response = new ListResponse<>();
        response.setResponses(gateways, gateways.size());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }
}
