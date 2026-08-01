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
import org.apache.cloudstack.api.response.SuccessResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.service.NsxProviderService;

/**
 * Deregisters a tier-0 gateway from CloudStack. The gateway itself is left untouched in
 * NSX, along with its uplinks and BGP peerings — this only removes CloudStack's record.
 */
@APICommand(name = DeleteNsxVrfGatewayCmd.APINAME, description = "Removes CloudStack's registration of an NSX VRF gateway. The gateway itself is not deleted from NSX",
        responseObject = SuccessResponse.class, requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = false, since = "4.23.0")
public class DeleteNsxVrfGatewayCmd extends BaseCmd {
    public static final String APINAME = "deleteNsxVrfGateway";

    @Inject
    NsxProviderService nsxProviderService;

    @Parameter(name = ApiConstants.ID, type = CommandType.UUID, entityType = NsxVrfGatewayResponse.class,
            required = true, description = "the ID of the NSX VRF gateway registration to remove")
    private Long id;

    public Long getId() {
        return id;
    }

    @Override
    public void execute() throws ServerApiException {
        boolean result = nsxProviderService.deleteNsxVrfGateway(getId());
        SuccessResponse response = new SuccessResponse(getCommandName());
        response.setSuccess(result);
        setResponseObject(response);
    }

    @Override
    public long getEntityOwnerId() {
        return CallContext.current().getCallingAccount().getId();
    }
}
