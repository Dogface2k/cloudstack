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
package com.cloud.kubernetes.cluster;

import java.util.Arrays;

import com.cloud.utils.exception.CloudRuntimeException;

public enum KubernetesClusterNetworkRuleRole {
    API_FIREWALL(ResourceType.FIREWALL, false),
    SSH_FIREWALL(ResourceType.FIREWALL, false),
    ETCD_SSH_FIREWALL(ResourceType.FIREWALL, true),
    EXTERNAL_SSH_FIREWALL(ResourceType.FIREWALL, true),
    API_PORT_FORWARD(ResourceType.PORT_FORWARDING, false),
    SSH_PORT_FORWARD(ResourceType.PORT_FORWARDING, true),
    API_LOAD_BALANCER(ResourceType.LOAD_BALANCER, false),
    API_ACL(ResourceType.NETWORK_ACL_ITEM, false),
    SSH_ACL(ResourceType.NETWORK_ACL_ITEM, false),
    ETCD_CLIENT_ACL(ResourceType.NETWORK_ACL_ITEM, false);

    public enum ResourceType {
        FIREWALL,
        PORT_FORWARDING,
        LOAD_BALANCER,
        NETWORK_ACL_ITEM
    }

    private static final String DYNAMIC_SEPARATOR = ":";

    private final ResourceType resourceType;
    private final boolean vmScoped;

    KubernetesClusterNetworkRuleRole(ResourceType resourceType, boolean vmScoped) {
        this.resourceType = resourceType;
        this.vmScoped = vmScoped;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public boolean isVmScoped() {
        return vmScoped;
    }

    public String toLogicalRole(Long vmId) {
        if (!vmScoped) {
            if (vmId != null) {
                throw new CloudRuntimeException(String.format("Role %s is not scoped to a virtual machine", name()));
            }
            return name();
        }
        if (vmId == null || vmId <= 0) {
            throw new CloudRuntimeException(String.format("Role %s requires a virtual machine", name()));
        }
        return name() + DYNAMIC_SEPARATOR + vmId;
    }

    public static KubernetesClusterNetworkRuleRole fromLogicalRole(String logicalRole) {
        if (logicalRole == null) {
            throw new CloudRuntimeException("Kubernetes cluster network-rule role is missing");
        }
        return Arrays.stream(values())
                .filter(role -> role.vmScoped ? logicalRole.startsWith(role.name() + DYNAMIC_SEPARATOR) : logicalRole.equals(role.name()))
                .findFirst()
                .orElseThrow(() -> new CloudRuntimeException(String.format("Unsupported Kubernetes cluster network-rule role %s", logicalRole)));
    }

    public Long getVmId(String logicalRole) {
        if (!vmScoped) {
            if (!name().equals(logicalRole)) {
                throw new CloudRuntimeException(String.format("Invalid logical role %s for role %s", logicalRole, name()));
            }
            return null;
        }
        String prefix = name() + DYNAMIC_SEPARATOR;
        if (!logicalRole.startsWith(prefix)) {
            throw new CloudRuntimeException(String.format("Invalid logical role %s for role %s", logicalRole, name()));
        }
        try {
            Long vmId = Long.valueOf(logicalRole.substring(prefix.length()));
            if (vmId <= 0) {
                throw new NumberFormatException("VM ID must be positive");
            }
            return vmId;
        } catch (NumberFormatException e) {
            throw new CloudRuntimeException(String.format("Invalid virtual machine identifier in logical role %s", logicalRole), e);
        }
    }
}
