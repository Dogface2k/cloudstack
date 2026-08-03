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

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import com.cloud.bgp.BGPService;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ASNumberDao;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.kubernetes.cluster.KubernetesCluster;
import com.cloud.kubernetes.cluster.KubernetesClusterDetailsVO;
import com.cloud.kubernetes.cluster.KubernetesClusterManagerImpl;
import com.cloud.kubernetes.cluster.KubernetesClusterNetworkRuleOwnershipState;
import com.cloud.kubernetes.cluster.KubernetesClusterVO;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMap;
import com.cloud.kubernetes.cluster.KubernetesClusterVmMapVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.RemoteAccessVpnDao;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import org.apache.logging.log4j.Level;

public class KubernetesClusterDestroyWorker extends KubernetesClusterResourceModifierActionWorker {

    @Inject
    protected AccountManager accountManager;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private ASNumberDao asNumberDao;
    @Inject
    private BGPService bgpService;
    @Inject
    protected RemoteAccessVpnDao remoteAccessVpnDao;
    @Inject
    protected Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;

    private List<KubernetesClusterVmMapVO> clusterVMs;

    public KubernetesClusterDestroyWorker(final KubernetesCluster kubernetesCluster, final KubernetesClusterManagerImpl clusterManager) {
        super(kubernetesCluster, clusterManager);
    }

