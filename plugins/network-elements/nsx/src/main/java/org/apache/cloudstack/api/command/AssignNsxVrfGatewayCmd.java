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
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.NsxVrfGatewayResponse;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.service.NsxProviderService;

/**
 * Claims a staged tier-0 gateway for a tenant. Exactly one of accountid or domainid must
 * be given: that choice is what {@code nsx.vrf.scope} matches against when a tenant's
 * tier-1 gateways are created.
 */
@APICommand(name = AssignNsxVrfGatewayCmd.APINAME, description = "Assigns a registered NSX VRF gateway to an account or a domain",
        responseObject = NsxVrfGatewayResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0", authorized = {RoleType.Admin})
public class AssignNsxVrfGatewayCmd extends BaseCmd {
    public static final String APINAME = "assignNsxVrfGateway";

    @Inject
    NsxProviderService nsxProviderService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = NsxVrfGatewayResponse.class,
            required = true, description = "the ID of the NSX VRF gateway to assign")
    private Long id;

    @Parameter(name = ApiConstants.ACCOUNT_ID, type = CommandType.UUID, entityType = AccountResponse.class,
            description = "the ID of the account to assign the gateway to")
    private Long accountId;

    @Parameter(name = ApiConstants.DOMAIN_ID, type = CommandType.UUID, entityType = DomainResponse.class,
            description = "the ID of the domain to assign the gateway to; every account in the domain, and in its " +
                    "sub-domains, resolves to this gateway unless it has one of its own")
    private Long domainId;

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getDomainId() {
        return domainId;
    }

    @Override
    public void execute() throws ServerApiException {
        NsxVrfGatewayResponse response = nsxProviderService.assignNsxVrfGateway(this);
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
