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
package com.cloud.kubernetes.cluster.actionworkers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkACLItemMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleAdoptionSpec;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleLifecycleState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleRole;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleRole.ResourceType;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.Nic;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

final class KubernetesClusterNetworkRuleOwnershipValidator {

    private static final Set<KubernetesCluster.State> ADOPTABLE_CLUSTER_STATES = Set.of(
            KubernetesCluster.State.Running, KubernetesCluster.State.Stopped,
            KubernetesCluster.State.Alert, KubernetesCluster.State.Error);

    private final KubernetesClusterResourceModifierActionWorker worker;

    KubernetesClusterNetworkRuleOwnershipValidator(KubernetesClusterResourceModifierActionWorker worker) {
        this.worker = worker;
    }

    boolean adopt(List<KubernetesClusterNetworkRuleAdoptionSpec> specs) {
        List<KubernetesClusterNetworkRuleAdoptionSpec> requestedSpecs = specs == null
                ? Collections.emptyList() : new ArrayList<>(specs);
        return Transaction.execute((TransactionCallback<Boolean>) status -> adoptLocked(requestedSpecs));
    }

    Set<String> getExpectedLogicalRoles(Network network, List<KubernetesClusterVmMapVO> vmMaps) {
        return new HashSet<>(buildExpectedRules(worker.kubernetesCluster, network, vmMaps).keySet());
    }

