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

import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.CONTROL;
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.ETCD;
import static com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType.WORKER;
import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.cloud.cpu.CPU;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper.KubernetesClusterNodeType;
import com.cloud.network.rules.FirewallManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.utils.db.Transaction;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkACLCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterFirewallRuleMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkACLItemMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleLifecycleState;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleRole;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.lb.LoadBalancingRulesService;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.NetworkACLItemDao;
import com.cloud.network.vpc.NetworkACLItemVO;
import com.cloud.network.vpc.NetworkACLService;
import com.cloud.network.vpc.NetworkACLVO;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.LaunchPermissionDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.utils.ssh.SshHelper;
import com.cloud.vm.Nic;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.Level;

public class KubernetesClusterResourceModifierActionWorker extends KubernetesClusterActionWorker {

    @Inject
    protected CapacityManager capacityManager;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected NetworkACLService networkACLService;
    @Inject
    protected  NetworkACLItemDao networkACLItemDao;
    @Inject
    protected NetworkACLDao networkACLDao;
    @Inject
    protected LoadBalancingRulesService lbService;
    @Inject
    protected FirewallManager firewallManager;
    @Inject
    protected ResourceManager resourceManager;
    @Inject
    protected DedicatedResourceDao dedicatedResourceDao;
    @Inject
    protected LoadBalancerDao loadBalancerDao;
    @Inject
    protected LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Inject
    protected UserVmManager userVmManager;
    @Inject
    protected LaunchPermissionDao launchPermissionDao;
    @Inject
    protected VolumeApiService volumeService;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected NetworkOfferingDao networkOfferingDao;

    protected String kubernetesClusterNodeNamePrefix;

    private static final int MAX_CLUSTER_PREFIX_LENGTH = 43;

    protected KubernetesClusterResourceModifierActionWorker(final KubernetesCluster kubernetesCluster, final KubernetesClusterManagerImpl clusterManager) {
        super(kubernetesCluster, clusterManager);
    }

    protected void init() {
        super.init();
        kubernetesClusterNodeNamePrefix = getKubernetesClusterNodeNamePrefix();
    }

    protected List<HostVO> filterHostsByAffinityConstraints(List<HostVO> hosts, AffinityConstraints constraints, DataCenter zone)
            throws InsufficientServerCapacityException {
        if (constraints.hasHostAffinity && Objects.nonNull(constraints.requiredHostId)) {
            hosts = hosts.stream().filter(host -> host.getId() == constraints.requiredHostId.longValue()).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(hosts)) {
                String msg = String.format("Cannot find capacity for Kubernetes cluster: host affinity requires all VMs on host %d but it is not available in zone %s",
                        constraints.requiredHostId, zone.getName());
                throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
            }
        }

        if (constraints.hasHostAntiAffinity) {
            hosts = hosts.stream().filter(host -> !constraints.antiAffinityOccupiedHosts.contains(host.getId())).collect(Collectors.toList());
            if (CollectionUtils.isEmpty(hosts)) {
                String msg = String.format("Cannot find capacity for Kubernetes cluster: host anti-affinity requires each VM on a separate host, " +
                                           "but all %d available hosts in zone %s are already occupied by existing cluster VMs",
                        constraints.antiAffinityOccupiedHosts.size(), zone.getName());
                throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
            }
        }

