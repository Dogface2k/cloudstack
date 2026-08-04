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
package org.apache.cloudstack.service;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.NsxAnswer;
import org.apache.cloudstack.NsxVrfGatewayValidationAnswer;
import org.apache.cloudstack.agent.api.CreateNsxDistributedFirewallRulesCommand;
import org.apache.cloudstack.agent.api.CreateNsxLoadBalancerRuleCommand;
import org.apache.cloudstack.agent.api.CreateNsxPortForwardRuleCommand;
import org.apache.cloudstack.agent.api.CreateNsxStaticNatCommand;
import org.apache.cloudstack.agent.api.CreateNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.CreateNsxVpnConnectionCommand;
import org.apache.cloudstack.agent.api.CreateNsxVpnGatewayCommand;
import org.apache.cloudstack.agent.api.CreateOrUpdateNsxTier1NatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxDistributedFirewallRulesCommand;
import org.apache.cloudstack.agent.api.DeleteNsxLoadBalancerRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxNatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxSegmentCommand;
import org.apache.cloudstack.agent.api.DeleteNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.DeleteNsxVpnConnectionCommand;
import org.apache.cloudstack.agent.api.DeleteNsxVpnGatewayCommand;
import org.apache.cloudstack.agent.api.GetNsxVpnSessionStatusCommand;
import org.apache.cloudstack.agent.api.UpdateNsxVpnConnectionStateCommand;
import org.apache.cloudstack.agent.api.ValidateNsxVrfGatewayCommand;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.resource.NsxNetworkRule;
import org.apache.cloudstack.resourcedetail.dao.UserIpAddressDetailsDao;
import org.apache.cloudstack.utils.NsxControllerUtils;
import org.apache.cloudstack.utils.NsxHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.alert.AlertManager;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.SDNProviderNetworkRule;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxVrfGatewayDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.NsxVrfGatewayPlacementDao;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.Site2SiteVpnGatewayVO;
import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.network.element.NsxVrfGatewayPlacementVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.nsx.NsxService;
import com.cloud.network.nsx.NsxVpnGatewayResult;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.exception.CloudRuntimeException;
import org.apache.commons.lang3.StringUtils;

public class NsxServiceImpl extends ManagerBase implements NsxService, Configurable {

    public static final ConfigKey<Integer> NSX_VPN_STATUS_POLL_INTERVAL = new ConfigKey<>("Advanced", Integer.class,
            "nsx.vpn.status.poll.interval", "60",
            "Interval (in seconds) between two NSX Site-to-Site VPN connection status polls; requires a management server restart",
            false, ConfigKey.Scope.Global);

    public static final ConfigKey<String> NSX_VRF_SCOPE = new ConfigKey<>(String.class,
            "nsx.vrf.scope", "Advanced", "NONE",
            "Routing domain granularity for NSX zones. NONE (default) attaches every tenant's Tier-1 " +
                    "gateways to the zone-wide Tier-0. ACCOUNT or DOMAIN attaches them to the registered " +
                    "tenant-specific Tier-0 or VRF gateway",
            true, ConfigKey.Scope.Zone, null, null, null, null, null,
            ConfigKey.Kind.Select, "NONE,ACCOUNT,DOMAIN");

    public static final ConfigKey<Boolean> NSX_VRF_FALLBACK_TO_SHARED_TIER0 = new ConfigKey<>("Advanced", Boolean.class,
            "nsx.vrf.fallback.to.shared.tier0", "false",
            "Allow a tenant without an assigned NSX VRF gateway to use the zone-wide Tier-0 when nsx.vrf.scope is enabled",
            true, ConfigKey.Scope.Zone);

    protected static final String NSX_VRF_SCOPE_NONE = "NONE";

    protected static final String VPN_SESSION_STATUS_UP = "UP";
    protected static final String VPN_SESSION_STATUS_DOWN = "DOWN";
    protected static final String VPN_SESSION_STATUS_DEGRADED = "DEGRADED";
    protected static final String VPN_SESSION_STATUS_NOT_FOUND = "NOT_FOUND";
    protected static final int VPN_STATUS_POLL_FAILURE_THRESHOLD = 3;
    protected static final int VPN_STATUS_POLL_MIN_INTERVAL = 10;
    protected static final int VPN_STATUS_POLL_DEFAULT_INTERVAL = 60;

    private static final List<Site2SiteVpnConnection.State> VPN_POLLED_STATES = List.of(
            Site2SiteVpnConnection.State.Pending, Site2SiteVpnConnection.State.Connecting, Site2SiteVpnConnection.State.Connected,
            Site2SiteVpnConnection.State.Disconnected);