    private void validateClusterSate() {
        if (!(kubernetesCluster.getState().equals(KubernetesCluster.State.Running)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Stopped)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Alert)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Error)
                || kubernetesCluster.getState().equals(KubernetesCluster.State.Destroying))) {
            String msg = String.format("Cannot perform delete operation on cluster %s in state: %s",
                    kubernetesCluster, kubernetesCluster.getState());
            logger.warn(msg);
            throw new PermissionDeniedException(msg);
        }
    }

    private boolean destroyClusterVMs() {
        boolean vmDestroyed = true;
        if (!CollectionUtils.isEmpty(clusterVMs)) {
            for (KubernetesClusterVmMapVO clusterVM : clusterVMs) {
                long vmID = clusterVM.getVmId();

                // delete only if VM exists and is not removed
                UserVmVO userVM = userVmDao.findById(vmID);
                if (userVM == null || userVM.isRemoved()) {
                    continue;
                }
                CallContext vmContext = CallContext.register(CallContext.current(),
                        ApiCommandResourceType.VirtualMachine);
                vmContext.setEventResourceId(vmID);
                try {
                    if (clusterVM.isControlNode() && kubernetesCluster.isCsiEnabled()) {
                        deletePVsWithReclaimPolicyDelete();
                    }
                    UserVm vm = userVmService.destroyVm(vmID, true);
                    if (!userVmManager.expunge(userVM)) {
                        logger.warn("Unable to expunge VM {}, destroying Kubernetes cluster will probably fail", vm);
                    }
                    kubernetesClusterVmMapDao.expunge(clusterVM.getId());
                    if (logger.isInfoEnabled()) {
                        logger.info("Destroyed VM {} as part of Kubernetes cluster : {} cleanup", vm, kubernetesCluster);
                    }
                } catch (ResourceUnavailableException | ConcurrentOperationException e) {
                    logger.warn("Failed to destroy VM {} part of the Kubernetes cluster {} " +
                            "cleanup. Moving on with destroying remaining resources provisioned " +
                            "for the Kubernetes cluster", userVM, kubernetesCluster, e);
                    return false;
                } finally {
                    CallContext.unregister();
                }
            }
        }
        return vmDestroyed;
    }

    private boolean updateKubernetesClusterEntryForGC() {
        KubernetesClusterVO kubernetesClusterVO = kubernetesClusterDao.findById(kubernetesCluster.getId());
        kubernetesClusterVO.setCheckForGc(true);
        return kubernetesClusterDao.update(kubernetesCluster.getId(), kubernetesClusterVO);
    }

    private void destroyKubernetesClusterNetwork() throws ManagementServerException {
        NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
        if (network != null && network.getRemoved() == null) {
            Account owner = accountManager.getAccount(network.getAccountId());
            User callerUser = accountManager.getActiveUser(CallContext.current().getCallingUserId());
            ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);
            releaseASNumber(kubernetesCluster.getZoneId(), kubernetesCluster.getNetworkId());
            boolean networkDestroyed = networkMgr.destroyNetwork(kubernetesCluster.getNetworkId(), context, true);
            if (!networkDestroyed) {
                String msg = String.format("Failed to destroy network: %s as part of Kubernetes cluster: %s cleanup", network, kubernetesCluster);
                logger.warn(msg);
                throw new ManagementServerException(msg);
            }
            if (logger.isInfoEnabled()) {
                logger.info("Destroyed network: {} as part of Kubernetes cluster: {} cleanup", network, kubernetesCluster);
            }
        }
    }

    private void releaseASNumber(Long zoneId, long networkId) {
        DataCenter zone = dataCenterDao.findById(zoneId);
        ASNumberVO asNumber = asNumberDao.findByZoneAndNetworkId(zone.getId(), networkId);
        if (asNumber != null) {
            logger.debug(String.format("Releasing AS number %s from network %s", asNumber.getAsNumber(), networkId));
            bgpService.releaseASNumber(zone.getId(), asNumber.getAsNumber(), true);
        }
    }

    protected void deleteManagedNetworkRulesIfPresent(NetworkVO network,
            KubernetesClusterNetworkRuleOwnershipState ownershipState) throws ManagementServerException {
        if (!KubernetesClusterNetworkRuleOwnershipState.MANAGED.equals(ownershipState)) {
            return;
        }
        if (network != null) {
            deleteAllManagedNetworkRules(network);
            return;
        }
        boolean hasFirewallMappings = !kubernetesClusterFirewallRuleMapDao.listByClusterId(kubernetesCluster.getId()).isEmpty();
        boolean hasAclMappings = !kubernetesClusterNetworkACLItemMapDao.listByClusterId(kubernetesCluster.getId()).isEmpty();
        if (hasFirewallMappings || hasAclMappings) {
            throw new ManagementServerException(String.format(
                    "Network for Kubernetes cluster %s is missing while managed network-rule ownership records remain",
                    kubernetesCluster.getName()));
        }
    }

    private void validateClusterVMsDestroyed() {
        if(clusterVMs!=null  && !clusterVMs.isEmpty()) { // Wait for few seconds to get all VMs really expunged
            final int maxRetries = 3;
            int retryCounter = 0;
            while (retryCounter < maxRetries) {
                boolean allVMsRemoved = true;
                for (KubernetesClusterVmMap clusterVM : clusterVMs) {
                    UserVmVO userVM = userVmDao.findById(clusterVM.getVmId());
                    if (userVM != null && !userVM.isRemoved()) {
                        allVMsRemoved = false;
                        break;
                    }
                }
                if (allVMsRemoved) {
                    break;
                }
                try {
                    Thread.sleep(10000);
                } catch (InterruptedException ie) {}
                retryCounter++;
            }
        }
    }

    protected void releaseVpcTierPublicIpIfNeeded(KubernetesClusterNetworkRuleOwnershipState ownershipState)
            throws InsufficientAddressCapacityException {
        NetworkVO networkVO = networkDao.findById(kubernetesCluster.getNetworkId());
        if (networkVO == null || networkVO.getVpcId() == null) {
            return;
        }
        IpAddress address = getVpcTierKubernetesPublicIp(networkVO);
        if (address == null) {
            return;
        }
        if (!KubernetesClusterNetworkRuleOwnershipState.MANAGED.equals(ownershipState)) {
            logger.warn("Retaining public IP {} while deleting legacy Kubernetes cluster {}; ownership of resources on the IP is unknown",
                    address.getAddress().addr(), kubernetesCluster.getName());
            return;
        }
        boolean hasDirectIpBinding = address.isSourceNat() || address.isOneToOneNat()
                || address.getAssociatedWithVmId() != null || address.getSystem()
                || address.isForSystemVms() || address.isForRouter() || address.isPortable();
        boolean hasFirewallRules = CollectionUtils.isNotEmpty(firewallRulesDao.listByIpAndNotRevoked(address.getId()));
        boolean hasRemoteAccessVpn = remoteAccessVpnDao.findByPublicIpAddress(address.getId()) != null;
        boolean hasSiteToSiteVpnGateway = site2SiteVpnGatewayDao.findByPublicIpAddress(address.getId()) != null;
        if (hasDirectIpBinding || hasFirewallRules || hasRemoteAccessVpn || hasSiteToSiteVpnGateway) {
            logger.warn("Retaining public IP {} after deleting Kubernetes cluster {} because resources not owned by the cluster remain on the IP",
                    address.getAddress().addr(), kubernetesCluster.getName());
            return;
        }
        if (!networkService.releaseIpAddress(address.getId())) {
            throw new InsufficientAddressCapacityException(String.format("Failed to release public IP address %s for Kubernetes cluster %s",
                    address.getAddress().addr(), kubernetesCluster.getName()), Network.class, networkVO.getId());
        }
        kubernetesClusterDetailsDao.removeDetail(kubernetesCluster.getId(), ApiConstants.PUBLIC_IP_ID);
    }

    public boolean destroy() throws CloudRuntimeException {
        init();
        validateClusterSate();
        this.clusterVMs = kubernetesClusterVmMapDao.listByClusterId(kubernetesCluster.getId());
        List<VMInstanceVO> vms = this.clusterVMs.stream().map(vmMap -> vmInstanceDao.findById(vmMap.getVmId())).collect(Collectors.toList());
        if (KubernetesClusterManagerImpl.checkIfVmsAssociatedWithBackupOffering(vms)) {
            throw new CloudRuntimeException("Unable to delete Kubernetes cluster, as node(s) are associated to a backup offering");
        }
        boolean cleanupNetwork = true;
        final KubernetesClusterDetailsVO clusterDetails = kubernetesClusterDetailsDao.findDetail(kubernetesCluster.getId(), "networkCleanup");
        if (clusterDetails != null) {
            cleanupNetwork = Boolean.parseBoolean(clusterDetails.getValue());
        }
        NetworkVO clusterNetwork = networkDao.findById(kubernetesCluster.getNetworkId());
        boolean directAccess = clusterNetwork != null && manager.isDirectAccess(clusterNetwork);
        KubernetesClusterVO persistedCluster = kubernetesClusterDao.findById(kubernetesCluster.getId());
        if (persistedCluster == null) {
            throw new CloudRuntimeException(String.format("Kubernetes cluster %s no longer exists", kubernetesCluster.getName()));
        }
        boolean legacyNetworkRules = persistedCluster.getNetworkRuleOwnershipState() != KubernetesClusterNetworkRuleOwnershipState.MANAGED;
        if (cleanupNetwork) { // if network has additional VM, cannot proceed with cluster destroy
            NetworkVO network = networkDao.findById(kubernetesCluster.getNetworkId());
            List<KubernetesClusterVmMapVO> externalNodes = clusterVMs.stream().filter(KubernetesClusterVmMapVO::isExternalNode).collect(Collectors.toList());
            if (!externalNodes.isEmpty()) {
                String errMsg = String.format("Failed to delete kubernetes cluster %s as there are %s external node(s) present. Please remove the external node(s) from the cluster (and network) or delete them before deleting the cluster.", kubernetesCluster.getName(), externalNodes.size());
                logger.error(errMsg);
                throw new CloudRuntimeException(errMsg);
            }
            if (network != null) {
                List<VMInstanceVO> networkVMs = vmInstanceDao.listNonRemovedVmsByTypeAndNetwork(network.getId(), VirtualMachine.Type.User);
                if (networkVMs.size() > clusterVMs.size()) {
                    logAndThrow(Level.ERROR, String.format("Network : %s for Kubernetes cluster : %s has instances using it which are not part of the Kubernetes cluster", network.getName(), kubernetesCluster.getName()));
                }
                for (VMInstanceVO vm : networkVMs) {
                    boolean vmFoundInKubernetesCluster = false;
                    for (KubernetesClusterVmMap clusterVM : clusterVMs) {
                        if (vm.getId() == clusterVM.getVmId()) {
                            vmFoundInKubernetesCluster = true;
                            break;
                        }
                    }
                    if (!vmFoundInKubernetesCluster) {
                        logAndThrow(Level.ERROR, String.format("VM : %s which is not a part of Kubernetes cluster : %s is using Kubernetes cluster network : %s", vm.getUuid(), kubernetesCluster.getName(), network.getName()));
                    }
                }
            } else {
                logger.error("Failed to find network for Kubernetes cluster : {}", kubernetesCluster);
            }
        }
        if (logger.isInfoEnabled()) {
            logger.info("Destroying Kubernetes cluster : {}", kubernetesCluster);
        }
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.DestroyRequested);
        boolean vmsDestroyed = destroyClusterVMs();
        // if there are VM's that were not expunged, we can not delete the network
        if (vmsDestroyed) {
            if (cleanupNetwork) {
                validateClusterVMsDestroyed();
            }
            try {
                deleteManagedNetworkRulesIfPresent(clusterNetwork, persistedCluster.getNetworkRuleOwnershipState());
            } catch (ManagementServerException | CloudRuntimeException e) {
                String msg = String.format("Failed to remove managed network rules of Kubernetes cluster: %s", kubernetesCluster);
                logger.warn(msg, e);
                updateKubernetesClusterEntryForGC();
                throw new CloudRuntimeException(msg, e);
            }
            if (cleanupNetwork) {
                try {
                    destroyKubernetesClusterNetwork();
                } catch (ManagementServerException | CloudRuntimeException e) {
                    String msg = String.format("Failed to destroy network of Kubernetes cluster: %s cleanup", kubernetesCluster);
                    logger.warn(msg, e);
                    updateKubernetesClusterEntryForGC();
                    throw new CloudRuntimeException(msg, e);
                }
            } else {
                if (legacyNetworkRules && !directAccess) {
                    logger.warn("Leaving unowned legacy network rules in retained network {} while deleting Kubernetes cluster {}; "
                            + "only explicitly adopted rules can be removed safely", clusterNetwork, kubernetesCluster);
                }
                try {
                    releaseVpcTierPublicIpIfNeeded(persistedCluster.getNetworkRuleOwnershipState());
                } catch (InsufficientAddressCapacityException e) {
                    String msg = String.format("Failed to release public IP for VPC tier used by Kubernetes cluster: %s", kubernetesCluster);
                    logger.warn(msg, e);
                    updateKubernetesClusterEntryForGC();
                    throw new CloudRuntimeException(msg, e);
                }
            }
        } else {
            String msg = String.format("Failed to destroy one or more VMs as part of Kubernetes cluster: %s cleanup", kubernetesCluster);
            logger.warn(msg);
            updateKubernetesClusterEntryForGC();
            throw new CloudRuntimeException(msg);
        }
        stateTransitTo(kubernetesCluster.getId(), KubernetesCluster.Event.OperationSucceeded);
        annotationDao.removeByEntityType(AnnotationService.EntityType.KUBERNETES_CLUSTER.name(), kubernetesCluster.getUuid());
        kubernetesClusterDetailsDao.removeDetails(kubernetesCluster.getId());
        kubernetesClusterAffinityGroupMapDao.removeByClusterId(kubernetesCluster.getId());
        boolean deleted = kubernetesClusterDao.remove(kubernetesCluster.getId());
        if (!deleted) {
            logMessage(Level.WARN, String.format("Failed to delete Kubernetes cluster: %s", kubernetesCluster), null);
            updateKubernetesClusterEntryForGC();
            return false;
        }
        if (logger.isInfoEnabled()) {
            logger.info("Kubernetes cluster: {} is successfully deleted", kubernetesCluster);
        }
        return true;
    }
}