        return hosts;
    }

    protected DeployDestination plan(final long nodesCount, final DataCenter zone, final ServiceOffering offering,
            final Long domainId, final Long accountId, final Hypervisor.HypervisorType hypervisorType,
            CPU.CPUArch arch, KubernetesClusterNodeType nodeType) throws InsufficientServerCapacityException {
        final int cpu_requested = offering.getCpu() * offering.getSpeed();
        final long ram_requested = offering.getRamSize() * 1024L * 1024L;
        boolean useDedicatedHosts = false;
        List<HostVO> hosts = new ArrayList<>();
        Long group = getExplicitAffinityGroup(domainId, accountId);
        if (Objects.nonNull(group)) {
            List<DedicatedResourceVO> dedicatedHosts = new ArrayList<>();
            if (Objects.nonNull(accountId)) {
                dedicatedHosts = dedicatedResourceDao.listByAccountId(accountId);
            } else if (Objects.nonNull(domainId)) {
                dedicatedHosts = dedicatedResourceDao.listByDomainId(domainId);
            }
            for (DedicatedResourceVO dedicatedResource : dedicatedHosts) {
                hosts.addAll(manager.getHostsForDedicatedResource(dedicatedResource, zone));
                useDedicatedHosts = true;
            }
        }
        if (hosts.isEmpty()) {
            hosts = resourceManager.listAllHostsInOneZoneByType(Host.Type.Routing, zone.getId());
        }
        if (Objects.nonNull(hypervisorType)) {
            hosts = hosts.stream().filter(x -> x.getHypervisorType() == hypervisorType).collect(Collectors.toList());
        }
        if (Objects.nonNull(arch)) {
            hosts = hosts.stream().filter(x -> x.getArch().equals(arch)).collect(Collectors.toList());
        }
        if (CollectionUtils.isEmpty(hosts)) {
            String msg = String.format("Cannot find enough capacity for Kubernetes cluster(requested cpu=%d memory=%s) with offering: %s hypervisor: %s and arch: %s",
                    cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName(), clusterTemplate.getHypervisorType().toString(),
                    Objects.nonNull(arch) ? arch.getType() : "null");
            logAndThrow(Level.WARN, msg, new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId()));
        }

        AffinityConstraints affinityConstraints = resolveAffinityConstraints(nodeType, domainId, accountId);
        hosts = filterHostsByAffinityConstraints(hosts, affinityConstraints, zone);

        final Map<String, Pair<HostVO, Integer>> hosts_with_resevered_capacity = new ConcurrentHashMap<String, Pair<HostVO, Integer>>();
        for (HostVO h : hosts) {
            hosts_with_resevered_capacity.put(h.getUuid(), new Pair<HostVO, Integer>(h, 0));
        }
        boolean suitable_host_found = false;
        HostVO suitableHost = null;
        for (int i = 1; i <= nodesCount; i++) {
            suitable_host_found = false;
            for (Map.Entry<String, Pair<HostVO, Integer>> hostEntry : hosts_with_resevered_capacity.entrySet()) {
                Pair<HostVO, Integer> hp = hostEntry.getValue();
                HostVO h = hp.first();
                if (!h.getHypervisorType().equals(clusterTemplate.getHypervisorType()) || !h.getArch().equals(clusterTemplate.getArch())) {
                    continue;
                }
                hostDao.loadHostTags(h);
                if (StringUtils.isNotEmpty(offering.getHostTag()) && !(h.getHostTags() != null && h.getHostTags().contains(offering.getHostTag()))) {
                    continue;
                }
                int reserved = hp.second();
                if (affinityConstraints.hasHostAntiAffinity && reserved > 0) {
                    continue;
                }
                reserved++;
                ClusterVO cluster = clusterDao.findById(h.getClusterId());
                ClusterDetailsVO cluster_detail_cpu = clusterDetailsDao.findDetail(cluster.getId(), "cpuOvercommitRatio");
                ClusterDetailsVO cluster_detail_ram = clusterDetailsDao.findDetail(cluster.getId(), "memoryOvercommitRatio");
                Float cpuOvercommitRatio = Float.parseFloat(cluster_detail_cpu.getValue());
                Float memoryOvercommitRatio = Float.parseFloat(cluster_detail_ram.getValue());
                if (logger.isDebugEnabled()) {
                    logger.debug(String.format("Checking host : %s for capacity already reserved %d", h.getName(), reserved));
                }
                if (capacityManager.checkIfHostHasCapacity(h, cpu_requested * reserved, ram_requested * reserved, false, cpuOvercommitRatio, memoryOvercommitRatio, true)) {
                    logger.debug("Found host {} with enough capacity: CPU={} RAM={}", h.getName(), cpu_requested * reserved, toHumanReadableSize(ram_requested * reserved));
                    hostEntry.setValue(new Pair<HostVO, Integer>(h, reserved));
                    suitable_host_found = true;
                    suitableHost = h;
                    break;
                }
            }
            if (!suitable_host_found) {
                if (logger.isInfoEnabled()) {
                    logger.info("Suitable hosts not found in datacenter: {} for node {}, with offering: {} and hypervisor: {}",
                            zone, i, offering, clusterTemplate.getHypervisorType().toString());
                }
                break;
            }
        }
        if (suitable_host_found) {
            if (logger.isInfoEnabled()) {
                logger.info("Suitable hosts found in datacenter: {}, creating deployment destination", zone);
            }
            if (useDedicatedHosts) {
                return new DeployDestination(zone, null, null, suitableHost);
            }
            return new DeployDestination(zone, null, null, null);
        }
        String msg;
        if (affinityConstraints.hasHostAntiAffinity) {
            msg = String.format("Cannot find enough capacity for Kubernetes cluster (requested cpu=%d memory=%s) with offering: %s. " +
                                "Host anti-affinity requires %d separate hosts but not enough suitable hosts are available in zone %s",
                    cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName(),
                    nodesCount, zone.getName());
        } else {
            msg = String.format("Cannot find enough capacity for Kubernetes cluster(requested cpu=%d memory=%s) with offering: %s hypervisor: %s and arch: %s",
                    cpu_requested * nodesCount, toHumanReadableSize(ram_requested * nodesCount), offering.getName(), clusterTemplate.getHypervisorType().toString(),
                    Objects.nonNull(arch) ? arch.getType() : "null");
        }
        throw new InsufficientServerCapacityException(msg, DataCenter.class, zone.getId());
    }

    /**
     * Plan Kubernetes Cluster Deployment
     * @return a map of DeployDestination per node type
     */
    protected Map<String, DeployDestination> planKubernetesCluster(Long domainId, Long accountId, Hypervisor.HypervisorType hypervisorType, CPU.CPUArch arch) throws InsufficientServerCapacityException {
        Map<String, DeployDestination> destinationMap = new HashMap<>();
        DataCenter zone = dataCenterDao.findById(kubernetesCluster.getZoneId());
        if (logger.isDebugEnabled()) {
            logger.debug("Checking deployment destination for Kubernetes cluster: {} in zone: {}", kubernetesCluster, zone);
        }
        long controlNodeCount = kubernetesCluster.getControlNodeCount();
        long clusterSize = kubernetesCluster.getNodeCount();
        long etcdNodes = kubernetesCluster.getEtcdNodeCount();
        Map<String, Long> nodeTypeCount = Map.of(WORKER.name(), clusterSize,
                CONTROL.name(), controlNodeCount, ETCD.name(), etcdNodes);

        for (KubernetesClusterNodeType nodeType : CLUSTER_NODES_TYPES_LIST) {
            Long nodes = nodeTypeCount.getOrDefault(nodeType.name(), kubernetesCluster.getServiceOfferingId());
            if (nodes == null || nodes == 0) {
                continue;
            }
            ServiceOffering nodeOffering = getServiceOfferingForNodeTypeOnCluster(nodeType, kubernetesCluster);
            if (logger.isDebugEnabled()) {
                logger.debug("Checking deployment destination for {} nodes on Kubernetes cluster : {} in zone : {}", nodeType.name(), kubernetesCluster.getName(), zone.getName());
            }
            DeployDestination planForNodeType = plan(nodes, zone, nodeOffering, domainId, accountId, hypervisorType, arch, nodeType);
            destinationMap.put(nodeType.name(), planForNodeType);
        }
        return destinationMap;
    }

    protected void resizeNodeVolume(final UserVm vm) throws ManagementServerException {
        try {
            if (vm.getHypervisorType() == Hypervisor.HypervisorType.VMware && templateDao.findById(vm.getTemplateId()).isDeployAsIs()) {
                List<VolumeVO> vmVols = volumeDao.findByInstance(vm.getId());
                for (VolumeVO volumeVO : vmVols) {
                    if (volumeVO.getVolumeType() == Volume.Type.ROOT) {
                        ResizeVolumeCmd resizeVolumeCmd = new ResizeVolumeCmd();
                        resizeVolumeCmd = ComponentContext.inject(resizeVolumeCmd);
                        resizeVolumeCmd.setSize(kubernetesCluster.getNodeRootDiskSize());
                        resizeVolumeCmd.setId(volumeVO.getId());

                        volumeService.resizeVolume(resizeVolumeCmd);
                    }
                }
            }
        } catch (ResourceAllocationException e) {
            throw new ManagementServerException(String.format("Failed to resize volume of  VM in the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        }
    }

    protected void startKubernetesVM(final UserVm vm, final Long domainId, final Long accountId, KubernetesClusterNodeType nodeType) throws ManagementServerException {
        CallContext vmContext = null;
        if (!ApiCommandResourceType.VirtualMachine.equals(CallContext.current().getEventResourceType())); {
            vmContext = CallContext.register(CallContext.current(), ApiCommandResourceType.VirtualMachine);
            vmContext.setEventResourceId(vm.getId());
        }
        DeploymentPlan plan = null;
        if (Objects.nonNull(domainId) && !listDedicatedHostsInDomain(domainId).isEmpty()) {
            DeployDestination dest = null;
            try {
                Map<String, DeployDestination> destinationMap = planKubernetesCluster(domainId, accountId, vm.getHypervisorType(), clusterTemplate.getArch());
                dest = destinationMap.get(nodeType.name());
            } catch (InsufficientCapacityException e) {
                logTransitStateAndThrow(Level.ERROR, String.format("Provisioning the cluster failed due to insufficient capacity in the Kubernetes cluster: %s", kubernetesCluster.getUuid()), kubernetesCluster.getId(), KubernetesCluster.Event.CreateFailed, e);
            }
            if (dest != null) {
                plan = new DataCenterDeployment(
                        Objects.nonNull(dest.getDataCenter()) ? dest.getDataCenter().getId() : 0,
                        Objects.nonNull(dest.getPod()) ? dest.getPod().getId() : null,
                        Objects.nonNull(dest.getCluster()) ? dest.getCluster().getId() : null,
                        Objects.nonNull(dest.getHost()) ? dest.getHost().getId() : null,
                        null,
                        null);
            }
        }
        try {
            userVmManager.startVirtualMachine(vm, plan);
        } catch (OperationTimedoutException | ResourceUnavailableException | InsufficientCapacityException ex) {
            throw new ManagementServerException(String.format("Failed to start VM in the Kubernetes cluster : %s", kubernetesCluster.getName()), ex);
        } finally {
            if (vmContext != null) {
                CallContext.unregister();
            }
        }

        UserVm startVm = userVmDao.findById(vm.getId());
        if (!startVm.getState().equals(VirtualMachine.State.Running)) {
            throw new ManagementServerException(String.format("Failed to start VM in the Kubernetes cluster : %s", kubernetesCluster.getName()));
        }
    }

    protected List<UserVm> provisionKubernetesClusterNodeVms(final long nodeCount, final int offset,
            final String controlIpAddress, final Long domainId, final Long accountId) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        List<UserVm> nodes = new ArrayList<>();
        for (int i = offset + 1; i <= nodeCount; i++) {
            CallContext vmContext = CallContext.register(CallContext.current(), ApiCommandResourceType.VirtualMachine);
            try {
                UserVm vm = createKubernetesNode(controlIpAddress, domainId, accountId);
                vmContext.setEventResourceId(vm.getId());
                addKubernetesClusterVm(kubernetesCluster.getId(), vm.getId(), false, false, false, false);
                if (kubernetesCluster.getNodeRootDiskSize() > 0) {
                    resizeNodeVolume(vm);
                }
                startKubernetesVM(vm, domainId, accountId, WORKER);
                vm = userVmDao.findById(vm.getId());
                if (vm == null) {
                    throw new ManagementServerException(String.format("Failed to provision worker VM for Kubernetes cluster : %s", kubernetesCluster.getName()));
                }
                nodes.add(vm);
                logger.info("Provisioned node VM: {} in to the Kubernetes cluster: {}", vm, kubernetesCluster);
            } finally {
                CallContext.unregister();
            }
        }
        return nodes;
    }

    protected List<UserVm> provisionKubernetesClusterNodeVms(final long nodeCount, final String controlIpAddress, final Long domainId, final Long accountId) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        return provisionKubernetesClusterNodeVms(nodeCount, 0, controlIpAddress, domainId, accountId);
    }

    protected UserVm createKubernetesNode(String joinIp, Long domainId, Long accountId) throws ManagementServerException,
            ResourceUnavailableException, InsufficientCapacityException {
        UserVm nodeVm = null;
        DataCenter zone = dataCenterDao.findById(kubernetesCluster.getZoneId());
        ServiceOffering serviceOffering = getServiceOfferingForNodeTypeOnCluster(WORKER, kubernetesCluster);
        List<Long> networkIds = new ArrayList<Long>();
        networkIds.add(kubernetesCluster.getNetworkId());
        Account owner = accountDao.findById(kubernetesCluster.getAccountId());
        Network.IpAddresses addrs = new Network.IpAddresses(null, null);
        long rootDiskSize = kubernetesCluster.getNodeRootDiskSize();
        Map<String, String> customParameterMap = new HashMap<String, String>();
        if (rootDiskSize > 0) {
            customParameterMap.put("rootdisksize", String.valueOf(rootDiskSize));
        }
        if (Hypervisor.HypervisorType.VMware.equals(clusterTemplate.getHypervisorType())) {
            customParameterMap.put(VmDetailConstants.ROOT_DISK_CONTROLLER, "scsi");
        }
        String suffix = Long.toHexString(System.currentTimeMillis());
        String hostName = String.format("%s-node-%s", kubernetesClusterNodeNamePrefix, suffix);
        String k8sNodeConfig = null;
        try {
            k8sNodeConfig = getKubernetesNodeConfig(joinIp, Hypervisor.HypervisorType.VMware.equals(clusterTemplate.getHypervisorType()), false);
        } catch (IOException e) {
            logAndThrow(Level.ERROR, "Failed to read Kubernetes node configuration file", e);
        }

        String base64UserData = Base64.encodeBase64String(k8sNodeConfig.getBytes(com.cloud.utils.StringUtils.getPreferredCharset()));
        List<String> keypairs = new ArrayList<String>();
        if (StringUtils.isNotBlank(kubernetesCluster.getKeyPair())) {
            keypairs.add(kubernetesCluster.getKeyPair());
        }
        List<Long> affinityGroupIds = getMergedAffinityGroupIds(WORKER, domainId, accountId);
        if (kubernetesCluster.getSecurityGroupId() != null && networkModel.checkSecurityGroupSupportForNetwork(owner, zone, networkIds, List.of(kubernetesCluster.getSecurityGroupId()))) {
            List<Long> securityGroupIds = new ArrayList<>();
            securityGroupIds.add(kubernetesCluster.getSecurityGroupId());
            nodeVm = userVmService.createAdvancedSecurityGroupVirtualMachine(zone, serviceOffering, workerNodeTemplate, networkIds, securityGroupIds, owner,
                    hostName, hostName, null, null, null, null, Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST,base64UserData, null, null, keypairs,
                    null, addrs, null, null, affinityGroupIds, customParameterMap, null, null, null,
                    null, true, null, null, UserVmManager.CKS_NODE, null, null);
        } else {
            nodeVm = userVmService.createAdvancedVirtualMachine(zone, serviceOffering, workerNodeTemplate, networkIds, owner,
                    hostName, hostName, null, null, null, null,
                    Hypervisor.HypervisorType.None, BaseCmd.HTTPMethod.POST, base64UserData, null, null, keypairs,
                    null, addrs, null, null, affinityGroupIds, customParameterMap,
                    null, null, null, null, true,
                    UserVmManager.CKS_NODE, null, null, null, null);
        }
        if (logger.isInfoEnabled()) {
            logger.info("Created node VM : {}, {} in the Kubernetes cluster : {}", hostName, nodeVm, kubernetesCluster.getName());
        }
        return nodeVm;
    }

    /**
     * To provision SSH port forwarding rules for the given Kubernetes cluster
     * for its given virtual machines
     *
     * @param publicIp
     * @param network
     * @param account
     * @param clusterVMIds (when empty then method must be called while
     *                     down-scaling of the KubernetesCluster therefore no new rules
     *                     to be added)
     * @throws ResourceUnavailableException
     * @throws NetworkRuleConflictException
     */
    protected void provisionSshPortForwardingRules(IpAddress publicIp, Network network, Account account,
            List<Long> clusterVMIds, Map<Long, Integer> vmIdPortMap) throws ResourceUnavailableException,
            NetworkRuleConflictException {
        if (CollectionUtils.isEmpty(clusterVMIds)) {
            return;
        }
        Map<Long, Integer> explicitPorts = vmIdPortMap == null ? Collections.emptyMap() : vmIdPortMap;
        Set<Integer> reservedPorts = new HashSet<>();
        for (Map.Entry<Long, Integer> entry : explicitPorts.entrySet()) {
            Integer port = entry.getValue();
            if (port == null || port < CLUSTER_NODES_DEFAULT_START_SSH_PORT || port > NetUtils.PORT_RANGE_MAX) {
                throw new NetworkRuleConflictException(String.format("Invalid SSH public port configured for VM %d", entry.getKey()));
            }
            if (!reservedPorts.add(port)) {
                throw new NetworkRuleConflictException(String.format("SSH public port %d is assigned to more than one VM", port));
            }
        }

        int nextDefaultPort = CLUSTER_NODES_DEFAULT_START_SSH_PORT;
        Set<Long> processedVmIds = new HashSet<>();
        for (Long vmId : clusterVMIds) {
            if (vmId == null || !processedVmIds.add(vmId)) {
                throw new NetworkRuleConflictException("Kubernetes cluster VM list contains a missing or duplicate VM id");
            }
            Integer sourcePort = explicitPorts.get(vmId);
            if (sourcePort == null) {
                while (reservedPorts.contains(nextDefaultPort)) {
                    nextDefaultPort++;
                }
                if (nextDefaultPort > NetUtils.PORT_RANGE_MAX) {
                    throw new NetworkRuleConflictException("No public SSH port is available for the Kubernetes cluster");
                }
                sourcePort = nextDefaultPort++;
            }
            provisionPublicIpPortForwardingRule(publicIp, network, account, vmId, sourcePort, DEFAULT_SSH_PORT);
        }
    }

    protected void deleteManagedLoadBalancerRule(KubernetesClusterFirewallRuleMapVO mapping, Network network)
            throws ResourceUnavailableException {
        markManagedFirewallRulePendingDelete(mapping.getFirewallRuleId());
        LoadBalancerVO rule = loadBalancerDao.findById(mapping.getFirewallRuleId());
        if (rule != null && !lbService.deleteLoadBalancerRule(rule.getId(), true)) {
            throw new ResourceUnavailableException("Failed to remove the Kubernetes cluster load balancing rule", Network.class, network.getId());
        }
        FirewallRuleVO removedRule = firewallRulesDao.findByIdIncludingRemoved(mapping.getFirewallRuleId());
        if (removedRule != null && removedRule.getRemoved() == null) {
            throw new ResourceUnavailableException("Kubernetes cluster load balancing rule removal was not confirmed", Network.class, network.getId());
        }
        forgetManagedFirewallRule(mapping);
    }

    protected void provisionVpcTierAllowPortACLRule(final Network network, int startPort, int endPorts, String logicalRole)
            throws ResourceUnavailableException, NetworkRuleConflictException {
        Network effectiveNetwork = networkDao.findById(network.getId());
        if (effectiveNetwork == null) {
            throw new ResourceUnavailableException("Kubernetes cluster network no longer exists", Network.class, network.getId());
        }
        Long networkAclId = effectiveNetwork.getNetworkACLId();
        if (networkAclId == null) {
            throw new NetworkRuleConflictException(String.format("VPC tier %s does not have a network ACL attached", effectiveNetwork.getName()));
        }
        boolean sharedAcl = networkDao.listByAclId(networkAclId).stream()
                .anyMatch(attachedNetwork -> attachedNetwork.getId() != effectiveNetwork.getId());
        if (sharedAcl) {
            throw new NetworkRuleConflictException(String.format("Network ACL %d is shared by multiple VPC tiers and cannot contain Kubernetes cluster-owned rules",
                    networkAclId));
        }
        List<NetworkACLItemVO> aclItems = networkACLItemDao.listByACL(networkAclId);
        aclItems = aclItems.stream().filter(networkACLItem -> !NetworkACLItem.State.Revoke.equals(networkACLItem.getState())).collect(Collectors.toList());
        KubernetesClusterNetworkACLItemMapVO existingMapping = kubernetesClusterNetworkACLItemMapDao.findByClusterIdAndLogicalRole(
                kubernetesCluster.getId(), logicalRole);
        if (existingMapping != null) {
            NetworkACLItemVO ownedRule = networkACLItemDao.findById(existingMapping.getNetworkAclItemId());
            boolean desiredRule = ownedRule != null && !NetworkACLItem.State.Revoke.equals(ownedRule.getState())
                    && Objects.equals(ownedRule.getAclId(), networkAclId)
                    && Objects.equals(ownedRule.getSourcePortStart(), startPort)
                    && Objects.equals(ownedRule.getSourcePortEnd(), endPorts)
                    && NetUtils.TCP_PROTO.equalsIgnoreCase(ownedRule.getProtocol())
                    && NetworkACLItem.TrafficType.Ingress.equals(ownedRule.getTrafficType())
                    && NetworkACLItem.Action.Allow.equals(ownedRule.getAction())
                    && ownedRule.getSourceCidrList() != null
                    && ownedRule.getSourceCidrList().size() == 2
                    && new HashSet<>(ownedRule.getSourceCidrList()).equals(Set.of(NetUtils.ALL_IP4_CIDRS, NetUtils.ALL_IP6_CIDRS));
            if (desiredRule && existingMapping.getLifecycleState() != KubernetesClusterNetworkRuleLifecycleState.PENDING_DELETE) {
                if (!networkACLService.applyNetworkACL(ownedRule.getAclId())) {
                    throw new ResourceUnavailableException("Failed to apply the Kubernetes cluster network ACL item", Network.class, network.getId());
                }
                activateManagedNetworkAclItem(ownedRule.getId());
                return;
            }
            deleteManagedNetworkAclItem(existingMapping, effectiveNetwork);
        }
        for (NetworkACLItemVO aclItem : aclItems) {
            List<String> sourceCidrs = aclItem.getSourceCidrList();
            boolean desiredRule = NetUtils.TCP_PROTO.equalsIgnoreCase(aclItem.getProtocol())
                    && Objects.equals(aclItem.getSourcePortStart(), startPort)
                    && Objects.equals(aclItem.getSourcePortEnd(), endPorts)
                    && NetworkACLItem.TrafficType.Ingress.equals(aclItem.getTrafficType())
                    && NetworkACLItem.Action.Allow.equals(aclItem.getAction())
                    && sourceCidrs != null && sourceCidrs.contains(NetUtils.ALL_IP4_CIDRS)
                    && sourceCidrs.contains(NetUtils.ALL_IP6_CIDRS);
            if (desiredRule) {
                throw new NetworkRuleConflictException(String.format("Matching network ACL item %d is not owned by Kubernetes cluster %s",
                        aclItem.getId(), kubernetesCluster.getName()));
            }
        }
        CreateNetworkACLCmd networkACLRule = new CreateNetworkACLCmd();
        networkACLRule = ComponentContext.inject(networkACLRule);

        networkACLRule.setProtocol("TCP");

        networkACLRule.setPublicStartPort(startPort);

        networkACLRule.setPublicEndPort(endPorts);

        networkACLRule.setTrafficType(NetworkACLItem.TrafficType.Ingress.toString());

        networkACLRule.setNetworkId(effectiveNetwork.getId());

        networkACLRule.setAclId(networkAclId);

        networkACLRule.setAction(NetworkACLItem.Action.Allow.toString());

        CreateNetworkACLCmd finalNetworkACLRule = networkACLRule;
        NetworkACLItem aclRule = Transaction.execute((TransactionCallback<NetworkACLItem>) status -> {
            lockClusterForNetworkRuleMutation();
            NetworkACLItem rule = networkACLService.createNetworkACLItem(finalNetworkACLRule);
            recordManagedNetworkAclItem(rule.getId(), logicalRole);
            return rule;
        });
        networkACLService.moveRuleToTheTopInACLList(aclRule);
        if (!networkACLService.applyNetworkACL(aclRule.getAclId())) {
            throw new ResourceUnavailableException("Failed to apply the Kubernetes cluster network ACL item", Network.class, effectiveNetwork.getId());
        }
        activateManagedNetworkAclItem(aclRule.getId());
    }

    protected void deleteManagedNetworkAclItem(KubernetesClusterNetworkACLItemMapVO mapping, Network network)
            throws ResourceUnavailableException {
        validateManagedNetworkAclItemMapping(mapping, network);
        markManagedNetworkAclItemPendingDelete(mapping.getNetworkAclItemId());
        NetworkACLItemVO item = networkACLItemDao.findById(mapping.getNetworkAclItemId());
        if (item != null && !networkACLService.revokeNetworkACLItem(item.getId())) {
            throw new ResourceUnavailableException("Failed to remove the Kubernetes cluster network ACL item", Network.class, network.getId());
        }
        if (networkACLItemDao.findById(mapping.getNetworkAclItemId()) != null) {
            throw new ResourceUnavailableException("Kubernetes cluster network ACL item removal was not confirmed", Network.class, network.getId());
        }
        forgetManagedNetworkAclItem(mapping);
    }

    protected void validateManagedFirewallRuleMapping(KubernetesClusterFirewallRuleMapVO mapping, Network network) {
        KubernetesClusterNetworkRuleRole role = KubernetesClusterNetworkRuleRole.fromLogicalRole(mapping.getLogicalRole());
        if (KubernetesClusterNetworkRuleRole.ResourceType.NETWORK_ACL_ITEM.equals(role.getResourceType())) {
            throw new CloudRuntimeException(String.format("Network ACL role %s is stored as firewall-rule ownership", mapping.getLogicalRole()));
        }
        role.getVmId(mapping.getLogicalRole());
        FirewallRuleVO rule = firewallRulesDao.findById(mapping.getFirewallRuleId());
        if (rule == null) {
            return;
        }
        FirewallRule.Purpose expectedPurpose;
        switch (role.getResourceType()) {
            case FIREWALL:
                expectedPurpose = FirewallRule.Purpose.Firewall;
                break;
            case PORT_FORWARDING:
                expectedPurpose = FirewallRule.Purpose.PortForwarding;
                break;
            case LOAD_BALANCER:
                expectedPurpose = FirewallRule.Purpose.LoadBalancing;
                break;
            default:
                throw new CloudRuntimeException(String.format("Unsupported firewall-rule resource type %s", role.getResourceType()));
        }
        if (!expectedPurpose.equals(rule.getPurpose())
                || !Objects.equals(rule.getNetworkId(), network.getId())
                || rule.getAccountId() != kubernetesCluster.getAccountId()
                || rule.getDomainId() != kubernetesCluster.getDomainId()) {
            throw new CloudRuntimeException(String.format(
                    "Managed rule %d for role %s does not belong to Kubernetes cluster %s; refusing deletion",
                    rule.getId(), mapping.getLogicalRole(), kubernetesCluster.getName()));
        }
    }

    protected void validateManagedNetworkAclItemMapping(KubernetesClusterNetworkACLItemMapVO mapping, Network network) {
        KubernetesClusterNetworkRuleRole role = KubernetesClusterNetworkRuleRole.fromLogicalRole(mapping.getLogicalRole());
        if (!KubernetesClusterNetworkRuleRole.ResourceType.NETWORK_ACL_ITEM.equals(role.getResourceType())) {
            throw new CloudRuntimeException(String.format("Firewall-rule role %s is stored as network ACL ownership", mapping.getLogicalRole()));
        }
        role.getVmId(mapping.getLogicalRole());
        NetworkACLItemVO item = networkACLItemDao.findById(mapping.getNetworkAclItemId());
        if (item == null) {
            return;
        }
        NetworkACLVO acl = networkACLDao.findById(item.getAclId());
        if (acl == null || !Objects.equals(acl.getVpcId(), network.getVpcId())) {
            throw new CloudRuntimeException(String.format(
                    "Managed network ACL item %d for role %s does not belong to Kubernetes cluster %s VPC; refusing deletion",
                    item.getId(), mapping.getLogicalRole(), kubernetesCluster.getName()));
        }
        if (networkDao.listByAclId(item.getAclId()).stream()
                .anyMatch(attachedNetwork -> attachedNetwork.getId() != network.getId())) {
            throw new CloudRuntimeException(String.format(
                    "Managed network ACL item %d for role %s is attached to another VPC tier; refusing deletion",
                    item.getId(), mapping.getLogicalRole()));
        }
    }

    protected void deleteManagedFirewallRuleByRole(KubernetesClusterFirewallRuleMapVO mapping, Network network)
            throws ResourceUnavailableException {
        KubernetesClusterNetworkRuleRole role = KubernetesClusterNetworkRuleRole.fromLogicalRole(mapping.getLogicalRole());
        switch (role.getResourceType()) {
            case FIREWALL:
                deleteManagedFirewallRule(mapping);
                break;
            case PORT_FORWARDING:
                deleteManagedPortForwardingRule(mapping);
                break;
            case LOAD_BALANCER:
                deleteManagedLoadBalancerRule(mapping, network);
                break;
            default:
                throw new CloudRuntimeException(String.format("Unsupported firewall-rule ownership role %s", mapping.getLogicalRole()));
        }
    }

    protected void deleteManagedNetworkRulesNotIn(Set<String> desiredRoles, Network network) throws ManagementServerException {
        List<KubernetesClusterFirewallRuleMapVO> allFirewallMappings = kubernetesClusterFirewallRuleMapDao.listByClusterId(kubernetesCluster.getId());
        List<KubernetesClusterNetworkACLItemMapVO> allAclMappings = kubernetesClusterNetworkACLItemMapDao.listByClusterId(kubernetesCluster.getId());
        allFirewallMappings.forEach(mapping -> validateManagedFirewallRuleMapping(mapping, network));
        allAclMappings.forEach(mapping -> validateManagedNetworkAclItemMapping(mapping, network));
        List<KubernetesClusterFirewallRuleMapVO> firewallMappings = allFirewallMappings.stream()
                .filter(mapping -> !desiredRoles.contains(mapping.getLogicalRole()))
                .sorted(Comparator.comparingLong(KubernetesClusterFirewallRuleMapVO::getId))
                .collect(Collectors.toList());
        List<KubernetesClusterNetworkACLItemMapVO> aclMappings = allAclMappings.stream()
                .filter(mapping -> !desiredRoles.contains(mapping.getLogicalRole()))
                .sorted(Comparator.comparingLong(KubernetesClusterNetworkACLItemMapVO::getId))
                .collect(Collectors.toList());
        try {
            for (KubernetesClusterFirewallRuleMapVO mapping : firewallMappings) {
                deleteManagedFirewallRuleByRole(mapping, network);
            }
            for (KubernetesClusterNetworkACLItemMapVO mapping : aclMappings) {
                deleteManagedNetworkAclItem(mapping, network);
            }
        } catch (ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to remove stale managed network rules for Kubernetes cluster %s",
                    kubernetesCluster.getName()), e);
        }
    }

    protected void deleteAllManagedNetworkRules(Network network) throws ManagementServerException {
        deleteManagedNetworkRulesNotIn(Collections.emptySet(), network);
    }

    protected void provisionLoadBalancerRule(final IpAddress publicIp, final Network network,
            final Account account, final List<Long> clusterVMIds, final int port) throws NetworkRuleConflictException,
            InsufficientAddressCapacityException, ResourceUnavailableException {
        LoadBalancer lb = null;
        KubernetesClusterFirewallRuleMapVO existingMapping = kubernetesClusterFirewallRuleMapDao.findByClusterIdAndLogicalRole(
                kubernetesCluster.getId(), API_LOAD_BALANCER_ROLE);
        if (existingMapping != null) {
            LoadBalancerVO ownedRule = loadBalancerDao.findById(existingMapping.getFirewallRuleId());
            boolean desiredRule = ownedRule != null && !FirewallRule.State.Revoke.equals(ownedRule.getState())
                    && Objects.equals(ownedRule.getSourceIpAddressId(), publicIp.getId())
                    && Objects.equals(ownedRule.getSourcePortStart(), port)
                    && Objects.equals(ownedRule.getSourcePortEnd(), port)
                    && Objects.equals(ownedRule.getDefaultPortStart(), port)
                    && Objects.equals(ownedRule.getDefaultPortEnd(), port)
                    && Objects.equals(ownedRule.getNetworkId(), network.getId())
                    && ownedRule.getAccountId() == account.getId()
                    && ownedRule.getDomainId() == account.getDomainId()
                    && NetUtils.TCP_PROTO.equalsIgnoreCase(ownedRule.getProtocol())
                    && NetUtils.TCP_PROTO.equalsIgnoreCase(ownedRule.getLbProtocol())
                    && "api-lb".equals(ownedRule.getName())
                    && "roundrobin".equalsIgnoreCase(ownedRule.getAlgorithm());
            if (desiredRule && existingMapping.getLifecycleState() != KubernetesClusterNetworkRuleLifecycleState.PENDING_DELETE) {
                lb = ownedRule;
            } else {
                deleteManagedLoadBalancerRule(existingMapping, network);
            }
        }
        if (lb == null) {
            for (LoadBalancerVO existingRule : loadBalancerDao.listByIpAddress(publicIp.getId())) {
                if (!FirewallRule.State.Revoke.equals(existingRule.getState())
                        && existingRule.getSourcePortStart() <= port && existingRule.getSourcePortEnd() >= port) {
                    boolean desiredRule = existingRule.getSourcePortStart() == port && existingRule.getSourcePortEnd() == port
                            && existingRule.getDefaultPortStart() == port && existingRule.getDefaultPortEnd() == port
                            && Objects.equals(existingRule.getNetworkId(), network.getId()) && existingRule.getAccountId() == account.getId()
                            && existingRule.getDomainId() == account.getDomainId()
                            && NetUtils.TCP_PROTO.equalsIgnoreCase(existingRule.getProtocol())
                            && NetUtils.TCP_PROTO.equalsIgnoreCase(existingRule.getLbProtocol())
                            && "api-lb".equals(existingRule.getName()) && "roundrobin".equalsIgnoreCase(existingRule.getAlgorithm());
                    if (desiredRule) {
                        throw new NetworkRuleConflictException(String.format("Matching load balancing rule %d is not owned by Kubernetes cluster %s",
                                existingRule.getId(), kubernetesCluster.getName()));
                    }
                    throw new NetworkRuleConflictException(String.format("A matching load balancing rule %d is not owned by Kubernetes cluster %s",
                            existingRule.getId(), kubernetesCluster.getName()));
                }
            }
        }
        if (lb == null) {
            try {
                lb = Transaction.execute((TransactionCallbackWithException<LoadBalancer, Exception>) status -> {
                    lockClusterForNetworkRuleMutation();
                    LoadBalancer created = lbService.createPublicLoadBalancerRule(null, "api-lb", "LB rule for API access",
                            port, port, port, port, publicIp.getId(), NetUtils.TCP_PROTO, "roundrobin", network.getId(),
                            account.getId(), false, NetUtils.TCP_PROTO, true);
                    recordManagedFirewallRule(created.getId(), API_LOAD_BALANCER_ROLE);
                    return created;
                });
            } catch (NetworkRuleConflictException | InsufficientAddressCapacityException e) {
                throw e;
            } catch (Exception e) {
                throw new CloudRuntimeException("Failed to create the Kubernetes cluster load balancing rule ownership atomically", e);
            }
        }

        Map<Long, List<String>> desiredVmIdIpMap = new HashMap<>();
        List<LoadBalancerVMMapVO> existingMappings = loadBalancerVMMapDao.listByLoadBalancerId(lb.getId(), false);
        Map<Long, KubernetesClusterVmMapVO> clusterVmMappings = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId()).stream()
                .collect(Collectors.toMap(KubernetesClusterVmMapVO::getVmId, vmMap -> vmMap));
        for (Long vmId : clusterVMIds) {
            KubernetesClusterVmMapVO vmMap = clusterVmMappings.get(vmId);
            if (vmMap == null || !vmMap.isControlNode()) {
                continue;
            }
            Nic controlVmNic = networkModel.getNicInNetwork(vmId, kubernetesCluster.getNetworkId());
            if (controlVmNic == null || StringUtils.isBlank(controlVmNic.getIPv4Address())) {
                throw new CloudRuntimeException(String.format(
                        "No IPv4 address was found for control node %d on Kubernetes cluster network %d",
                        vmId, kubernetesCluster.getNetworkId()));
            }
            desiredVmIdIpMap.put(vmId, List.of(controlVmNic.getIPv4Address()));
        }
        Map<Long, List<String>> staleVmIdIpMap = new HashMap<>();
        for (LoadBalancerVMMapVO existing : existingMappings) {
            List<String> desiredIps = desiredVmIdIpMap.get(existing.getInstanceId());
            if (desiredIps == null || !desiredIps.contains(existing.getInstanceIp())) {
                staleVmIdIpMap.computeIfAbsent(existing.getInstanceId(), ignored -> new ArrayList<>()).add(existing.getInstanceIp());
            }
        }
        if (!staleVmIdIpMap.isEmpty() && !lbService.removeFromLoadBalancer(lb.getId(), null, staleVmIdIpMap, false)) {
            throw new ResourceUnavailableException("Failed to remove stale control nodes from the Kubernetes API load balancing rule", Network.class,
                    network.getId());
        }
        Map<Long, List<String>> missingVmIdIpMap = new HashMap<>();
        for (Map.Entry<Long, List<String>> desired : desiredVmIdIpMap.entrySet()) {
            boolean alreadyMapped = existingMappings.stream().anyMatch(mapping -> Objects.equals(mapping.getInstanceId(), desired.getKey())
                    && desired.getValue().contains(mapping.getInstanceIp()) && !staleVmIdIpMap.containsKey(mapping.getInstanceId()));
            if (!alreadyMapped) {
                missingVmIdIpMap.put(desired.getKey(), desired.getValue());
            }
        }
        if (!missingVmIdIpMap.isEmpty() && !lbService.assignToLoadBalancer(lb.getId(), null, missingVmIdIpMap, null, false)) {
            throw new ResourceUnavailableException("Failed to assign control nodes to the Kubernetes API load balancing rule", Network.class, network.getId());
        }
        if (missingVmIdIpMap.isEmpty() && staleVmIdIpMap.isEmpty() && !lbService.applyLoadBalancerConfig(lb.getId())) {
            throw new ResourceUnavailableException("Failed to apply the Kubernetes API load balancing rule", Network.class, network.getId());
        }
        activateManagedFirewallRule(lb.getId());
    }

    protected Map<Long, Integer> createFirewallRules(IpAddress publicIp, List<Long> clusterVMIds, boolean apiRule) throws ManagementServerException {
        // Firewall rule for SSH access on each node VM
        Map<Long, Integer> vmIdPortMap = addFirewallRulesForNodes(publicIp, clusterVMIds.size());
        if (!apiRule) {
            return vmIdPortMap;
        }
        // Firewall rule for API access for control node VMs
        CallContext.register(CallContext.current(), null);
        try {
            provisionFirewallRules(publicIp, owner, CLUSTER_API_PORT, CLUSTER_API_PORT, API_FIREWALL_ROLE);
            if (logger.isInfoEnabled()) {
                logger.info("Provisioned firewall rule to open up port {} on {} for Kubernetes cluster {}", CLUSTER_API_PORT, publicIp.getAddress().addr(), kubernetesCluster);
            }
        } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to provision firewall rules for API access for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        } finally {
            CallContext.unregister();
        }
        return vmIdPortMap;
    }

    /**
     * Setup network rules for Kubernetes cluster
     * Open up firewall port CLUSTER_API_PORT, secure port on which Kubernetes
     * API server is running. Also create load balancing rule to forward public
     * IP traffic to control VMs' private IP.
     * Open up  firewall ports NODES_DEFAULT_START_SSH_PORT to NODES_DEFAULT_START_SSH_PORT+n
     * for SSH access. Also create port-forwarding rule to forward public IP traffic to all
     * @param network
     * @param clusterVMIds
     * @throws ManagementServerException
     */
    protected void setupKubernetesClusterIsolatedNetworkRules(IpAddress publicIp, Network network, List<Long> clusterVMIds, boolean apiRule) throws ManagementServerException {
        Map<Long, Integer> vmIdPortMap = createFirewallRules(publicIp, clusterVMIds, apiRule);

        // Port forwarding rule for SSH access on each node VM
        try {
            provisionSshPortForwardingRules(publicIp, network, owner, clusterVMIds, vmIdPortMap);
        } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to activate SSH port forwarding rules for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        }

        if (!apiRule) {
            return;
        }
        // Load balancer rule for API access for control node VMs
        try {
            provisionLoadBalancerRule(publicIp, network, owner, clusterVMIds, CLUSTER_API_PORT);
        } catch (NetworkRuleConflictException | InsufficientAddressCapacityException | ResourceUnavailableException e) {
            throw new ManagementServerException(String.format("Failed to provision load balancer rule for API access for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        }
    }

    protected void createVpcTierAclRules(Network network) throws ManagementServerException {
        Long networkAclId = network.getNetworkACLId();
        if (networkAclId == null) {
            throw new ManagementServerException(String.format("Failed to provision ACL rules for the Kubernetes cluster : %s as VPC tier %s does not have a network ACL attached", kubernetesCluster.getName(), network.getName()));
        }
        if (Objects.equals(networkAclId, NetworkACL.DEFAULT_ALLOW)) {
            return;
        }
        // ACL rule for API access for control node VMs
        CallContext.register(CallContext.current(), null);
        try {
            provisionVpcTierAllowPortACLRule(network, CLUSTER_API_PORT, CLUSTER_API_PORT, API_ACL_ROLE);
            if (logger.isInfoEnabled()) {
                logger.info("Provisioned ACL rule to open up port {} on {} for Kubernetes cluster {}", CLUSTER_API_PORT, publicIpAddress, kubernetesCluster);
            }
        } catch (ResourceUnavailableException | NetworkRuleConflictException | InvalidParameterValueException | PermissionDeniedException e) {
            throw new ManagementServerException(String.format("Failed to provision firewall rules for API access for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        } finally {
            CallContext.unregister();
        }
        CallContext.register(CallContext.current(), null);
        try {
            provisionVpcTierAllowPortACLRule(network, DEFAULT_SSH_PORT, DEFAULT_SSH_PORT, SSH_ACL_ROLE);
            if (logger.isInfoEnabled()) {
                logger.info("Provisioned ACL rule to open up port {} on {} for Kubernetes cluster {}", DEFAULT_SSH_PORT, publicIpAddress, kubernetesCluster);
            }
        } catch (ResourceUnavailableException | NetworkRuleConflictException | InvalidParameterValueException | PermissionDeniedException e) {
            throw new ManagementServerException(String.format("Failed to provision firewall rules for API access for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        } finally {
            CallContext.unregister();
        }
    }

    protected void setupKubernetesClusterVpcTierRules(IpAddress publicIp, Network network, List<Long> clusterVMIds) throws ManagementServerException {
        // Create ACL rules
        createVpcTierAclRules(network);

        NetworkOffering offering = networkOfferingDao.findById(network.getNetworkOfferingId());
        if (offering.isConserveMode()) {
            // Add load balancing for API access
            try {
                provisionLoadBalancerRule(publicIp, network, owner, clusterVMIds, CLUSTER_API_PORT);
            } catch (InsufficientAddressCapacityException | ResourceUnavailableException e) {
                throw new ManagementServerException(String.format("Failed to activate API load balancing rules for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
            }
        } else {
            // Add port forwarding for API access
            try {
                provisionPublicIpPortForwardingRule(publicIp, network, owner, clusterVMIds.get(0), CLUSTER_API_PORT, CLUSTER_API_PORT);
            } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
                throw new ManagementServerException(String.format("Failed to activate API port forwarding rules for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
            }
        }

        // Add port forwarding rule for SSH access on each node VM
        try {
            Map<Long, Integer> vmIdPortMap = getVmPortMap();
            provisionSshPortForwardingRules(publicIp, network, owner, clusterVMIds, vmIdPortMap);
        } catch (ResourceUnavailableException | NetworkRuleConflictException e) {
            throw new ManagementServerException(String.format("Failed to activate SSH port forwarding rules for the Kubernetes cluster : %s", kubernetesCluster.getName()), e);
        }
    }

    /**
     * Generates a valid name prefix for Kubernetes cluster nodes.
     *
     * <p>The prefix must comply with Kubernetes naming constraints:
     * <ul>
     *   <li>Maximum 63 characters total</li>
     *   <li>Only lowercase alphanumeric characters and hyphens</li>
     *   <li>Must start with a letter</li>
     *   <li>Must end with an alphanumeric character</li>
     * </ul>
     *
     * <p>The generated prefix is limited to 43 characters to accommodate the full node naming pattern:
     * <pre>{'prefix'}-{'control' | 'node'}-{'11-digit-hash'}</pre>
     *
     * @return A valid node name prefix, truncated if necessary
     * @see <a href="https://kubernetes.io/docs/concepts/overview/working-with-objects/names/">Kubernetes "Object Names and IDs" documentation</a>
     */
    protected String getKubernetesClusterNodeNamePrefix() {
        String prefix = kubernetesCluster.getName().toLowerCase();

        if (NetUtils.verifyDomainNameLabel(prefix, true)) {
            return StringUtils.truncate(prefix, MAX_CLUSTER_PREFIX_LENGTH);
        }

        prefix = prefix.replaceAll("[^a-z0-9-]", "");
        if (prefix.isEmpty()) {
            prefix = kubernetesCluster.getUuid();
        }
        return StringUtils.truncate("k8s-" + prefix, MAX_CLUSTER_PREFIX_LENGTH);
    }

    protected String getEtcdNodeNameForCluster() {
        String prefix = kubernetesCluster.getName();
        if (!NetUtils.verifyDomainNameLabel(prefix, true)) {
            prefix = prefix.replaceAll("[^a-zA-Z0-9-]", "");
            if (prefix.isEmpty()) {
                prefix = kubernetesCluster.getUuid();
            }
        }
        prefix = prefix + "-etcd" ;
        if (prefix.length() > 40) {
            prefix = prefix.substring(0, 40);
        }
        return prefix;
    }

    protected KubernetesClusterVO updateKubernetesClusterEntry(final Long cores, final Long memory, final Long size,
            final Long serviceOfferingId, final Boolean autoscaleEnabled,
            final Long minSize, final Long maxSize,
            final KubernetesClusterNodeType nodeType,
            final boolean updateNodeOffering,
            final boolean updateClusterOffering) {
        return Transaction.execute((TransactionCallback<KubernetesClusterVO>) status -> {
            KubernetesClusterVO updatedCluster = kubernetesClusterDao.createForUpdate(kubernetesCluster.getId());

            if (cores != null) {
                updatedCluster.setCores(cores);
            }
            if (memory != null) {
                updatedCluster.setMemory(memory);
            }
            if (size != null) {
                updatedCluster.setNodeCount(size);
            }
            if (updateNodeOffering && serviceOfferingId != null && nodeType != null) {
                if (WORKER == nodeType) {
                    updatedCluster.setWorkerNodeServiceOfferingId(serviceOfferingId);
                } else if (CONTROL == nodeType) {
                    updatedCluster.setControlNodeServiceOfferingId(serviceOfferingId);
                } else if (ETCD == nodeType) {
                    updatedCluster.setEtcdNodeServiceOfferingId(serviceOfferingId);
                }
            }
            if (updateClusterOffering && serviceOfferingId != null) {
                updatedCluster.setServiceOfferingId(serviceOfferingId);
            }
            if (autoscaleEnabled != null) {
                updatedCluster.setAutoscalingEnabled(autoscaleEnabled.booleanValue());
            }
            updatedCluster.setMinSize(minSize);
            updatedCluster.setMaxSize(maxSize);
            kubernetesClusterDao.persist(updatedCluster);
            // Prevent null attributes set by the createForUpdate method
            return kubernetesClusterDao.findById(kubernetesCluster.getId());
        });
    }

    private KubernetesClusterVO updateKubernetesClusterEntry(final Boolean autoscaleEnabled, final Long minSize, final Long maxSize) throws CloudRuntimeException {
        KubernetesClusterVO kubernetesClusterVO = updateKubernetesClusterEntry(null, null, null, null, autoscaleEnabled, minSize, maxSize, null, false, false);
        if (kubernetesClusterVO == null) {
            logTransitStateAndThrow(Level.ERROR, String.format("Scaling Kubernetes cluster %s failed, unable to update Kubernetes cluster",
                    kubernetesCluster.getName()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
        }
        return kubernetesClusterVO;
    }

    protected boolean autoscaleCluster(boolean enable, Long minSize, Long maxSize) {
        if (!kubernetesCluster.getState().equals(KubernetesCluster.State.Scaling)) {
            stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.AutoscaleRequested);
        }

        File pkFile = getManagementServerSshPublicKeyFile();
        Pair<String, Integer> publicIpSshPort = getKubernetesClusterServerIpSshPort(null);
        publicIpAddress = publicIpSshPort.first();
        sshPort = publicIpSshPort.second();

        try {
            if (enable) {
                String command = String.format("sudo /opt/bin/autoscale-kube-cluster -i %s -e -M %d -m %d",
                        kubernetesCluster.getUuid(), maxSize, minSize);
                Pair<Boolean, String> result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(),
                        pkFile, null, command, 10000, 10000, 60000);

                // Maybe the file isn't present. Try and copy it
                if (!result.first()) {
                    logMessage(Level.INFO, "Autoscaling files missing. Adding them now", null);
                    retrieveScriptFiles();
                    copyScripts(publicIpAddress, sshPort);

                    if (!createCloudStackSecret(keys)) {
                        logTransitStateAndThrow(Level.ERROR, String.format("Failed to setup keys for Kubernetes cluster %s",
                                kubernetesCluster.getName()), kubernetesCluster.getId(), KubernetesCluster.Event.OperationFailed);
                    }

                    // If at first you don't succeed ...
                    result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(),
                            pkFile, null, command, 10000, 10000, 60000);
                    if (!result.first()) {
                        throw new CloudRuntimeException(result.second());
                    }
                }
                updateKubernetesClusterEntry(true, minSize, maxSize);
            } else {
                Pair<Boolean, String> result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(),
                        pkFile, null, String.format("sudo /opt/bin/autoscale-kube-cluster -d"),
                        10000, 10000, 60000);
                if (!result.first()) {
                    throw new CloudRuntimeException(result.second());
                }
                updateKubernetesClusterEntry(false, null, null);
            }
            return true;
        } catch (Exception e) {
            String msg = String.format("Failed to autoscale Kubernetes cluster: %s : %s", kubernetesCluster.getName(), e.getMessage());
            logAndThrow(Level.ERROR, msg);
            return false;
        } finally {
            // Deploying the autoscaler might fail but it can be deployed manually too, so no need to go to an alert state
            updateLoginUserDetails(null);
        }
    }

    protected List<DedicatedResourceVO> listDedicatedHostsInDomain(Long domainId) {
        return dedicatedResourceDao.listByDomainId(domainId);
    }

    public boolean deletePVsWithReclaimPolicyDelete() {
        File pkFile = getManagementServerSshPublicKeyFile();
        Pair<String, Integer> publicIpSshPort = getKubernetesClusterServerIpSshPort(null);
        publicIpAddress = publicIpSshPort.first();
        sshPort = publicIpSshPort.second();
        try {
            String command = String.format("sudo %s/%s", scriptPath, deletePvScriptFilename);
            logMessage(Level.INFO, "Starting PV deletion script for cluster: " + kubernetesCluster.getName(), null);
            Pair<Boolean, String> result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(),
                    pkFile, null, command, 10000, 10000, 600000); // 10 minute timeout
            if (Boolean.FALSE.equals(result.first())) {
                logMessage(Level.INFO, "PV delete script missing. Adding it now", null);
                retrieveScriptFiles();
                if (deletePvScriptFile != null) {
                    copyScriptFile(publicIpAddress, sshPort, deletePvScriptFile, deletePvScriptFilename);
                    logMessage(Level.INFO, "Executing PV deletion script (this may take several minutes)...", null);
                    result = SshHelper.sshExecute(publicIpAddress, sshPort, getControlNodeLoginUser(),
                            pkFile, null, command, 10000, 10000, 600000); // 10 minute timeout
                    if (Boolean.FALSE.equals(result.first())) {
                        logMessage(Level.ERROR, "PV deletion script failed: " + result.second(), null);
                        throw new CloudRuntimeException(result.second());
                    }
                    logMessage(Level.INFO, "PV deletion script completed successfully", null);
                } else {
                    logMessage(Level.WARN, "PV delete script file not found in resources, skipping PV deletion", null);
                    return false;
                }
            } else {
                logMessage(Level.INFO, "PV deletion script completed successfully", null);
            }

            if (result.second() != null && !result.second().trim().isEmpty()) {
                logMessage(Level.INFO, "PV deletion script output: " + result.second(), null);
            }

            return true;
        } catch (Exception e) {
            String msg = String.format("Failed to delete PVs with reclaimPolicy=Delete: %s : %s", kubernetesCluster.getName(), e.getMessage());
            logMessage(Level.WARN, msg, e);
            return false;
        }
    }
}