    @Inject
    NsxControllerUtils nsxControllerUtils;
    @Inject
    VpcDao vpcDao;
    @Inject
    Site2SiteVpnConnectionDao site2SiteVpnConnectionDao;
    @Inject
    Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;
    @Inject
    NsxVrfGatewayDao nsxVrfGatewayDao;
    @Inject
    NsxVrfGatewayPlacementDao nsxVrfGatewayPlacementDao;
    @Inject
    NsxProviderDao nsxProviderDao;
    @Inject
    NsxVrfGatewayLockManager nsxVrfGatewayLockManager;
    @Inject
    DomainDao domainDao;
    @Inject
    UserIpAddressDetailsDao userIpAddressDetailsDao;
    @Inject
    AlertManager alertManager;

    protected Logger logger = LogManager.getLogger(getClass());

    private ScheduledExecutorService vpnStatusPollExecutor;
    private final Map<Long, Integer> vpnStatusPollFailures = new ConcurrentHashMap<>();

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        super.configure(name, params);
        return true;
    }

    @Override
    public synchronized boolean start() {
        super.start();
        if (vpnStatusPollExecutor != null && !vpnStatusPollExecutor.isShutdown()) {
            return true;
        }
        Integer configuredInterval = NSX_VPN_STATUS_POLL_INTERVAL.value();
        int pollInterval = Objects.isNull(configuredInterval) ? VPN_STATUS_POLL_DEFAULT_INTERVAL : configuredInterval;
        if (pollInterval < VPN_STATUS_POLL_MIN_INTERVAL) {
            logger.warn("The configured value {} of {} is below the minimum of {} seconds, using the default of {} seconds",
                    configuredInterval, NSX_VPN_STATUS_POLL_INTERVAL.key(), VPN_STATUS_POLL_MIN_INTERVAL, VPN_STATUS_POLL_DEFAULT_INTERVAL);
            pollInterval = VPN_STATUS_POLL_DEFAULT_INTERVAL;
        }
        ScheduledExecutorService executor = createVpnStatusPollExecutor();
        try {
            executor.scheduleWithFixedDelay(new VpnStatusPollTask(), pollInterval, pollInterval, TimeUnit.SECONDS);
            vpnStatusPollExecutor = executor;
        } catch (RuntimeException e) {
            executor.shutdownNow();
            throw e;
        }
        return true;
    }

    protected ScheduledExecutorService createVpnStatusPollExecutor() {
        return Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Nsx-Vpn-Status-Poll"));
    }

    @Override
    public synchronized boolean stop() {
        ScheduledExecutorService executor = vpnStatusPollExecutor;
        vpnStatusPollExecutor = null;
        if (Objects.nonNull(executor)) {
            executor.shutdownNow();
        }
        return super.stop();
    }

    public boolean createVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName,
            boolean sourceNatEnabled, Long sourceNatVlanId) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true, sourceNatEnabled);
        return createTier1GatewayWithPlacement(createNsxTier1GatewayCommand, sourceNatVlanId);
    }

    private boolean createTier1GatewayWithPlacement(CreateNsxTier1GatewayCommand cmd, Long sourceNatVlanId) {
        return nsxVrfGatewayLockManager.withPlacementLock(cmd.isResourceVpc(), cmd.getNetworkResourceId(), () -> {
            PlacementPlan plan = nsxVrfGatewayLockManager.withZoneLock(cmd.getZoneId(),
                    () -> preparePlacement(cmd, sourceNatVlanId));
            try {
                if (plan.gateway != null) {
                    validateVrfGatewayForPlacement(plan.gateway);
                }
                NsxAnswer result = nsxControllerUtils.sendNsxCommand(cmd, cmd.getZoneId());
                if (!result.getResult()) {
                    throw new CloudRuntimeException(String.format(
                            "Failed to create NSX Tier-1 gateway for %s: %s",
                            cmd.getNetworkResourceName(), result.getDetails()));
                }
                plan.placement.setState(NsxVrfGatewayPlacementVO.State.ACTIVE);
                updatePlacement(plan.placement);
                return true;
            } catch (RuntimeException e) {
                markPlacementFailed(plan.placement);
                throw e;
            }
        });
    }

    private PlacementPlan preparePlacement(CreateNsxTier1GatewayCommand cmd, Long sourceNatVlanId) {
        PlacementPlan plan = reservePlacement(cmd.getZoneId(), cmd.getAccountId(), cmd.getDomainId(),
                cmd.isResourceVpc() ? cmd.getNetworkResourceId() : null,
                cmd.isResourceVpc() ? null : cmd.getNetworkResourceId());
        NsxVrfGatewayVO gateway = plan.gateway;
        if (gateway != null) {
            cmd.setTier0Gateway(gateway.getNsxTier0Name());
            cmd.setEdgeCluster(gateway.getEdgeCluster());
            if (cmd.isSourceNatEnabled() && sourceNatVlanId != null
                    && !Objects.equals(gateway.getPublicVlanDbId(), sourceNatVlanId)) {
                throw new CloudRuntimeException(String.format(
                        "The source NAT IP for %s must come from public IP range %s assigned to NSX VRF gateway %s",
                        cmd.getNetworkResourceName(), gateway.getPublicVlanDbId(), gateway.getNsxTier0Name()));
            }
        } else {
            cmd.setTier0Gateway(plan.placement.getTier0Name());
        }
        plan.placement.setState(NsxVrfGatewayPlacementVO.State.PENDING_CREATE);
        updatePlacement(plan.placement);
        return plan;
    }

    private PlacementPlan reservePlacement(long zoneId, long accountId, long domainId, Long vpcId, Long networkId) {
        NsxVrfGatewayPlacementVO placement = vpcId != null
                ? nsxVrfGatewayPlacementDao.findByVpcId(vpcId)
                : nsxVrfGatewayPlacementDao.findByNetworkId(networkId);
        if (placement != null) {
            validatePlacementOwner(placement, zoneId, accountId, domainId);
            if (NsxVrfGatewayPlacementVO.State.PENDING_DELETE.name().equals(placement.getState())) {
                throw new CloudRuntimeException("The NSX Tier-1 placement is pending deletion");
            }
            NsxVrfGatewayVO gateway = placement.getGatewayId() == null
                    ? null : nsxVrfGatewayDao.findById(placement.getGatewayId());
            if (placement.getGatewayId() != null && gateway == null) {
                throw new CloudRuntimeException("The NSX VRF gateway recorded for this Tier-1 placement is unavailable");
            }
            return new PlacementPlan(gateway, placement);
        }

        NsxVrfGatewayVO gateway = resolveVrfGateway(zoneId, accountId, domainId);
        String tier0Name = gateway == null ? getSharedTier0(zoneId) : gateway.getNsxTier0Name();
        placement = new NsxVrfGatewayPlacementVO(gateway == null ? null : gateway.getId(), zoneId, domainId,
                accountId, vpcId, networkId, tier0Name);
        placement = nsxVrfGatewayPlacementDao.persist(placement);
        if (placement == null) {
            throw new CloudRuntimeException("Failed to persist the NSX Tier-1 placement");
        }
        return new PlacementPlan(gateway, placement);
    }

    private void validatePlacementOwner(NsxVrfGatewayPlacementVO placement, long zoneId, long accountId,
            long domainId) {
        if (placement.getZoneId() != zoneId || placement.getDomainId() != domainId
                || placement.getAccountId() != accountId) {
            throw new CloudRuntimeException("The NSX Tier-1 placement does not belong to the requested tenant");
        }
    }

    private String getSharedTier0(long zoneId) {
        NsxProviderVO provider = nsxProviderDao.findByZoneId(zoneId);
        if (provider == null || StringUtils.isBlank(provider.getTier0Gateway())) {
            throw new CloudRuntimeException(String.format("Zone %s has no configured NSX Tier-0 gateway", zoneId));
        }
        return provider.getTier0Gateway();
    }

    private void validateVrfGatewayForPlacement(NsxVrfGatewayVO gateway) {
        ValidateNsxVrfGatewayCommand command = new ValidateNsxVrfGatewayCommand(gateway.getZoneId(),
                gateway.getNsxTier0Name(), gateway.getParentTier0(), gateway.getEdgeCluster());
        NsxAnswer answer = nsxControllerUtils.sendNsxCommandForResult(command, gateway.getZoneId());
        if (!(answer instanceof NsxVrfGatewayValidationAnswer) || !answer.getResult()) {
            String details = answer == null ? "no answer was returned" : answer.getDetails();
            throw new CloudRuntimeException(String.format(
                    "NSX VRF gateway %s failed placement validation: %s",
                    gateway.getNsxTier0Name(), details));
        }
    }

    private boolean deleteTier1GatewayWithPlacement(DeleteNsxTier1GatewayCommand command, Long vpcId, Long networkId) {
        boolean isVpc = vpcId != null;
        long resourceId = isVpc ? vpcId : networkId;
        return nsxVrfGatewayLockManager.withPlacementLock(isVpc, resourceId, () -> {
            NsxVrfGatewayPlacementVO placement = nsxVrfGatewayLockManager.withZoneLock(command.getZoneId(),
                    () -> preparePlacementForDeletion(command, vpcId, networkId));
            try {
                NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, command.getZoneId());
                if (!result.getResult()) {
                    throw new CloudRuntimeException(String.format(
                            "Failed to delete NSX Tier-1 gateway: %s", result.getDetails()));
                }
                if (placement != null && !nsxVrfGatewayPlacementDao.expunge(placement.getId())) {
                    throw new CloudRuntimeException(String.format(
                            "Failed to remove NSX Tier-1 placement %s after backend deletion", placement.getId()));
                }
                return true;
            } catch (RuntimeException e) {
                if (placement != null) {
                    markPlacementFailed(placement);
                }
                throw e;
            }
        });
    }

    private NsxVrfGatewayPlacementVO preparePlacementForDeletion(DeleteNsxTier1GatewayCommand command,
            Long vpcId, Long networkId) {
        NsxVrfGatewayPlacementVO placement = vpcId != null
                ? nsxVrfGatewayPlacementDao.findByVpcId(vpcId)
                : nsxVrfGatewayPlacementDao.findByNetworkId(networkId);
        if (placement != null) {
            if (placement.getZoneId() != command.getZoneId()
                    || placement.getAccountId() != command.getAccountId()
                    || placement.getDomainId() != command.getDomainId()) {
                throw new CloudRuntimeException("The NSX Tier-1 placement does not belong to the requested tenant");
            }
            placement.setState(NsxVrfGatewayPlacementVO.State.PENDING_DELETE);
            updatePlacement(placement);
        }
        return placement;
    }

    private void updatePlacement(NsxVrfGatewayPlacementVO placement) {
        placement.setUpdated(new Date());
        if (!nsxVrfGatewayPlacementDao.update(placement.getId(), placement)) {
            throw new CloudRuntimeException(String.format(
                    "Failed to update NSX Tier-1 placement %s", placement.getId()));
        }
    }

    private void markPlacementFailed(NsxVrfGatewayPlacementVO placement) {
        placement.setState(NsxVrfGatewayPlacementVO.State.FAILED);
        placement.setUpdated(new Date());
        if (!nsxVrfGatewayPlacementDao.update(placement.getId(), placement)) {
            logger.error("Failed to mark NSX Tier-1 placement {} as failed", placement.getId());
        }
    }

    private static class PlacementPlan {
        private final NsxVrfGatewayVO gateway;
        private final NsxVrfGatewayPlacementVO placement;

        PlacementPlan(NsxVrfGatewayVO gateway, NsxVrfGatewayPlacementVO placement) {
            this.gateway = gateway;
            this.placement = placement;
        }
    }

    protected NsxVrfGatewayVO resolveVrfGateway(Long zoneId, long accountId, long domainId) {
        if (zoneId == null) {
            return null;
        }
        String scope = getVrfScope(zoneId);
        if (StringUtils.isBlank(scope) || NSX_VRF_SCOPE_NONE.equalsIgnoreCase(scope)) {
            return null;
        }

        NsxVrfGatewayVO gateway = null;
        if (NsxVrfGatewayVO.Scope.ACCOUNT.name().equalsIgnoreCase(scope)) {
            gateway = nsxVrfGatewayDao.findByAccount(zoneId, accountId);
        }
        if (gateway == null) {
            gateway = findVrfGatewayForDomainChain(zoneId, domainId);
        }
        if (gateway != null) {
            logger.debug("Resolved NSX VRF gateway {} for account {} in zone {}", gateway, accountId, zoneId);
            return gateway;
        }
        if (isVrfFallbackToSharedTier0Allowed(zoneId)) {
            logger.warn("Account {} has no NSX VRF gateway in zone {}; using the shared Tier-0 because {} is enabled",
                    accountId, zoneId, NSX_VRF_FALLBACK_TO_SHARED_TIER0.key());
            return null;
        }
        throw new CloudRuntimeException(String.format(
                "Zone %s uses %s=%s, but account %s has no NSX VRF gateway. Assign one or enable %s",
                zoneId, NSX_VRF_SCOPE.key(), scope, accountId, NSX_VRF_FALLBACK_TO_SHARED_TIER0.key()));
    }

    protected String getVrfScope(long zoneId) {
        return NSX_VRF_SCOPE.valueIn(zoneId);
    }

    protected boolean isVrfFallbackToSharedTier0Allowed(long zoneId) {
        return Boolean.TRUE.equals(NSX_VRF_FALLBACK_TO_SHARED_TIER0.valueIn(zoneId));
    }

    private NsxVrfGatewayVO findVrfGatewayForDomainChain(long zoneId, long domainId) {
        DomainVO domain = domainDao.findById(domainId);
        while (domain != null) {
            NsxVrfGatewayVO gateway = nsxVrfGatewayDao.findByDomain(zoneId, domain.getId());
            if (gateway != null) {
                return gateway;
            }
            Long parentId = domain.getParent();
            domain = parentId == null ? null : domainDao.findById(parentId);
        }
        return null;
    }

    @Override
    public boolean updateVpcSourceNatIp(Vpc vpc, IpAddress address) {
        if (vpc == null || address == null) {
            return false;
        }
        long accountId = vpc.getAccountId();
        long domainId = vpc.getDomainId();
        long zoneId = vpc.getZoneId();
        long vpcId = vpc.getId();

        validatePublicIpVlan(zoneId, accountId, domainId, vpcId, null, address.getVlanId());

        logger.debug("Updating the source NAT IP for NSX VPC {} to IP: {}", vpc, address.getAddress().addr());
        String tier1GatewayName = NsxControllerUtils.getTier1GatewayName(domainId, accountId, zoneId, vpcId, true);
        String sourceNatRuleId = NsxControllerUtils.getNsxNatRuleId(domainId, accountId, zoneId, vpcId, true);
        CreateOrUpdateNsxTier1NatRuleCommand cmd = NsxHelper.createOrUpdateNsxNatRuleCommand(domainId, accountId, zoneId, tier1GatewayName, "SNAT", address.getAddress().addr(), sourceNatRuleId);
        NsxAnswer answer = nsxControllerUtils.sendNsxCommand(cmd, zoneId);
        if (!answer.getResult()) {
            logger.error("Could not update the source NAT IP address for VPC {}: {}", vpc, answer.getDetails());
            return false;
        }
        return true;
    }

    @Override
    public Long reserveTier1PlacementAndGetPublicVlanId(long zoneId, long accountId, long domainId, Long vpcId,
            Long networkId) {
        if ((vpcId == null) == (networkId == null)) {
            throw new IllegalArgumentException("Exactly one of vpcId or networkId is required");
        }
        long resourceId = vpcId != null ? vpcId : networkId;
        return nsxVrfGatewayLockManager.withPlacementLock(vpcId != null, resourceId,
                () -> nsxVrfGatewayLockManager.withZoneLock(zoneId, () -> {
                    PlacementPlan plan = reservePlacement(zoneId, accountId, domainId, vpcId, networkId);
                    return plan.gateway == null ? null : plan.gateway.getPublicVlanDbId();
                }));
    }

    @Override
    public Long getPublicVlanId(long zoneId, long accountId, long domainId, Long vpcId, Long networkId) {
        if ((vpcId == null) == (networkId == null)) {
            throw new IllegalArgumentException("Exactly one of vpcId or networkId is required");
        }
        long resourceId = vpcId != null ? vpcId : networkId;
        return nsxVrfGatewayLockManager.withPlacementLock(vpcId != null, resourceId, () -> {
            PlacementPlan plan = getRecordedPlacement(zoneId, accountId, domainId, vpcId, networkId);
            return plan.gateway == null ? null : plan.gateway.getPublicVlanDbId();
        });
    }

    private PlacementPlan getRecordedPlacement(long zoneId, long accountId, long domainId, Long vpcId,
            Long networkId) {
        NsxVrfGatewayPlacementVO placement = vpcId != null
                ? nsxVrfGatewayPlacementDao.findByVpcId(vpcId)
                : nsxVrfGatewayPlacementDao.findByNetworkId(networkId);
        if (placement == null) {
            throw new CloudRuntimeException(
                    "The NSX Tier-1 has no recorded placement; backfill its current Tier-0 placement before continuing");
        }
        validatePlacementOwner(placement, zoneId, accountId, domainId);
        if (NsxVrfGatewayPlacementVO.State.FAILED.name().equals(placement.getState())) {
            throw new CloudRuntimeException("The NSX Tier-1 placement is in a failed state");
        }
        if (NsxVrfGatewayPlacementVO.State.PENDING_DELETE.name().equals(placement.getState())) {
            throw new CloudRuntimeException("The NSX Tier-1 placement is pending deletion");
        }
        NsxVrfGatewayVO gateway = placement.getGatewayId() == null
                ? null : nsxVrfGatewayDao.findById(placement.getGatewayId());
        if (placement.getGatewayId() != null && gateway == null) {
            throw new CloudRuntimeException("The NSX VRF gateway recorded for this Tier-1 placement is unavailable");
        }
        return new PlacementPlan(gateway, placement);
    }

    @Override
    public void validatePublicIpVlan(long zoneId, long accountId, long domainId, Long vpcId, Long networkId,
            long vlanId) {
        Long expectedVlanId = getPublicVlanId(zoneId, accountId, domainId, vpcId, networkId);
        if (expectedVlanId != null && expectedVlanId != vlanId) {
            throw new CloudRuntimeException(String.format(
                    "Public IP range %s does not belong to the NSX VRF gateway used by this Tier-1", vlanId));
        }
    }

    @Override
    public boolean createNetwork(Long zoneId, long accountId, long domainId, Long networkId, String networkName,
                                 boolean sourceNatEnabled) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, networkId, networkName, false, sourceNatEnabled);
        return createTier1GatewayWithPlacement(createNsxTier1GatewayCommand, null);
    }

    public boolean deleteVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName) {
        DeleteNsxTier1GatewayCommand deleteNsxTier1GatewayCommand =
                new DeleteNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true);
        return deleteTier1GatewayWithPlacement(deleteNsxTier1GatewayCommand, vpcId, null);
    }

    public boolean deleteNetwork(long zoneId, long accountId, long domainId, NetworkVO network) {
        String vpcName = null;
        if (Objects.nonNull(network.getVpcId())) {
            VpcVO vpc = vpcDao.findById(network.getVpcId());
            vpcName = Objects.nonNull(vpc) ? vpc.getName() : null;
        }
        DeleteNsxSegmentCommand deleteNsxSegmentCommand = new DeleteNsxSegmentCommand(domainId, accountId, zoneId,
                network.getVpcId(), vpcName, network.getId(), network.getName());
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxSegmentCommand, network.getDataCenterId());
        if (!result.getResult()) {
            String msg = String.format("Could not remove the NSX segment for network %s: %s", network, result.getDetails());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }

        if (Objects.isNull(network.getVpcId())) {
            DeleteNsxTier1GatewayCommand deleteNsxTier1GatewayCommand = new DeleteNsxTier1GatewayCommand(domainId, accountId, zoneId, network.getId(), network.getName(), false);
            return deleteTier1GatewayWithPlacement(deleteNsxTier1GatewayCommand, null, network.getId());
        }
        return result.getResult();
    }

    public boolean createStaticNatRule(long zoneId, long domainId, long accountId, Long networkResourceId, String networkResourceName,
                                       boolean isVpcResource, long vmId, String publicIp, String vmIp) {
        CreateNsxStaticNatCommand createNsxStaticNatCommand = new CreateNsxStaticNatCommand(domainId, accountId, zoneId,
                networkResourceId, networkResourceName, isVpcResource, vmId, publicIp, vmIp);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxStaticNatCommand, zoneId);
        return result.getResult();
    }

    public boolean deleteStaticNatRule(long zoneId, long domainId, long accountId, Long networkResourceId, String networkResourceName,
                                       boolean isVpcResource) {
        DeleteNsxNatRuleCommand deleteNsxStaticNatCommand = new DeleteNsxNatRuleCommand(domainId, accountId, zoneId,
                networkResourceId, networkResourceName, isVpcResource, null, null, null, null);
        deleteNsxStaticNatCommand.setService(Network.Service.StaticNat);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxStaticNatCommand, zoneId);
        return result.getResult();
    }

    public NsxAnswer createPortForwardRule(NsxNetworkRule nsxNetRule) {
        SDNProviderNetworkRule netRule = nsxNetRule.getBaseRule();
        // TODO: if port doesn't exist in default list of services, create a service entry
        CreateNsxPortForwardRuleCommand createPortForwardCmd = new CreateNsxPortForwardRuleCommand(netRule.getDomainId(),
                netRule.getAccountId(), netRule.getZoneId(), netRule.getNetworkResourceId(),
                netRule.getNetworkResourceName(), netRule.isVpcResource(), netRule.getVmId(), netRule.getRuleId(),
                netRule.getPublicIp(), netRule.getVmIp(), netRule.getPublicPort(), netRule.getPrivatePort(), netRule.getProtocol());
        return nsxControllerUtils.sendNsxCommand(createPortForwardCmd, netRule.getZoneId());
    }

    public boolean deletePortForwardRule(NsxNetworkRule nsxNetRule) {
        SDNProviderNetworkRule netRule = nsxNetRule.getBaseRule();
        DeleteNsxNatRuleCommand deleteCmd = new DeleteNsxNatRuleCommand(netRule.getDomainId(),
                netRule.getAccountId(), netRule.getZoneId(), netRule.getNetworkResourceId(),
                netRule.getNetworkResourceName(), netRule.isVpcResource(),  netRule.getVmId(), netRule.getRuleId(), netRule.getPrivatePort(), netRule.getProtocol());
        deleteCmd.setService(Network.Service.PortForwarding);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteCmd, netRule.getZoneId());
        return result.getResult();
    }

    public boolean createLbRule(NsxNetworkRule nsxNetRule) {
        SDNProviderNetworkRule netRule = nsxNetRule.getBaseRule();
        CreateNsxLoadBalancerRuleCommand command = new CreateNsxLoadBalancerRuleCommand(netRule.getDomainId(),
                netRule.getAccountId(), netRule.getZoneId(), netRule.getNetworkResourceId(),
                netRule.getNetworkResourceName(), netRule.isVpcResource(),  nsxNetRule.getMemberList(), netRule.getRuleId(),
                netRule.getPublicPort(), netRule.getPrivatePort(), netRule.getAlgorithm(), netRule.getProtocol());
        command.setPublicIp(netRule.getPublicIp());
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, netRule.getZoneId());
        return result.getResult();
    }

    public boolean deleteLbRule(NsxNetworkRule nsxNetRule) {
        SDNProviderNetworkRule netRule = nsxNetRule.getBaseRule();
        DeleteNsxLoadBalancerRuleCommand command = new DeleteNsxLoadBalancerRuleCommand(netRule.getDomainId(),
                netRule.getAccountId(), netRule.getZoneId(), netRule.getNetworkResourceId(),
                netRule.getNetworkResourceName(), netRule.isVpcResource(),  nsxNetRule.getMemberList(), netRule.getRuleId(),
                netRule.getVmId());
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, netRule.getZoneId());
        return result.getResult();
    }

    public boolean addFirewallRules(Network network, List<NsxNetworkRule> netRules) {
        CreateNsxDistributedFirewallRulesCommand command = new CreateNsxDistributedFirewallRulesCommand(network.getDomainId(),
                network.getAccountId(), network.getDataCenterId(), network.getVpcId(), network.getId(), netRules);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, network.getDataCenterId());
        return result.getResult();
    }

    public boolean deleteFirewallRules(Network network, List<NsxNetworkRule> netRules) {
        DeleteNsxDistributedFirewallRulesCommand command = new DeleteNsxDistributedFirewallRulesCommand(network.getDomainId(),
                network.getAccountId(), network.getDataCenterId(), network.getVpcId(), network.getId(), netRules);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, network.getDataCenterId());
        return result.getResult();
    }

    public NsxVpnGatewayResult createVpnGateway(Vpc vpc, String localEndpointIp) {
        CreateNsxVpnGatewayCommand createNsxVpnGatewayCommand = new CreateNsxVpnGatewayCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), localEndpointIp);
        NsxAnswer result = nsxControllerUtils.sendNsxCommandForResult(createNsxVpnGatewayCommand, vpc.getZoneId());
        return new NsxVpnGatewayResult(result.getResult(), result.isEndpointMayBeInUse());
    }

    public boolean deleteVpnGateway(Vpc vpc) {
        DeleteNsxVpnGatewayCommand deleteNsxVpnGatewayCommand = new DeleteNsxVpnGatewayCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName());
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxVpnGatewayCommand, vpc.getZoneId());
        return result.getResult();
    }

    public boolean createVpnConnection(Vpc vpc, String connectionUuid, String peerAddress, String psk,
                                       String ikePolicy, String espPolicy, Long ikeLifetime, Long espLifetime,
                                       boolean dpdEnabled, String ikeVersion, boolean passive, List<String> peerCidrs,
                                       String vtiLocalIp, String vtiPeerIp, int vtiPrefixLength, String localEndpointIp) {
        CreateNsxVpnConnectionCommand createNsxVpnConnectionCommand = new CreateNsxVpnConnectionCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), connectionUuid, peerAddress, psk,
                ikePolicy, espPolicy, ikeLifetime, espLifetime, dpdEnabled, ikeVersion, passive, peerCidrs,
                vtiLocalIp, vtiPeerIp, vtiPrefixLength, vpc.getCidr(), localEndpointIp);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxVpnConnectionCommand, vpc.getZoneId());
        return result.getResult();
    }

    public boolean deleteVpnConnection(Vpc vpc, String connectionUuid) {
        DeleteNsxVpnConnectionCommand deleteNsxVpnConnectionCommand = new DeleteNsxVpnConnectionCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), connectionUuid);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxVpnConnectionCommand, vpc.getZoneId());
        return result.getResult();
    }

    public boolean updateVpnConnectionState(Vpc vpc, String connectionUuid, boolean enabled) {
        UpdateNsxVpnConnectionStateCommand command = new UpdateNsxVpnConnectionStateCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), connectionUuid, enabled);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(command, vpc.getZoneId());
        return result.getResult();
    }

    public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
        GetNsxVpnSessionStatusCommand getNsxVpnSessionStatusCommand = new GetNsxVpnSessionStatusCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), connectionUuid);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(getNsxVpnSessionStatusCommand, vpc.getZoneId());
        return result.getDetails();
    }

    /**
     * Every management server runs this poller over VPN connections whose gateway has persisted
     * NSX ownership; duplicate polling in a multi-server setup is tolerated, as state transitions
     * are serialized by the row lock in transitionVpnConnectionState
     */
    protected class VpnStatusPollTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                Set<Long> polledConnectionIds = new HashSet<>();
                List<Site2SiteVpnConnectionVO> connections = site2SiteVpnConnectionDao.listByStates(
                        VPN_POLLED_STATES.toArray(new Site2SiteVpnConnection.State[0]));
                for (Site2SiteVpnConnectionVO connection : connections) {
                    Site2SiteVpnGatewayVO vpnGateway = site2SiteVpnGatewayDao.findById(connection.getVpnGatewayId());
                    if (vpnGateway == null) {
                        continue;
                    }
                    VpcVO vpc = vpcDao.findById(vpnGateway.getVpcId());
                    if (vpc == null || !isVpnProvidedByNsx(vpc, vpnGateway)) {
                        continue;
                    }
                    polledConnectionIds.add(connection.getId());
                    pollVpnConnectionStatus(connection, vpc);
                }
                // Drop the failure counters of connections that were deleted or left the polled states
                vpnStatusPollFailures.keySet().retainAll(polledConnectionIds);
            } catch (Exception e) {
                logger.warn("Failed to poll the status of the NSX Site-to-Site VPN connections: {}", e.getMessage(), e);
            }
        }
    }

    private boolean isVpnProvidedByNsx(Vpc vpc, Site2SiteVpnGatewayVO vpnGateway) {
        return vpc != null
                && userIpAddressDetailsDao != null
                && vpnGateway != null
                && userIpAddressDetailsDao.findDetail(vpnGateway.getAddrId(), NsxElement.NSX_VPN_GATEWAY_IP_DETAIL) != null;
    }

    protected void pollVpnConnectionStatus(Site2SiteVpnConnectionVO connection, VpcVO vpc) {
        Site2SiteVpnConnection.State observedState = connection.getState();
        String status;
        try {
            status = getVpnConnectionStatus(vpc, connection.getUuid());
            vpnStatusPollFailures.remove(connection.getId());
        } catch (Exception e) {
            int failures = vpnStatusPollFailures.merge(connection.getId(), 1, Integer::sum);
            logger.warn("Failed to get the status of the NSX VPN connection {} of VPC {} ({} consecutive failure(s)): {}",
                    connection, vpc, failures, e.getMessage());
            if (failures >= VPN_STATUS_POLL_FAILURE_THRESHOLD) {
                // A failed status query says nothing about the tunnel itself: alert, but never
                // transition the connection state on management-plane errors
                String title = String.format("Unable to poll the status of Site-to-site Vpn Connection %s", connection.getUuid());
                String context = String.format(
                        "The status of Site-to-site Vpn Connection %s on the NSX tier-1 gateway of VPC %s could not be polled %d consecutive times; its state %s is left unchanged",
                        connection.getUuid(), vpc.getName(), failures, observedState);
                logger.warn(context);
                alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, vpc.getZoneId(), null, title, context);
                vpnStatusPollFailures.remove(connection.getId());
            }
            return;
        }
        Site2SiteVpnConnection.State newState;
        if (VPN_SESSION_STATUS_UP.equals(status)) {
            newState = Site2SiteVpnConnection.State.Connected;
        } else if (VPN_SESSION_STATUS_DOWN.equals(status) || VPN_SESSION_STATUS_DEGRADED.equals(status)) {
            newState = Site2SiteVpnConnection.State.Disconnected;
        } else if (VPN_SESSION_STATUS_NOT_FOUND.equals(status)) {
            if (observedState == Site2SiteVpnConnection.State.Pending
                    || observedState == Site2SiteVpnConnection.State.Connecting) {
                // the async connection job may still be creating the session on NSX
                return;
            }
            if (observedState == Site2SiteVpnConnection.State.Disconnected) {
                // stop intentionally disables the session; a subsequent status lookup may report it
                // as absent while the connection remains a valid, stopped CloudStack resource
                return;
            }
            // an established session vanished from NSX: flag the connection for a manual reset
            newState = Site2SiteVpnConnection.State.Error;
        } else {
            logger.debug("NSX VPN connection {} of VPC {} reported the status {}, not transitioning the state", connection, vpc, status);
            return;
        }
        transitionVpnConnectionState(connection, vpc, observedState, newState);
    }

    protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                Site2SiteVpnConnection.State observedState,
                                                Site2SiteVpnConnection.State newState) {
        if (observedState == newState) {
            return;
        }
        Site2SiteVpnConnectionVO lock = site2SiteVpnConnectionDao.acquireInLockTable(connection.getId());
        if (lock == null) {
            logger.warn("Unable to acquire the lock for the NSX Site-to-Site VPN connection {}, not updating its state", connection);
            return;
        }
        try {
            Site2SiteVpnConnectionVO lockedConnection = site2SiteVpnConnectionDao.findById(connection.getId());
            if (lockedConnection == null || !VPN_POLLED_STATES.contains(lockedConnection.getState())
                    || lockedConnection.getState() != observedState) {
                return;
            }
            Site2SiteVpnConnection.State oldState = lockedConnection.getState();
            lockedConnection.setState(newState);
            site2SiteVpnConnectionDao.persist(lockedConnection);
            vpnStatusPollFailures.remove(lockedConnection.getId());
            String title = String.format("Site-to-site Vpn Connection %s just switched from %s to %s", lockedConnection.getUuid(), oldState, newState);
            String context = String.format("Site-to-site Vpn Connection %s on the NSX tier-1 gateway of VPC %s just switched from %s to %s",
                    lockedConnection.getUuid(), vpc.getName(), oldState, newState);
            logger.info(context);
            alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_DOMAIN_ROUTER, vpc.getZoneId(), null, title, context);
        } finally {
            site2SiteVpnConnectionDao.releaseFromLockTable(lock.getId());
        }
    }

    @Override
    public String getConfigComponentName() {
        return NsxApiClient.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {
            NSX_API_FAILURE_RETRIES, NSX_API_FAILURE_INTERVAL, NSX_VPN_STATUS_POLL_INTERVAL,
            NSX_VRF_SCOPE, NSX_VRF_FALLBACK_TO_SHARED_TIER0
        };
    }

    @Override
    public String getSegmentId(long domainId, long accountId, long zoneId, Long vpcId, long networkId) {
        return NsxControllerUtils.getNsxSegmentId(domainId, accountId, zoneId, vpcId, networkId);
    }
}
