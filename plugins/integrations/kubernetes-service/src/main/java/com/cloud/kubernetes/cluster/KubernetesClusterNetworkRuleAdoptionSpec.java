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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.exception.InvalidParameterValueException;

public class KubernetesClusterNetworkRuleAdoptionSpec {

    private static final String RESOURCE_TYPE_KEY = "resourcetype";
    private static final String ROLE_KEY = "role";
    private static final String RESOURCE_ID_KEY = "resourceid";
    private static final String VIRTUAL_MACHINE_ID_KEY = "virtualmachineid";
    private static final Set<String> ALLOWED_KEYS = Set.of(RESOURCE_TYPE_KEY, ROLE_KEY, RESOURCE_ID_KEY,
            VIRTUAL_MACHINE_ID_KEY);

    private final KubernetesClusterNetworkRuleRole.ResourceType resourceType;
    private final KubernetesClusterNetworkRuleRole role;
    private final String resourceUuid;
    private final String virtualMachineUuid;

    public KubernetesClusterNetworkRuleAdoptionSpec(KubernetesClusterNetworkRuleRole.ResourceType resourceType,
            KubernetesClusterNetworkRuleRole role, String resourceUuid, String virtualMachineUuid) {
        this.resourceType = resourceType;
        this.role = role;
        this.resourceUuid = resourceUuid;
        this.virtualMachineUuid = virtualMachineUuid;
    }

    public KubernetesClusterNetworkRuleRole.ResourceType getResourceType() {
        return resourceType;
    }

    public KubernetesClusterNetworkRuleRole getRole() {
        return role;
    }

    public String getResourceUuid() {
        return resourceUuid;
    }

    public String getVirtualMachineUuid() {
        return virtualMachineUuid;
    }

    public String getExternalIdentity() {
        return role.name() + ":" + StringUtils.defaultString(virtualMachineUuid);
    }

    public static List<KubernetesClusterNetworkRuleAdoptionSpec> parse(Map<String, Map<String, String>> declarations) {
        if (MapUtils.isEmpty(declarations)) {
            return new ArrayList<>();
        }
        List<KubernetesClusterNetworkRuleAdoptionSpec> result = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Map.Entry<String, Map<String, String>> entry : declarations.entrySet()) {
            Map<String, String> declaration = entry.getValue();
            if (MapUtils.isEmpty(declaration)) {
                throw new InvalidParameterValueException(String.format("Network-rule declaration %s is empty", entry.getKey()));
            }
            Set<String> keys = new HashSet<>();
            declaration.keySet().forEach(key -> {
                if (key == null) {
                    throw new InvalidParameterValueException(String.format(
                            "Network-rule declaration %s contains a null field name", entry.getKey()));
                }
                String canonicalKey = key.toLowerCase(Locale.ROOT);
                if (!keys.add(canonicalKey)) {
                    throw new InvalidParameterValueException(String.format(
                            "Network-rule declaration %s repeats field %s", entry.getKey(), key));
                }
            });
            if (!ALLOWED_KEYS.containsAll(keys)) {
                keys.removeAll(ALLOWED_KEYS);
                throw new InvalidParameterValueException(String.format("Network-rule declaration %s has unsupported fields %s", entry.getKey(), keys));
            }
            KubernetesClusterNetworkRuleRole role = parseEnum(KubernetesClusterNetworkRuleRole.class,
                    value(declaration, ROLE_KEY), "role", entry.getKey());
            KubernetesClusterNetworkRuleRole.ResourceType resourceType = parseEnum(KubernetesClusterNetworkRuleRole.ResourceType.class,
                    value(declaration, RESOURCE_TYPE_KEY), "resource type", entry.getKey());
            if (!role.getResourceType().equals(resourceType)) {
                throw new InvalidParameterValueException(String.format("Role %s requires resource type %s", role, role.getResourceType()));
            }
            String resourceUuid = StringUtils.trimToNull(value(declaration, RESOURCE_ID_KEY));
            String vmUuid = StringUtils.trimToNull(value(declaration, VIRTUAL_MACHINE_ID_KEY));
            if (resourceUuid == null) {
                throw new InvalidParameterValueException(String.format("Network-rule declaration %s is missing resourceId", entry.getKey()));
            }
            if (role.isVmScoped() != (vmUuid != null)) {
                throw new InvalidParameterValueException(String.format("Role %s %s virtualMachineId", role,
                        role.isVmScoped() ? "requires" : "does not accept"));
            }
            KubernetesClusterNetworkRuleAdoptionSpec spec = new KubernetesClusterNetworkRuleAdoptionSpec(resourceType, role,
                    resourceUuid, vmUuid);
            if (!identities.add(spec.getExternalIdentity())) {
                throw new InvalidParameterValueException(String.format("Duplicate network-rule declaration for %s", spec.getExternalIdentity()));
            }
            result.add(spec);
        }
        return result;
    }

    private static String value(Map<String, String> declaration, String key) {
        return declaration.entrySet().stream()
                .filter(entry -> key.equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> type, String value, String field, String declaration) {
        if (StringUtils.isBlank(value)) {
            throw new InvalidParameterValueException(String.format("Network-rule declaration %s is missing %s", declaration, field));
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidParameterValueException(String.format("Network-rule declaration %s has invalid %s %s", declaration, field, value));
        }
    }
}
