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
package org.apache.cloudstack;

import org.apache.cloudstack.agent.api.ValidateNsxVrfGatewayCommand;

public class NsxVrfGatewayValidationAnswer extends NsxAnswer {

    private final String edgeClusterPath;
    private final String parentTier0Path;

    public NsxVrfGatewayValidationAnswer(ValidateNsxVrfGatewayCommand command,
            boolean success, String details, String edgeClusterPath, String parentTier0Path) {
        super(command, success, details);
        this.edgeClusterPath = edgeClusterPath;
        this.parentTier0Path = parentTier0Path;
    }

    public NsxVrfGatewayValidationAnswer(ValidateNsxVrfGatewayCommand command, Exception exception) {
        super(command, exception);
        this.edgeClusterPath = null;
        this.parentTier0Path = null;
    }

    public String getEdgeClusterPath() {
        return edgeClusterPath;
    }

    public String getParentTier0Path() {
        return parentTier0Path;
    }
}
