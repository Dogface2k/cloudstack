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
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.cloudstack.resource.NsxNetworkRule;
import org.apache.cloudstack.utils.NsxControllerUtils;
import org.apache.cloudstack.utils.NsxHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.alert.AlertManager;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.SDNProviderNetworkRule;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.Site2SiteVpnGatewayVO;
import com.cloud.network.nsx.NsxService;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.dao.NsxVrfGatewayDao;
import com.cloud.network.element.NsxVrfGatewayVO;
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
                    "gateways to the zone-wide Tier-0, which is the behaviour before per-tenant VRFs existed. " +
                    "ACCOUNT or DOMAIN instead attaches them to the VRF (or dedicated) Tier-0 registered for " +
                    "that tenant, giving each tenant its own routing table",
            true, ConfigKey.Scope.Zone, null, null, null, null, null,
            ConfigKey.Kind.Select, "NONE,ACCOUNT,DOMAIN");

    public static final ConfigKey<Boolean> NSX_VRF_FALLBACK_TO_SHARED_TIER0 = new ConfigKey<>("Advanced", Boolean.class,
            "nsx.vrf.fallback.to.shared.tier0", "false",
            "Only consulted when nsx.vrf.scope is not NONE. When false, creating a network or VPC for a tenant " +
                    "with no NSX VRF gateway registered fails. When true, such tenants silently fall back to the " +
                    "zone-wide Tier-0 — convenient, but it puts them back in the shared routing table",
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
            Site2SiteVpnConnection.State.Connecting, Site2SiteVpnConnection.State.Connected,
            Site2SiteVpnConnection.State.Disconnected);

    @Inject
    NsxControllerUtils nsxControllerUtils;
    @Inject
    VpcDao vpcDao;
    @Inject
    VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Inject
    Site2SiteVpnConnectionDao site2SiteVpnConnectionDao;
    @Inject
    Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;
    @Inject
    AlertManager alertManager;
    @Inject
    NsxVrfGatewayDao nsxVrfGatewayDao;
    @Inject
    DomainDao domainDao;

    protected Logger logger = LogManager.getLogger(getClass());

    private ScheduledExecutorService vpnStatusPollExecutor;
    private final Map<Long, Integer> vpnStatusPollFailures = new ConcurrentHashMap<>();

    @Override
    public boolean configure(String name, Map<String, Object> params) throws ConfigurationException {
        vpnStatusPollExecutor = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("Nsx-Vpn-Status-Poll"));
        return true;
    }

    @Override
    public boolean start() {
        Integer configuredInterval = NSX_VPN_STATUS_POLL_INTERVAL.value();
        int pollInterval = Objects.isNull(configuredInterval) ? VPN_STATUS_POLL_DEFAULT_INTERVAL : configuredInterval;
        if (pollInterval < VPN_STATUS_POLL_MIN_INTERVAL) {
            logger.warn("The configured value {} of {} is below the minimum of {} seconds, using the default of {} seconds",
                    configuredInterval, NSX_VPN_STATUS_POLL_INTERVAL.key(), VPN_STATUS_POLL_MIN_INTERVAL, VPN_STATUS_POLL_DEFAULT_INTERVAL);
            pollInterval = VPN_STATUS_POLL_DEFAULT_INTERVAL;
        }
        vpnStatusPollExecutor.scheduleWithFixedDelay(new VpnStatusPollTask(), pollInterval, pollInterval, TimeUnit.SECONDS);
        return true;
    }

    @Override
    public boolean stop() {
        if (Objects.nonNull(vpnStatusPollExecutor)) {
            vpnStatusPollExecutor.shutdownNow();
        }
        return true;
    }

    public boolean createVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName, boolean sourceNatEnabled) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true, sourceNatEnabled);
        applyVrfGateway(createNsxTier1GatewayCommand, zoneId, accountId, domainId);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxTier1GatewayCommand, zoneId);
        return result.getResult();
    }

    /**
     * Points the new Tier-1 gateway at the tenant's own Tier-0 and edge cluster, when the
     * zone is VRF-segregated and the tenant has one registered. Leaves the command
     * untouched otherwise, so the resource falls back to the zone-wide values.
     */
    protected void applyVrfGateway(CreateNsxTier1GatewayCommand cmd, Long zoneId, long accountId, long domainId) {
        NsxVrfGatewayVO gateway = resolveVrfGateway(zoneId, accountId, domainId);
        if (gateway == null) {
            return;
        }
        cmd.setTier0Gateway(gateway.getNsxTier0Name());
        cmd.setEdgeCluster(gateway.getEdgeCluster());
    }

    /**
     * Finds the NSX VRF gateway a tenant's Tier-1 gateways should attach to.
     *
     * Resolution order is exact account, then the tenant's domain, then each ancestor
     * domain in turn — so a reseller can register one routing domain per customer rather
     * than one per CloudStack account.
     *
     * @return the tenant's gateway, or null when the zone is not VRF-segregated or when
     *         no gateway is registered and falling back to the shared Tier-0 is allowed
     * @throws CloudRuntimeException when the zone is VRF-segregated, the tenant has no
     *         gateway, and fallback is disabled — silently dropping a tenant into the
     *         shared routing table of a zone declared segregated is the worse failure
     */
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
            logger.warn("Zone {} has {}={} but account {} has no NSX VRF gateway registered; falling back to the " +
                            "zone-wide tier 0 because {} is enabled. This tenant shares a routing table with the others",
                    zoneId, NSX_VRF_SCOPE.key(), scope, accountId, NSX_VRF_FALLBACK_TO_SHARED_TIER0.key());
            return null;
        }
        throw new CloudRuntimeException(String.format(
                "Zone %s is segregated by %s=%s but account %s has no NSX VRF gateway registered. Register one with " +
                        "addNsxVrfGateway and assign it, or enable %s to allow the shared tier 0",
                zoneId, NSX_VRF_SCOPE.key(), scope, accountId, NSX_VRF_FALLBACK_TO_SHARED_TIER0.key()));
    }

    /** Seam over the zone-scoped setting, so the resolution logic is unit-testable. */
    protected String getVrfScope(long zoneId) {
        return NSX_VRF_SCOPE.valueIn(zoneId);
    }

    /** Seam over the zone-scoped setting, so the resolution logic is unit-testable. */
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

    public boolean createNetwork(Long zoneId, long accountId, long domainId, Long networkId, String networkName) {
        CreateNsxTier1GatewayCommand createNsxTier1GatewayCommand =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, networkId, networkName, false, false);
        applyVrfGateway(createNsxTier1GatewayCommand, zoneId, accountId, domainId);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxTier1GatewayCommand, zoneId);
        return result.getResult();
    }

    public boolean deleteVpcNetwork(Long zoneId, long accountId, long domainId, Long vpcId, String vpcName) {
        DeleteNsxTier1GatewayCommand deleteNsxTier1GatewayCommand =
                new DeleteNsxTier1GatewayCommand(domainId, accountId, zoneId, vpcId, vpcName, true);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(deleteNsxTier1GatewayCommand, zoneId);
        return result.getResult();
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
            result = nsxControllerUtils.sendNsxCommand(deleteNsxTier1GatewayCommand, zoneId);
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

    public boolean createVpnGateway(Vpc vpc, String localEndpointIp) {
        CreateNsxVpnGatewayCommand createNsxVpnGatewayCommand = new CreateNsxVpnGatewayCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), localEndpointIp);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(createNsxVpnGatewayCommand, vpc.getZoneId());
        return result.getResult();
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

    public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
        GetNsxVpnSessionStatusCommand getNsxVpnSessionStatusCommand = new GetNsxVpnSessionStatusCommand(vpc.getDomainId(),
                vpc.getAccountId(), vpc.getZoneId(), vpc.getId(), vpc.getName(), connectionUuid);
        NsxAnswer result = nsxControllerUtils.sendNsxCommand(getNsxVpnSessionStatusCommand, vpc.getZoneId());
        return result.getDetails();
    }

    /**
     * Every management server runs this poller over all NSX-provided VPN connections; the
     * duplicate polling in a multi-server setup is tolerated, as state transitions are
     * serialized by the row lock in transitionVpnConnectionState
     */
    protected class VpnStatusPollTask extends ManagedContextRunnable {
        @Override
        protected void runInContext() {
            try {
                Set<Long> polledConnectionIds = new HashSet<>();
                List<Site2SiteVpnConnectionVO> connections = site2SiteVpnConnectionDao.listAll();
                for (Site2SiteVpnConnectionVO connection : connections) {
                    if (!VPN_POLLED_STATES.contains(connection.getState())) {
                        continue;
                    }
                    Site2SiteVpnGatewayVO vpnGateway = site2SiteVpnGatewayDao.findById(connection.getVpnGatewayId());
                    if (vpnGateway == null) {
                        continue;
                    }
                    VpcVO vpc = vpcDao.findById(vpnGateway.getVpcId());
                    if (vpc == null || vpcOfferingServiceMapDao.findByServiceProviderAndOfferingId(
                            Network.Service.Vpn.getName(), Network.Provider.Nsx.getName(), vpc.getVpcOfferingId()) == null) {
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

    protected void pollVpnConnectionStatus(Site2SiteVpnConnectionVO connection, VpcVO vpc) {
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
                        connection.getUuid(), vpc.getName(), failures, connection.getState());
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
            if (connection.getState() == Site2SiteVpnConnection.State.Connecting) {
                // the async connection job may still be creating the session on NSX
                return;
            }
            // an established session vanished from NSX: flag the connection for a manual reset
            newState = Site2SiteVpnConnection.State.Error;
        } else {
            logger.debug("NSX VPN connection {} of VPC {} reported the status {}, not transitioning the state", connection, vpc, status);
            return;
        }
        transitionVpnConnectionState(connection, vpc, newState);
    }

    protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc, Site2SiteVpnConnection.State newState) {
        if (connection.getState() == newState) {
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
                    || lockedConnection.getState() == newState) {
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
