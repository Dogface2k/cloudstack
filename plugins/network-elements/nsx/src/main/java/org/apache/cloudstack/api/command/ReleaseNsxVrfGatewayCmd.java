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
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.service.NsxProviderService;

/**
 * Returns a tier-0 gateway to the unassigned pool. Refused while the tenant still has
 * networks attached to it, since detaching a live tier-1 from its tier-0 is not something
 * CloudStack can do without disrupting the tenant.
 */
@APICommand(name = ReleaseNsxVrfGatewayCmd.APINAME, description = "Releases an NSX VRF gateway from its account or domain, returning it to the pool",
        responseObject = NsxVrfGatewayResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0")
public class ReleaseNsxVrfGatewayCmd extends BaseCmd {
    public static final String APINAME = "releaseNsxVrfGateway";

    @Inject
    NsxProviderService nsxProviderService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = NsxVrfGatewayResponse.class,
            required = true, description = "the ID of the NSX VRF gateway to release")
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public void execute() throws ServerApiException {
        NsxVrfGatewayResponse response = nsxProviderService.releaseNsxVrfGateway(getId());
        response.setResponseName(getCommandName());
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