    private boolean adoptLocked(List<KubernetesClusterNetworkRuleAdoptionSpec> specs) {
        KubernetesClusterVO cluster = worker.kubernetesClusterDao.lockRow(worker.kubernetesCluster.getId(), true);
        if (cluster == null || cluster.getRemoved() != null) {
            throw new InvalidParameterValueException("Kubernetes cluster no longer exists");
        }
        if (!KubernetesCluster.ClusterType.CloudManaged.equals(cluster.getClusterType())) {
            throw new InvalidParameterValueException("Only CloudManaged Kubernetes clusters can adopt network-rule ownership");
        }
        if (!ADOPTABLE_CLUSTER_STATES.contains(cluster.getState())) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s is in transient state %s", cluster.getName(), cluster.getState()));
        }
        KubernetesClusterNetworkRuleOwnershipState ownershipState = cluster.getNetworkRuleOwnershipState();
        if (!KubernetesClusterNetworkRuleOwnershipState.LEGACY_UNMANAGED.equals(ownershipState)
                && !KubernetesClusterNetworkRuleOwnershipState.MANAGED.equals(ownershipState)) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s has unsupported network-rule ownership state %s", cluster.getName(), ownershipState));
        }

        Network network = worker.networkDao.findById(cluster.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException(String.format(
                    "Network for Kubernetes cluster %s cannot be found", cluster.getName()));
        }
        List<KubernetesClusterVmMapVO> vmMaps = worker.kubernetesClusterVmMapDao.listByClusterId(cluster.getId());
        Map<Long, KubernetesClusterVmMapVO> vmMapsById = validateClusterVmMaps(cluster, vmMaps);
        Map<String, ExpectedRule> expectedRules = buildExpectedRules(cluster, network, vmMaps);
        Map<String, RequestedRule> requestedRules = resolveRequestedRules(cluster, specs, vmMapsById, expectedRules);
        IpAddress publicIp = expectedRules.isEmpty() ? null : getPublicIp(network);
        List<RequestedRule> adoptedRules = new ArrayList<>(requestedRules.values());
        lockReferencedResources(adoptedRules);
        validateNoDuplicateResources(adoptedRules);
        validateAndAlignDynamicSshRules(cluster, network, publicIp, expectedRules, requestedRules);
        for (Map.Entry<String, ExpectedRule> entry : expectedRules.entrySet()) {
            ExpectedRule expected = expectedRules.get(entry.getKey());
            RequestedRule requested = requestedRules.get(entry.getKey());
            if (requested != null) {
                validatePresentResource(cluster, network, publicIp, expected, requested);
            } else {
                validateAbsentResource(cluster, network, publicIp, expected);
            }
        }

        if (KubernetesClusterNetworkRuleOwnershipState.MANAGED.equals(ownershipState)) {
            validateIdempotentReplay(cluster, adoptedRules);
            return true;
        }
        if (!worker.kubernetesClusterFirewallRuleMapDao.listByClusterId(cluster.getId()).isEmpty()
                || !worker.kubernetesClusterNetworkACLItemMapDao.listByClusterId(cluster.getId()).isEmpty()) {
            throw new CloudRuntimeException(String.format(
                    "Legacy Kubernetes cluster %s already has network-rule ownership records", cluster.getName()));
        }

        for (RequestedRule request : adoptedRules) {
            String logicalRole = request.expected.logicalRole;
            if (ResourceType.NETWORK_ACL_ITEM.equals(request.spec.getResourceType())) {
                KubernetesClusterNetworkACLItemMapVO persisted = worker.kubernetesClusterNetworkACLItemMapDao.persist(
                        new KubernetesClusterNetworkACLItemMapVO(cluster.getId(), request.resourceId, logicalRole,
                                KubernetesClusterNetworkRuleLifecycleState.ACTIVE));
                if (persisted == null) {
                    throw new CloudRuntimeException(String.format("Failed to record ownership of network ACL item %s",
                            request.spec.getResourceUuid()));
                }
            } else {
                KubernetesClusterFirewallRuleMapVO persisted = worker.kubernetesClusterFirewallRuleMapDao.persist(
                        new KubernetesClusterFirewallRuleMapVO(cluster.getId(), request.resourceId, logicalRole,
                                KubernetesClusterNetworkRuleLifecycleState.ACTIVE));
                if (persisted == null) {
                    throw new CloudRuntimeException(String.format("Failed to record ownership of network rule %s",
                            request.spec.getResourceUuid()));
                }
            }
        }
        cluster.setNetworkRuleOwnershipState(KubernetesClusterNetworkRuleOwnershipState.MANAGED);
        if (!worker.kubernetesClusterDao.update(cluster.getId(), cluster)) {
            throw new CloudRuntimeException(String.format(
                    "Failed to update network-rule ownership state for Kubernetes cluster %s", cluster.getName()));
        }
        return true;
    }

    private Map<Long, KubernetesClusterVmMapVO> validateClusterVmMaps(KubernetesClusterVO cluster,
            List<KubernetesClusterVmMapVO> vmMaps) {
        if (vmMaps == null || vmMaps.size() != cluster.getTotalNodeCount()) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s has %d VM mappings but expects %d nodes", cluster.getName(),
                    vmMaps == null ? 0 : vmMaps.size(), cluster.getTotalNodeCount()));
        }
        Map<Long, KubernetesClusterVmMapVO> result = new LinkedHashMap<>();
        long controlNodeCount = 0;
        long etcdNodeCount = 0;
        long workerNodeCount = 0;
        for (KubernetesClusterVmMapVO vmMap : vmMaps) {
            int assignedRoles = (vmMap.isControlNode() ? 1 : 0) + (vmMap.isEtcdNode() ? 1 : 0)
                    + (vmMap.isExternalNode() ? 1 : 0);
            if (assignedRoles > 1) {
                throw new InvalidParameterValueException(String.format(
                        "Mapped VM %d for Kubernetes cluster %s has conflicting node roles",
                        vmMap.getVmId(), cluster.getName()));
            }
            if (vmMap.isControlNode()) {
                controlNodeCount++;
            } else if (vmMap.isEtcdNode()) {
                etcdNodeCount++;
            } else {
                workerNodeCount++;
            }
            if (result.put(vmMap.getVmId(), vmMap) != null) {
                throw new CloudRuntimeException(String.format(
                        "Kubernetes cluster %s contains duplicate VM mapping %d", cluster.getName(), vmMap.getVmId()));
            }
            VMInstanceVO vm = worker.vmInstanceDao.findById(vmMap.getVmId());
            if (vm == null || VirtualMachine.State.Destroyed.equals(vm.getState())
                    || VirtualMachine.State.Expunging.equals(vm.getState())) {
                throw new InvalidParameterValueException(String.format(
                        "Mapped VM %d for Kubernetes cluster %s is unavailable", vmMap.getVmId(), cluster.getName()));
            }
            if (vm.getAccountId() != cluster.getAccountId() || vm.getDomainId() != cluster.getDomainId()) {
                throw new InvalidParameterValueException(String.format(
                        "Mapped VM %s does not belong to Kubernetes cluster %s", vm.getUuid(), cluster.getName()));
            }
        }
        if (controlNodeCount != cluster.getControlNodeCount()
                || etcdNodeCount != cluster.getEtcdNodeCount()
                || workerNodeCount != cluster.getNodeCount()) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s VM mapping roles do not match its configured node counts",
                    cluster.getName()));
        }
        return result;
    }

    private Map<String, ExpectedRule> buildExpectedRules(KubernetesCluster cluster, Network network,
            List<KubernetesClusterVmMapVO> vmMaps) {
        Map<String, ExpectedRule> expected = new LinkedHashMap<>();
        if (worker.manager.isDirectAccess(network)) {
            return expected;
        }
        List<KubernetesClusterVmMapVO> control = vmMaps.stream().filter(KubernetesClusterVmMapVO::isControlNode)
                .collect(Collectors.toList());
        List<KubernetesClusterVmMapVO> etcd = vmMaps.stream().filter(KubernetesClusterVmMapVO::isEtcdNode)
                .collect(Collectors.toList());
        List<KubernetesClusterVmMapVO> external = vmMaps.stream().filter(KubernetesClusterVmMapVO::isExternalNode)
                .collect(Collectors.toList());
        List<KubernetesClusterVmMapVO> standard = vmMaps.stream()
                .filter(vm -> !vm.isEtcdNode() && !vm.isExternalNode()).collect(Collectors.toList());
        if (control.isEmpty()) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s has no control-node mapping", cluster.getName()));
        }

        int standardSshEnd = KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT + standard.size() - 1;
        if (network.getVpcId() == null) {
            putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.API_FIREWALL, null,
                    KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT, null));
            putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.SSH_FIREWALL, null,
                    KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT, standardSshEnd, null));
            for (int i = 0; i < external.size(); i++) {
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.EXTERNAL_SSH_FIREWALL,
                        external.get(i), standardSshEnd + i + 1, standardSshEnd + i + 1, null));
            }
            int etcdStartPort = com.cloud.kubernetes.cluster.KubernetesClusterService.KubernetesEtcdNodeStartPort.value();
            for (int i = 0; i < etcd.size(); i++) {
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.ETCD_SSH_FIREWALL,
                        etcd.get(i), etcdStartPort + i, etcdStartPort + i, null));
            }
            putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.API_LOAD_BALANCER, null,
                    KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT,
                    KubernetesClusterActionWorker.CLUSTER_API_PORT));
        } else {
            validateVpcTierAclTopology(cluster, network);
            if (!Objects.equals(network.getNetworkACLId(), NetworkACL.DEFAULT_ALLOW)) {
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.API_ACL, null,
                        KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT, null));
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.SSH_ACL, null,
                        KubernetesClusterActionWorker.DEFAULT_SSH_PORT, KubernetesClusterActionWorker.DEFAULT_SSH_PORT, null));
                if (!etcd.isEmpty()) {
                    putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.ETCD_CLIENT_ACL, null,
                            KubernetesClusterActionWorker.ETCD_NODE_CLIENT_REQUEST_PORT,
                            KubernetesClusterActionWorker.ETCD_NODE_CLIENT_REQUEST_PORT, null));
                }
            }
            NetworkOffering offering = worker.networkOfferingDao.findById(network.getNetworkOfferingId());
            if (offering == null) {
                throw new InvalidParameterValueException(String.format(
                        "Network offering for Kubernetes cluster %s cannot be found", cluster.getName()));
            }
            if (offering.isConserveMode()) {
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.API_LOAD_BALANCER, null,
                        KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT,
                        KubernetesClusterActionWorker.CLUSTER_API_PORT));
            } else {
                putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.API_PORT_FORWARD, control.get(0),
                        KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT,
                        KubernetesClusterActionWorker.CLUSTER_API_PORT));
            }
        }

        int nextStandardPort = KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT;
        int externalPortBase = KubernetesClusterActionWorker.CLUSTER_NODES_DEFAULT_START_SSH_PORT + standard.size();
        int externalIndex = 0;
        for (KubernetesClusterVmMapVO vmMap : vmMaps) {
            if (vmMap.isEtcdNode()) {
                continue;
            }
            int sourcePort = vmMap.isExternalNode() ? externalPortBase + externalIndex++ : nextStandardPort++;
            putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, vmMap,
                    sourcePort, sourcePort, KubernetesClusterActionWorker.DEFAULT_SSH_PORT));
        }
        int etcdStartPort = com.cloud.kubernetes.cluster.KubernetesClusterService.KubernetesEtcdNodeStartPort.value();
        for (int i = 0; i < etcd.size(); i++) {
            putExpected(expected, new ExpectedRule(KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD, etcd.get(i),
                    etcdStartPort + i, etcdStartPort + i, KubernetesClusterActionWorker.DEFAULT_SSH_PORT));
        }
        return expected;
    }

    private void validateVpcTierAclTopology(KubernetesCluster cluster, Network network) {
        Long networkAclId = network.getNetworkACLId();
        if (networkAclId == null) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s VPC tier does not have a network ACL attached", cluster.getName()));
        }
        if (Objects.equals(networkAclId, NetworkACL.DEFAULT_DENY)) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s VPC tier uses the default-deny ACL and cannot have cluster-owned ACL rules",
                    cluster.getName()));
        }
        if (Objects.equals(networkAclId, NetworkACL.DEFAULT_ALLOW)) {
            return;
        }
        NetworkACLVO acl = worker.networkACLDao.findById(networkAclId);
        if (acl == null || !Objects.equals(acl.getVpcId(), network.getVpcId())) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s VPC tier has an invalid network ACL", cluster.getName()));
        }
        if (worker.networkDao.listByAclId(networkAclId).stream()
                .anyMatch(attached -> attached.getId() != network.getId())) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s VPC tier network ACL is shared by another tier",
                    cluster.getName()));
        }
    }

    private void putExpected(Map<String, ExpectedRule> expected, ExpectedRule rule) {
        if (expected.put(rule.logicalRole, rule) != null) {
            throw new CloudRuntimeException(String.format("Duplicate expected Kubernetes network-rule role %s", rule.logicalRole));
        }
    }

    private Map<String, RequestedRule> resolveRequestedRules(KubernetesClusterVO cluster,
            List<KubernetesClusterNetworkRuleAdoptionSpec> specs,
            Map<Long, KubernetesClusterVmMapVO> vmMapsById, Map<String, ExpectedRule> expectedRules) {
        Map<String, RequestedRule> requested = new LinkedHashMap<>();
        for (KubernetesClusterNetworkRuleAdoptionSpec spec : specs) {
            KubernetesClusterVmMapVO vmMap = null;
            if (spec.getVirtualMachineUuid() != null) {
                VMInstanceVO vm = worker.vmInstanceDao.findByUuid(spec.getVirtualMachineUuid());
                if (vm == null || (vmMap = vmMapsById.get(vm.getId())) == null) {
                    throw new InvalidParameterValueException(String.format(
                            "VM %s is not mapped to Kubernetes cluster %s", spec.getVirtualMachineUuid(), cluster.getName()));
                }
                validateVmScopedRole(spec.getRole(), vmMap);
            }
            String logicalRole = spec.getRole().toLogicalRole(vmMap == null ? null : vmMap.getVmId());
            ExpectedRule expected = expectedRules.get(logicalRole);
            if (expected == null) {
                throw new InvalidParameterValueException(String.format(
                        "Role %s is not expected for Kubernetes cluster %s", logicalRole, cluster.getName()));
            }
            RequestedRule value = new RequestedRule(spec, expected);
            if (requested.put(logicalRole, value) != null) {
                throw new InvalidParameterValueException(String.format("Duplicate declaration for role %s", logicalRole));
            }
        }
        return requested;
    }

    private void validateVmScopedRole(KubernetesClusterNetworkRuleRole role, KubernetesClusterVmMapVO vmMap) {
        if (KubernetesClusterNetworkRuleRole.ETCD_SSH_FIREWALL.equals(role) && !vmMap.isEtcdNode()) {
            throw new InvalidParameterValueException("ETCD_SSH_FIREWALL requires an etcd-node VM");
        }
        if (KubernetesClusterNetworkRuleRole.EXTERNAL_SSH_FIREWALL.equals(role) && !vmMap.isExternalNode()) {
            throw new InvalidParameterValueException("EXTERNAL_SSH_FIREWALL requires an external-node VM");
        }
    }

    private IpAddress getPublicIp(Network network) {
        try {
            IpAddress publicIp = worker.getPublicIp(network);
            if (publicIp.getAllocatedToAccountId() == null
                    || publicIp.getAllocatedToAccountId() != worker.kubernetesCluster.getAccountId()
                    || publicIp.getAllocatedInDomainId() == null
                    || publicIp.getAllocatedInDomainId() != worker.kubernetesCluster.getDomainId()
                    || !Objects.equals(publicIp.getAssociatedWithNetworkId(), network.getId())) {
                throw new InvalidParameterValueException(String.format(
                        "Public IP %s does not belong to Kubernetes cluster %s network and owner",
                        publicIp.getUuid(), worker.kubernetesCluster.getName()));
            }
            return publicIp;
        } catch (ManagementServerException e) {
            throw new InvalidParameterValueException(e.getMessage());
        }
    }

    private void lockReferencedResources(List<RequestedRule> presentRules) {
        List<Long> firewallIds = new ArrayList<>();
        List<Long> aclItemIds = new ArrayList<>();
        for (RequestedRule requested : presentRules) {
            Long id = resolveResourceId(requested.spec);
            requested.resourceId = id;
            if (ResourceType.NETWORK_ACL_ITEM.equals(requested.spec.getResourceType())) {
                aclItemIds.add(id);
            } else {
                firewallIds.add(id);
            }
        }
        firewallIds.stream().sorted().forEach(id -> {
            if (worker.firewallRulesDao.lockRow(id, true) == null) {
                throw new InvalidParameterValueException(String.format("Network rule %d no longer exists", id));
            }
        });
        aclItemIds.stream().sorted().forEach(id -> {
            if (worker.networkACLItemDao.lockRow(id, true) == null) {
                throw new InvalidParameterValueException(String.format("Network ACL item %d no longer exists", id));
            }
        });
    }

    private Long resolveResourceId(KubernetesClusterNetworkRuleAdoptionSpec spec) {
        switch (spec.getResourceType()) {
            case FIREWALL:
                return requireResource(worker.firewallRulesDao.findByUuid(spec.getResourceUuid()), spec).getId();
            case PORT_FORWARDING:
                return requireResource(worker.portForwardingRulesDao.findByUuid(spec.getResourceUuid()), spec).getId();
            case LOAD_BALANCER:
                return requireResource(worker.loadBalancerDao.findByUuid(spec.getResourceUuid()), spec).getId();
            case NETWORK_ACL_ITEM:
                return requireResource(worker.networkACLItemDao.findByUuid(spec.getResourceUuid()), spec).getId();
            default:
                throw new InvalidParameterValueException(String.format("Unsupported resource type %s", spec.getResourceType()));
        }
    }

    private <T> T requireResource(T resource, KubernetesClusterNetworkRuleAdoptionSpec spec) {
        if (resource == null) {
            throw new InvalidParameterValueException(String.format("%s resource %s cannot be found",
                    spec.getResourceType(), spec.getResourceUuid()));
        }
        return resource;
    }

    private void validateNoDuplicateResources(List<RequestedRule> presentRules) {
        Set<String> resourceKeys = new HashSet<>();
        for (RequestedRule request : presentRules) {
            String key = request.spec.getResourceType() + ":" + request.resourceId;
            if (!resourceKeys.add(key)) {
                throw new InvalidParameterValueException(String.format(
                        "Resource %s is declared for more than one network-rule role", request.spec.getResourceUuid()));
            }
        }
    }

    private void validateAndAlignDynamicSshRules(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            Map<String, ExpectedRule> expectedRules, Map<String, RequestedRule> requestedRules) {
        List<ExpectedRule> dynamicSshRules = expectedRules.values().stream()
                .filter(this::isDynamicSshPortForwardingRole)
                .collect(Collectors.toList());
        if (dynamicSshRules.isEmpty()) {
            return;
        }
        List<PortForwardingRuleVO> nonRevokedPortForwardingRules =
                worker.portForwardingRulesDao.listByIpAndNotRevoked(publicIp.getId());
        for (ExpectedRule expected : dynamicSshRules) {
            List<PortForwardingRuleVO> candidates = nonRevokedPortForwardingRules.stream()
                    .filter(rule -> isSshPortForwardingCandidate(cluster, network, publicIp, expected, rule))
                    .collect(Collectors.toList());
            if (candidates.size() > 1) {
                throw new InvalidParameterValueException(String.format(
                        "More than one non-revoked SSH port-forwarding rule targets VM %s for role %s",
                        expected.vmMap.getVmId(), expected.logicalRole));
            }
            RequestedRule requested = requestedRules.get(expected.logicalRole);
            if (requested == null) {
                if (!candidates.isEmpty()) {
                    throw new InvalidParameterValueException(String.format(
                            "Role %s was omitted but non-revoked resource %s targets its mapped VM",
                            expected.logicalRole, candidates.get(0).getUuid()));
                }
                continue;
            }
            if (candidates.isEmpty() || !Objects.equals(candidates.get(0).getId(), requested.resourceId)) {
                invalidResource(requested.spec.getResourceUuid(), expected,
                        "resource must be the only non-revoked SSH port-forwarding rule targeting the mapped VM");
            }
            PortForwardingRuleVO rule = candidates.get(0);
            Integer sourcePort = rule.getSourcePortStart();
            if (sourcePort == null || sourcePort <= 0 || sourcePort > NetUtils.PORT_RANGE_MAX
                    || !sourcePort.equals(rule.getSourcePortEnd())) {
                invalidResource(rule.getUuid(), expected, "SSH source port must be one valid port");
            }
            expected.setSourcePorts(sourcePort, sourcePort);
            KubernetesClusterNetworkRuleRole firewallRole = expected.vmMap.isEtcdNode()
                    ? KubernetesClusterNetworkRuleRole.ETCD_SSH_FIREWALL
                    : KubernetesClusterNetworkRuleRole.EXTERNAL_SSH_FIREWALL;
            ExpectedRule firewall = expectedRules.get(firewallRole.toLogicalRole(expected.vmMap.getVmId()));
            if (firewall != null) {
                firewall.setSourcePorts(sourcePort, sourcePort);
            }
        }
        for (RequestedRule requested : requestedRules.values()) {
            if (!isDynamicSshFirewallRole(requested.expected)) {
                continue;
            }
            String portForwardingRole = KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD
                    .toLogicalRole(requested.expected.vmMap.getVmId());
            if (!requestedRules.containsKey(portForwardingRole)) {
                throw new InvalidParameterValueException(String.format(
                        "%s for VM %s requires the corresponding SSH_PORT_FORWARD declaration",
                        requested.expected.role, requested.expected.vmMap.getVmId()));
            }
        }
    }

    private boolean isDynamicSshPortForwardingRole(ExpectedRule expected) {
        return KubernetesClusterNetworkRuleRole.SSH_PORT_FORWARD.equals(expected.role)
                && expected.vmMap != null && (expected.vmMap.isEtcdNode() || expected.vmMap.isExternalNode());
    }

    private boolean isDynamicSshFirewallRole(ExpectedRule expected) {
        return KubernetesClusterNetworkRuleRole.ETCD_SSH_FIREWALL.equals(expected.role)
                || KubernetesClusterNetworkRuleRole.EXTERNAL_SSH_FIREWALL.equals(expected.role);
    }

    private boolean isSshPortForwardingCandidate(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected, PortForwardingRuleVO rule) {
        return !FirewallRule.State.Revoke.equals(rule.getState())
                && FirewallRule.Purpose.PortForwarding.equals(rule.getPurpose())
                && Objects.equals(rule.getSourceIpAddressId(), publicIp.getId())
                && Objects.equals(rule.getNetworkId(), network.getId())
                && rule.getAccountId() == cluster.getAccountId()
                && rule.getDomainId() == cluster.getDomainId()
                && NetUtils.TCP_PROTO.equalsIgnoreCase(rule.getProtocol())
                && Objects.equals(rule.getVirtualMachineId(), expected.vmMap.getVmId())
                && Objects.equals(rule.getDestinationPortStart(), KubernetesClusterActionWorker.DEFAULT_SSH_PORT)
                && Objects.equals(rule.getDestinationPortEnd(), KubernetesClusterActionWorker.DEFAULT_SSH_PORT);
    }

    private void validatePresentResource(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected, RequestedRule request) {
        switch (request.spec.getResourceType()) {
            case FIREWALL:
                validateFirewallRule(cluster, network, publicIp, expected,
                        requireResource(worker.firewallRulesDao.findById(request.resourceId), request.spec));
                break;
            case PORT_FORWARDING:
                validatePortForwardingRule(cluster, network, publicIp, expected,
                        requireResource(worker.portForwardingRulesDao.findById(request.resourceId), request.spec));
                break;
            case LOAD_BALANCER:
                validateLoadBalancerRule(cluster, network, publicIp,
                        requireResource(worker.loadBalancerDao.findById(request.resourceId), request.spec));
                break;
            case NETWORK_ACL_ITEM:
                validateNetworkAclItem(cluster, network, expected,
                        requireResource(worker.networkACLItemDao.findById(request.resourceId), request.spec));
                break;
            default:
                throw new InvalidParameterValueException(String.format("Unsupported resource type %s", request.spec.getResourceType()));
        }
        validateResourceIsUnownedOrExact(cluster, request);
    }

    private void validateFirewallRule(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected, FirewallRuleVO rule) {
        validateBaseFirewallRule(cluster, network, publicIp, expected, rule, FirewallRule.Purpose.Firewall);
        if (!FirewallRule.TrafficType.Ingress.equals(rule.getTrafficType())) {
            invalidResource(rule.getUuid(), expected, "traffic type must be Ingress");
        }
        if (!worker.hasExactSourceCidrs(rule, NetUtils.ALL_IP4_CIDRS)) {
            invalidResource(rule.getUuid(), expected, "source CIDR must be exactly 0.0.0.0/0");
        }
    }

    private void validatePortForwardingRule(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected, PortForwardingRuleVO rule) {
        validateBaseFirewallRule(cluster, network, publicIp, expected, rule, FirewallRule.Purpose.PortForwarding);
        if (!Objects.equals(rule.getDestinationPortStart(), expected.destinationPort)
                || !Objects.equals(rule.getDestinationPortEnd(), expected.destinationPort)
                || !Objects.equals(rule.getVirtualMachineId(), expected.vmMap.getVmId())) {
            invalidResource(rule.getUuid(), expected, "destination port or VM does not match");
        }
        Nic nic = worker.networkModel.getNicInNetwork(expected.vmMap.getVmId(), network.getId());
        if (nic == null || !Objects.equals(rule.getDestinationIpAddress(), new Ip(nic.getIPv4Address()))) {
            invalidResource(rule.getUuid(), expected, "destination IP is not the mapped VM network address");
        }
    }

    private void validateLoadBalancerRule(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            LoadBalancerVO rule) {
        ExpectedRule expected = new ExpectedRule(KubernetesClusterNetworkRuleRole.API_LOAD_BALANCER, null,
                KubernetesClusterActionWorker.CLUSTER_API_PORT, KubernetesClusterActionWorker.CLUSTER_API_PORT,
                KubernetesClusterActionWorker.CLUSTER_API_PORT);
        validateBaseFirewallRule(cluster, network, publicIp, expected, rule, FirewallRule.Purpose.LoadBalancing);
        if (!Objects.equals(rule.getDefaultPortStart(), expected.destinationPort)
                || !Objects.equals(rule.getDefaultPortEnd(), expected.destinationPort)
                || !"api-lb".equals(rule.getName()) || !"roundrobin".equalsIgnoreCase(rule.getAlgorithm())
                || !NetUtils.TCP_PROTO.equalsIgnoreCase(rule.getLbProtocol())) {
            invalidResource(rule.getUuid(), expected, "load-balancer policy does not match the managed API rule");
        }
        Map<Long, String> expectedBackends = new HashMap<>();
        for (KubernetesClusterVmMapVO vmMap : worker.kubernetesClusterVmMapDao.listByClusterId(cluster.getId())) {
            if (!vmMap.isControlNode()) {
                continue;
            }
            Nic nic = worker.networkModel.getNicInNetwork(vmMap.getVmId(), network.getId());
            if (nic == null || expectedBackends.put(vmMap.getVmId(), nic.getIPv4Address()) != null) {
                invalidResource(rule.getUuid(), expected, "control-node backend set cannot be resolved");
            }
        }
        Map<Long, String> actualBackends = new HashMap<>();
        for (LoadBalancerVMMapVO mapping : worker.loadBalancerVMMapDao.listByLoadBalancerId(rule.getId(), false)) {
            if (mapping.isRevoke() || actualBackends.put(mapping.getInstanceId(), mapping.getInstanceIp()) != null) {
                invalidResource(rule.getUuid(), expected, "load-balancer backend set contains duplicate or revoked entries");
            }
        }
        if (!expectedBackends.equals(actualBackends)) {
            invalidResource(rule.getUuid(), expected, "load-balancer backend set does not exactly match control nodes");
        }
    }

    private void validateBaseFirewallRule(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected, FirewallRuleVO rule, FirewallRule.Purpose purpose) {
        if (!FirewallRule.State.Active.equals(rule.getState()) || !purpose.equals(rule.getPurpose())
                || !Objects.equals(rule.getSourceIpAddressId(), publicIp.getId())
                || !Objects.equals(rule.getNetworkId(), network.getId())
                || rule.getAccountId() != cluster.getAccountId() || rule.getDomainId() != cluster.getDomainId()
                || !NetUtils.TCP_PROTO.equalsIgnoreCase(rule.getProtocol())
                || !Objects.equals(rule.getSourcePortStart(), expected.sourcePortStart)
                || !Objects.equals(rule.getSourcePortEnd(), expected.sourcePortEnd)) {
            invalidResource(rule.getUuid(), expected, "scope, state, protocol, or source ports do not match");
        }
    }

    private void validateNetworkAclItem(KubernetesClusterVO cluster, Network network, ExpectedRule expected,
            NetworkACLItemVO item) {
        Long networkAclId = network.getNetworkACLId();
        if (networkAclId == null || networkAclId <= NetworkACL.DEFAULT_DENY) {
            invalidResource(item.getUuid(), expected, "cluster tier does not have a dedicated network ACL");
        }
        NetworkACLVO acl = worker.networkACLDao.findById(item.getAclId());
        if (acl == null || item.getAclId() != networkAclId || !Objects.equals(acl.getVpcId(), network.getVpcId())
                || worker.networkDao.listByAclId(networkAclId).stream().anyMatch(attached -> attached.getId() != network.getId())) {
            invalidResource(item.getUuid(), expected, "network ACL is not dedicated and attached to the cluster tier");
        }
        worker.networkACLItemDao.loadCidrs(item);
        if (!NetworkACLItem.State.Active.equals(item.getState())
                || !NetUtils.TCP_PROTO.equalsIgnoreCase(item.getProtocol())
                || !Objects.equals(item.getSourcePortStart(), expected.sourcePortStart)
                || !Objects.equals(item.getSourcePortEnd(), expected.sourcePortEnd)
                || !NetworkACLItem.TrafficType.Ingress.equals(item.getTrafficType())
                || !NetworkACLItem.Action.Allow.equals(item.getAction())
                || item.getSourceCidrList() == null || item.getSourceCidrList().size() != 2
                || !new HashSet<>(item.getSourceCidrList()).equals(Set.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS))) {
            invalidResource(item.getUuid(), expected, "state, protocol, ports, action, traffic type, or CIDRs do not match");
        }
    }

    private void validateResourceIsUnownedOrExact(KubernetesClusterVO cluster, RequestedRule request) {
        String logicalRole = request.expected.logicalRole;
        if (ResourceType.NETWORK_ACL_ITEM.equals(request.spec.getResourceType())) {
            KubernetesClusterNetworkACLItemMapVO byResource = worker.kubernetesClusterNetworkACLItemMapDao.findByNetworkAclItemId(request.resourceId);
            KubernetesClusterNetworkACLItemMapVO byRole = worker.kubernetesClusterNetworkACLItemMapDao.findByClusterIdAndLogicalRole(cluster.getId(), logicalRole);
            validateExistingOwnership(cluster, logicalRole, request.resourceId, byResource == null ? null : byResource.getClusterId(),
                    byResource == null ? null : byResource.getLogicalRole(), byRole == null ? null : byRole.getNetworkAclItemId());
        } else {
            KubernetesClusterFirewallRuleMapVO byResource = worker.kubernetesClusterFirewallRuleMapDao.findByFirewallRuleId(request.resourceId);
            KubernetesClusterFirewallRuleMapVO byRole = worker.kubernetesClusterFirewallRuleMapDao.findByClusterIdAndLogicalRole(cluster.getId(), logicalRole);
            validateExistingOwnership(cluster, logicalRole, request.resourceId, byResource == null ? null : byResource.getClusterId(),
                    byResource == null ? null : byResource.getLogicalRole(), byRole == null ? null : byRole.getFirewallRuleId());
        }
    }

    private void validateExistingOwnership(KubernetesClusterVO cluster, String logicalRole, long resourceId,
            Long ownerClusterId, String ownedRole, Long roleResourceId) {
        if (ownerClusterId != null && (ownerClusterId != cluster.getId() || !logicalRole.equals(ownedRole))) {
            throw new InvalidParameterValueException(String.format("Resource for role %s is already owned by another mapping", logicalRole));
        }
        if (roleResourceId != null && roleResourceId != resourceId) {
            throw new InvalidParameterValueException(String.format("Role %s is already mapped to another resource", logicalRole));
        }
    }

    private void validateAbsentResource(KubernetesClusterVO cluster, Network network, IpAddress publicIp,
            ExpectedRule expected) {
        List<String> conflicts = new ArrayList<>();
        switch (expected.role.getResourceType()) {
            case FIREWALL:
                for (FirewallRuleVO rule : worker.firewallRulesDao.listByIpAndPurposeAndNotRevoked(
                        publicIp.getId(), FirewallRule.Purpose.Firewall)) {
                    if (overlaps(expected, rule)) {
                        conflicts.add(rule.getUuid());
                    }
                }
                break;
            case PORT_FORWARDING:
                for (PortForwardingRuleVO rule : worker.portForwardingRulesDao.listByIpAndNotRevoked(publicIp.getId())) {
                    if (overlaps(expected, rule)) {
                        conflicts.add(rule.getUuid());
                    }
                }
                break;
            case LOAD_BALANCER:
                for (LoadBalancerVO rule : worker.loadBalancerDao.listByIpAddress(publicIp.getId())) {
                    if (!FirewallRule.State.Revoke.equals(rule.getState()) && overlaps(expected, rule)) {
                        conflicts.add(rule.getUuid());
                    }
                }
                break;
            case NETWORK_ACL_ITEM:
                if (network.getNetworkACLId() != null) {
                    for (NetworkACLItemVO item : worker.networkACLItemDao.listByACL(network.getNetworkACLId())) {
                        if (!NetworkACLItem.State.Revoke.equals(item.getState())
                                && NetUtils.TCP_PROTO.equalsIgnoreCase(item.getProtocol())
                                && rangesOverlap(expected.sourcePortStart, expected.sourcePortEnd,
                                        item.getSourcePortStart(), item.getSourcePortEnd())) {
                            conflicts.add(item.getUuid());
                        }
                    }
                }
                break;
            default:
                throw new InvalidParameterValueException(String.format("Unsupported resource type %s", expected.role.getResourceType()));
        }
        if (!conflicts.isEmpty()) {
            throw new InvalidParameterValueException(String.format(
                    "Role %s was omitted but conflicting unowned resources exist: %s",
                    expected.logicalRole, conflicts));
        }
        if (ResourceType.NETWORK_ACL_ITEM.equals(expected.role.getResourceType())) {
            if (worker.kubernetesClusterNetworkACLItemMapDao.findByClusterIdAndLogicalRole(cluster.getId(), expected.logicalRole) != null) {
                throw new InvalidParameterValueException(String.format("Role %s already has an ownership mapping", expected.logicalRole));
            }
        } else if (worker.kubernetesClusterFirewallRuleMapDao.findByClusterIdAndLogicalRole(cluster.getId(), expected.logicalRole) != null) {
            throw new InvalidParameterValueException(String.format("Role %s already has an ownership mapping", expected.logicalRole));
        }
    }

    private boolean overlaps(ExpectedRule expected, FirewallRuleVO rule) {
        return NetUtils.TCP_PROTO.equalsIgnoreCase(rule.getProtocol())
                && rangesOverlap(expected.sourcePortStart, expected.sourcePortEnd,
                        rule.getSourcePortStart(), rule.getSourcePortEnd());
    }

    private boolean rangesOverlap(Integer firstStart, Integer firstEnd, Integer secondStart, Integer secondEnd) {
        return firstStart != null && firstEnd != null && secondStart != null && secondEnd != null
                && firstStart <= secondEnd && secondStart <= firstEnd;
    }

    private void validateIdempotentReplay(KubernetesClusterVO cluster, List<RequestedRule> presentRules) {
        Map<String, Long> expectedFirewallMappings = presentRules.stream()
                .filter(request -> !ResourceType.NETWORK_ACL_ITEM.equals(request.spec.getResourceType()))
                .collect(Collectors.toMap(request -> request.expected.logicalRole, request -> request.resourceId));
        Map<String, Long> actualFirewallMappings = worker.kubernetesClusterFirewallRuleMapDao.listByClusterId(cluster.getId()).stream()
                .peek(mapping -> {
                    if (!KubernetesClusterNetworkRuleLifecycleState.ACTIVE.equals(mapping.getLifecycleState())) {
                        throw new InvalidParameterValueException(String.format("Role %s is not active", mapping.getLogicalRole()));
                    }
                })
                .collect(Collectors.toMap(KubernetesClusterFirewallRuleMapVO::getLogicalRole,
                        KubernetesClusterFirewallRuleMapVO::getFirewallRuleId));
        Map<String, Long> expectedAclMappings = presentRules.stream()
                .filter(request -> ResourceType.NETWORK_ACL_ITEM.equals(request.spec.getResourceType()))
                .collect(Collectors.toMap(request -> request.expected.logicalRole, request -> request.resourceId));
        Map<String, Long> actualAclMappings = worker.kubernetesClusterNetworkACLItemMapDao.listByClusterId(cluster.getId()).stream()
                .peek(mapping -> {
                    if (!KubernetesClusterNetworkRuleLifecycleState.ACTIVE.equals(mapping.getLifecycleState())) {
                        throw new InvalidParameterValueException(String.format("Role %s is not active", mapping.getLogicalRole()));
                    }
                })
                .collect(Collectors.toMap(KubernetesClusterNetworkACLItemMapVO::getLogicalRole,
                        KubernetesClusterNetworkACLItemMapVO::getNetworkAclItemId));
        if (!expectedFirewallMappings.equals(actualFirewallMappings) || !expectedAclMappings.equals(actualAclMappings)) {
            throw new InvalidParameterValueException(String.format(
                    "Kubernetes cluster %s is already managed and the supplied manifest is not an exact replay", cluster.getName()));
        }
    }

    private void invalidResource(String resourceUuid, ExpectedRule expected, String detail) {
        throw new InvalidParameterValueException(String.format(
                "Resource %s does not match Kubernetes network-rule role %s: %s",
                resourceUuid, expected.logicalRole, detail));
    }

    private static final class ExpectedRule {
        private final KubernetesClusterNetworkRuleRole role;
        private final KubernetesClusterVmMapVO vmMap;
        private final String logicalRole;
        private Integer sourcePortStart;
        private Integer sourcePortEnd;
        private final Integer destinationPort;

        private ExpectedRule(KubernetesClusterNetworkRuleRole role, KubernetesClusterVmMapVO vmMap,
                Integer sourcePortStart, Integer sourcePortEnd, Integer destinationPort) {
            this.role = role;
            this.vmMap = vmMap;
            this.logicalRole = role.toLogicalRole(role.isVmScoped() && vmMap != null ? vmMap.getVmId() : null);
            this.sourcePortStart = sourcePortStart;
            this.sourcePortEnd = sourcePortEnd;
            this.destinationPort = destinationPort;
        }

        private void setSourcePorts(Integer sourcePortStart, Integer sourcePortEnd) {
            this.sourcePortStart = sourcePortStart;
            this.sourcePortEnd = sourcePortEnd;
        }
    }

    private static final class RequestedRule {
        private final KubernetesClusterNetworkRuleAdoptionSpec spec;
        private final ExpectedRule expected;
        private Long resourceId;

        private RequestedRule(KubernetesClusterNetworkRuleAdoptionSpec spec, ExpectedRule expected) {
            this.spec = spec;
            this.expected = expected;
        }
    }
}
