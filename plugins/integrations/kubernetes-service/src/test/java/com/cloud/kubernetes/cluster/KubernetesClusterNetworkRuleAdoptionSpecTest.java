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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;

import org.junit.Assert;
import org.junit.Test;

public class KubernetesClusterNetworkRuleAdoptionSpecTest {

    @Test
    public void parseAcceptsCaseInsensitiveFieldsAndValues() {
        Map<String, String> declaration = new LinkedHashMap<>();
        declaration.put("ResourceType", "port_forwarding");
        declaration.put("ROLE", "ssh_port_forward");
        declaration.put("resourceId", "rule-uuid");
        declaration.put("virtualMachineId", "vm-uuid");

        List<KubernetesClusterNetworkRuleAdoptionSpec> result = KubernetesClusterNetworkRuleAdoptionSpec.parse(
                Map.of("rule", declaration));

        Assert.assertEquals(1, result.size());
        KubernetesClusterNetworkRuleAdoptionSpec spec = result.get(0);
        Assert.assertEquals(KubernetesClusterNetworkRuleRole.ResourceType.PORT_FORWARDING, spec.getResourceType());
        Assert.assertEquals(KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, spec.getRole());
        Assert.assertEquals("rule-uuid", spec.getResourceUuid());
        Assert.assertEquals("vm-uuid", spec.getVirtualMachineUuid());
    }

    @Test
    public void parseAcceptsEmptyManifestForDirectNetwork() {
        Assert.assertTrue(KubernetesClusterNetworkRuleAdoptionSpec.parse(null).isEmpty());
        Assert.assertTrue(KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of()).isEmpty());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void parseRejectsFieldsRepeatedWithDifferentCase() {
        Map<String, String> declaration = new LinkedHashMap<>();
        declaration.put("role", "API_FIREWALL");
        declaration.put("ROLE", "API_FIREWALL");
        declaration.put("resourcetype", "FIREWALL");
        declaration.put("resourceid", "rule-uuid");
        KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of("rule", declaration));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void parseRejectsDuplicateLogicalIdentity() {
        Map<String, String> declaration = Map.of(
                "role", "SSH_PORT_FORWARD",
                "resourcetype", "PORT_FORWARDING",
                "resourceid", "rule-uuid",
                "virtualmachineid", "vm-uuid");
        KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of("first", declaration, "second", declaration));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void parseRejectsResourceTypeThatDoesNotMatchRole() {
        KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of("rule", Map.of(
                "role", "API_FIREWALL",
                "resourcetype", "LOAD_BALANCER",
                "resourceid", "rule-uuid")));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void parseRejectsDeclarationWithoutResource() {
        KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of("rule", Map.of(
                "role", "API_FIREWALL",
                "resourcetype", "FIREWALL")));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void parseRejectsVmForClusterScopedRole() {
        KubernetesClusterNetworkRuleAdoptionSpec.parse(Map.of("rule", Map.of(
                "role", "API_FIREWALL",
                "resourcetype", "FIREWALL",
                "resourceid", "rule-uuid",
                "virtualmachineid", "vm-uuid")));
    }

    @Test(expected = CloudRuntimeException.class)
    public void logicalRoleRejectsNonPositiveVmId() {
        KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD.toLogicalRole(0L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void logicalRoleParserRejectsNonPositiveVmId() {
        KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD.getVmId("SSH_PORT_FORWARD:-1");
    }
}
