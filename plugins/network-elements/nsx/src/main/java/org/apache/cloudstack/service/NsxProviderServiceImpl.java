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

import com.amazonaws.util.CollectionUtils;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.network.Network;
import com.cloud.network.Networks;
import com.cloud.network.nsx.NsxProvider;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.common.annotations.VisibleForTesting;
import org.apache.cloudstack.api.command.DeleteNsxControllerCmd;
import org.apache.cloudstack.api.command.ListNsxControllersCmd;
import org.apache.cloudstack.api.BaseResponse;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.dao.NsxVrfGatewayDao;
import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import org.apache.cloudstack.api.command.AddNsxControllerCmd;
import org.apache.cloudstack.api.command.AddNsxVrfGatewayCmd;
import org.apache.cloudstack.api.command.AssignNsxVrfGatewayCmd;
import org.apache.cloudstack.api.command.DeleteNsxVrfGatewayCmd;
import org.apache.cloudstack.api.command.ListNsxVrfGatewaysCmd;
import org.apache.cloudstack.api.command.ReleaseNsxVrfGatewayCmd;
import org.apache.cloudstack.api.response.NsxVrfGatewayResponse;
import org.apache.cloudstack.api.response.NsxControllerResponse;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.resource.NsxResource;
import org.apache.commons.lang3.StringUtils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import javax.naming.ConfigurationException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class NsxProviderServiceImpl implements NsxProviderService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    NsxProviderDao nsxProviderDao;
    @Inject
    DataCenterDao dataCenterDao;
    @Inject
    PhysicalNetworkDao physicalNetworkDao;
    @Inject
    NetworkDao networkDao;
    @Inject
    ResourceManager resourceManager;
    @Inject
    HostDetailsDao hostDetailsDao;
    @Inject
    NsxVrfGatewayDao nsxVrfGatewayDao;
    @Inject
    AccountDao accountDao;
    @Inject
    DomainDao domainDao;

    @Override
    public NsxProvider addProvider(AddNsxControllerCmd cmd) {
        final Long zoneId = cmd.getZoneId();
        final String name = cmd.getName();
        final String hostname = cmd.getHostname();
        final String port = cmd.getPort() == null || cmd.getPort().equals(StringUtils.EMPTY) ? "443" : cmd.getPort();
        final String username = cmd.getUsername();
        final String password = cmd.getPassword();
        final String tier0Gateway = cmd.getTier0Gateway();
        final String edgeCluster = cmd.getEdgeCluster();
        final String transportZone = cmd.getTransportZone();

        Map<String, String> params = new HashMap<>();
        params.put("guid", UUID.randomUUID().toString());
        params.put("zoneId", zoneId.toString());
        params.put("name", name);
        params.put("hostname", hostname);
        params.put("port", port);
        params.put("username", username);
        params.put("password", password);
        params.put("tier0Gateway", tier0Gateway);
        params.put("edgeCluster", edgeCluster);
        params.put("transportZone", transportZone);

        Map<String, Object> hostdetails = new HashMap<>(params);
        NsxProvider nsxProvider;

        NsxResource nsxResource = new NsxResource();
        try {
            nsxResource.configure(hostname, hostdetails);
            final Host host = resourceManager.addHost(zoneId, nsxResource, nsxResource.getType(), params);
            if (host != null) {
                 nsxProvider = Transaction.execute((TransactionCallback<NsxProviderVO>) status -> {
                    NsxProviderVO nsxProviderVO = new NsxProviderVO.Builder()
                            .setZoneId(zoneId)
                            .setHostId(host.getId())
                            .setProviderName(name)
                            .setHostname(hostname)
                            .setPort(port)
                            .setUsername(username)
                            .setPassword(password)
                            .setTier0Gateway(tier0Gateway)
                            .setEdgeCluster(edgeCluster)
                            .setTransportZone(transportZone)
                            .build();

                    nsxProviderDao.persist(nsxProviderVO);

                    DetailVO detail = new DetailVO(host.getId(), "nsxcontrollerid",
                            String.valueOf(nsxProviderVO.getId()));
                    hostDetailsDao.persist(detail);

                    return nsxProviderVO;
                });
            } else {
                throw new CloudRuntimeException("Failed to add NSX controller due to internal error.");
            }
        } catch (ConfigurationException e) {
            throw new CloudRuntimeException(e.getMessage());
        }
        return  nsxProvider;
    }

    @Override
    public NsxControllerResponse createNsxControllerResponse(NsxProvider nsxProvider) {
        DataCenterVO zone  = dataCenterDao.findById(nsxProvider.getZoneId());
        if (Objects.isNull(zone)) {
            throw new CloudRuntimeException(String.format("Failed to find zone with id %s", nsxProvider.getZoneId()));
        }
        NsxControllerResponse response = new NsxControllerResponse();
        response.setName(nsxProvider.getProviderName());
        response.setUuid(nsxProvider.getUuid());
        response.setHostname(nsxProvider.getHostname());
        response.setPort(nsxProvider.getPort());
        response.setZoneId(zone.getUuid());
        response.setZoneName(zone.getName());
        response.setTier0Gateway(nsxProvider.getTier0Gateway());
        response.setEdgeCluster(nsxProvider.getEdgeCluster());
        response.setTransportZone(nsxProvider.getTransportZone());
        response.setObjectName("nsxController");
        return response;
    }

    @Override
    public List<BaseResponse> listNsxProviders(Long zoneId) {
        List<BaseResponse> nsxControllersResponseList = new ArrayList<>();
        if (zoneId != null) {
            NsxProviderVO nsxProviderVO = nsxProviderDao.findByZoneId(zoneId);
            if (Objects.nonNull(nsxProviderVO)) {
                nsxControllersResponseList.add(createNsxControllerResponse(nsxProviderVO));
            }
        } else {
            List<NsxProviderVO> nsxProviderVOList = nsxProviderDao.listAll();
            for (NsxProviderVO nsxProviderVO : nsxProviderVOList) {
                nsxControllersResponseList.add(createNsxControllerResponse(nsxProviderVO));
            }
        }

        return nsxControllersResponseList;
    }

    @Override
    public boolean deleteNsxController(Long nsxControllerId) {
        NsxProviderVO nsxProvider = nsxProviderDao.findById(nsxControllerId);
        if (Objects.isNull(nsxProvider)) {
            throw new InvalidParameterValueException(String.format("Failed to find NSX controller with id: %s", nsxControllerId));
        }
        Long zoneId = nsxProvider.getZoneId();
        // Find the physical network we work for
        List<PhysicalNetworkVO> physicalNetworks = physicalNetworkDao.listByZone(zoneId);
        for (PhysicalNetworkVO physicalNetwork : physicalNetworks) {
            List<NetworkVO> networkList = networkDao.listByPhysicalNetwork(physicalNetwork.getId());
            if (!CollectionUtils.isNullOrEmpty(networkList)) {
                validateNetworkState(networkList);
            }
        }
        nsxProviderDao.remove(nsxControllerId);
        return true;
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        if (Boolean.TRUE.equals(NetworkOrchestrationService.NSX_ENABLED.value())) {
            cmdList.add(AddNsxControllerCmd.class);
            cmdList.add(ListNsxControllersCmd.class);
            cmdList.add(DeleteNsxControllerCmd.class);
            cmdList.add(AddNsxVrfGatewayCmd.class);
            cmdList.add(AssignNsxVrfGatewayCmd.class);
            cmdList.add(ReleaseNsxVrfGatewayCmd.class);
            cmdList.add(ListNsxVrfGatewaysCmd.class);
            cmdList.add(DeleteNsxVrfGatewayCmd.class);
        }
        return cmdList;
    }

    @Override
    public NsxVrfGatewayResponse addNsxVrfGateway(AddNsxVrfGatewayCmd cmd) {
        DataCenterVO zone = dataCenterDao.findById(cmd.getZoneId());
        if (zone == null) {
            throw new InvalidParameterValueException("Could not find zone with the given ID");
        }
        if (nsxProviderDao.findByZoneId(zone.getId()) == null) {
            throw new InvalidParameterValueException(String.format("Zone %s has no NSX controller registered", zone));
        }
        if (nsxVrfGatewayDao.findByZoneAndTier0Name(zone.getId(), cmd.getTier0Gateway()) != null) {
            throw new InvalidParameterValueException(String.format(
                    "Tier-0 gateway %s is already registered in zone %s", cmd.getTier0Gateway(), zone));
        }

        NsxVrfGatewayVO gateway = new NsxVrfGatewayVO(zone.getId(), cmd.getTier0Gateway(),
                cmd.getEdgeCluster(), cmd.getParentTier0Gateway());
        gateway = nsxVrfGatewayDao.persist(gateway);
        logger.debug("Registered NSX VRF gateway {} in zone {}", gateway, zone);
        return createNsxVrfGatewayResponse(gateway);
    }

    @Override
    public NsxVrfGatewayResponse assignNsxVrfGateway(AssignNsxVrfGatewayCmd cmd) {
        NsxVrfGatewayVO gateway = getVrfGatewayOrFail(cmd.getId());
        Long accountId = cmd.getAccountId();
        Long domainId = cmd.getDomainId();

        if ((accountId == null) == (domainId == null)) {
            throw new InvalidParameterValueException("Specify exactly one of accountid or domainid");
        }
        if (!gateway.isUnclaimed()) {
            throw new InvalidParameterValueException(String.format(
                    "NSX VRF gateway %s is already assigned; release it first", gateway.getNsxTier0Name()));
        }

        if (accountId != null) {
            AccountVO account = accountDao.findById(accountId);
            if (account == null) {
                throw new InvalidParameterValueException("Could not find account with the given ID");
            }
            NsxVrfGatewayVO existing = nsxVrfGatewayDao.findByAccount(gateway.getZoneId(), accountId);
            if (existing != null) {
                throw new InvalidParameterValueException(String.format(
                        "Account %s already has NSX VRF gateway %s in this zone", account, existing.getNsxTier0Name()));
            }
            gateway.setScope(NsxVrfGatewayVO.Scope.ACCOUNT.name());
            gateway.setAccountId(accountId);
            gateway.setDomainId(account.getDomainId());
        } else {
            DomainVO domain = domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Could not find domain with the given ID");
            }
            NsxVrfGatewayVO existing = nsxVrfGatewayDao.findByDomain(gateway.getZoneId(), domainId);
            if (existing != null) {
                throw new InvalidParameterValueException(String.format(
                        "Domain %s already has NSX VRF gateway %s in this zone", domain, existing.getNsxTier0Name()));
            }
            gateway.setScope(NsxVrfGatewayVO.Scope.DOMAIN.name());
            gateway.setDomainId(domainId);
        }

        if (!nsxVrfGatewayDao.update(gateway.getId(), gateway)) {
            throw new CloudRuntimeException(String.format(
                    "Failed to assign NSX VRF gateway %s", gateway.getNsxTier0Name()));
        }
        logger.debug("Assigned NSX VRF gateway {}", gateway);
        return createNsxVrfGatewayResponse(gateway);
    }

    @Override
    public NsxVrfGatewayResponse releaseNsxVrfGateway(Long id) {
        NsxVrfGatewayVO gateway = getVrfGatewayOrFail(id);
        if (gateway.isUnclaimed()) {
            throw new InvalidParameterValueException(String.format(
                    "NSX VRF gateway %s is not assigned to anyone", gateway.getNsxTier0Name()));
        }
        // Re-parenting a live tier-1 onto a different tier-0 is not something CloudStack
        // can do without disrupting the tenant, so refuse while networks are still using it.
        long networks = countNetworksUsingVrfGateway(gateway);
        if (networks > 0) {
            throw new InvalidParameterValueException(String.format(
                    "NSX VRF gateway %s still has %d network(s) attached; remove them before releasing it",
                    gateway.getNsxTier0Name(), networks));
        }
        // Must go through the setters: GenericDaoBase builds its UPDATE from setter calls
        // intercepted on the enhanced entity, so clearing the fields directly would write
        // nothing while still reporting success.
        gateway.setScope(null);
        gateway.setAccountId(null);
        gateway.setDomainId(null);
        gateway.setPublicVlanDbId(null);
        if (!nsxVrfGatewayDao.update(gateway.getId(), gateway)) {
            throw new CloudRuntimeException(String.format(
                    "Failed to release NSX VRF gateway %s", gateway.getNsxTier0Name()));
        }
        logger.debug("Released NSX VRF gateway {}", gateway);
        return createNsxVrfGatewayResponse(gateway);
    }

    @Override
    public List<NsxVrfGatewayResponse> listNsxVrfGateways(ListNsxVrfGatewaysCmd cmd) {
        List<NsxVrfGatewayVO> gateways;
        if (cmd.getAccountId() != null) {
            gateways = new ArrayList<>();
            for (DataCenterVO zone : listZonesToSearch(cmd.getZoneId())) {
                NsxVrfGatewayVO gateway = nsxVrfGatewayDao.findByAccount(zone.getId(), cmd.getAccountId());
                if (gateway != null) {
                    gateways.add(gateway);
                }
            }
        } else if (cmd.getDomainId() != null) {
            gateways = new ArrayList<>();
            for (DataCenterVO zone : listZonesToSearch(cmd.getZoneId())) {
                NsxVrfGatewayVO gateway = nsxVrfGatewayDao.findByDomain(zone.getId(), cmd.getDomainId());
                if (gateway != null) {
                    gateways.add(gateway);
                }
            }
        } else if (cmd.getZoneId() != null) {
            gateways = nsxVrfGatewayDao.listByZone(cmd.getZoneId());
        } else {
            gateways = nsxVrfGatewayDao.listAll();
        }

        List<NsxVrfGatewayResponse> responses = new ArrayList<>();
        for (NsxVrfGatewayVO gateway : gateways) {
            if (cmd.getAllocatedOnly() != null && cmd.getAllocatedOnly() == gateway.isUnclaimed()) {
                continue;
            }
            responses.add(createNsxVrfGatewayResponse(gateway));
        }
        return responses;
    }

    @Override
    public boolean deleteNsxVrfGateway(Long id) {
        NsxVrfGatewayVO gateway = getVrfGatewayOrFail(id);
        if (!gateway.isUnclaimed()) {
            throw new InvalidParameterValueException(String.format(
                    "NSX VRF gateway %s is still assigned; release it first", gateway.getNsxTier0Name()));
        }
        logger.debug("Deregistering NSX VRF gateway {}; it is left in place in NSX", gateway);
        return nsxVrfGatewayDao.remove(id);
    }

    private List<DataCenterVO> listZonesToSearch(Long zoneId) {
        if (zoneId == null) {
            return dataCenterDao.listAll();
        }
        DataCenterVO zone = dataCenterDao.findById(zoneId);
        return zone == null ? new ArrayList<>() : List.of(zone);
    }

    private NsxVrfGatewayVO getVrfGatewayOrFail(Long id) {
        NsxVrfGatewayVO gateway = id == null ? null : nsxVrfGatewayDao.findById(id);
        if (gateway == null) {
            throw new InvalidParameterValueException("Could not find NSX VRF gateway with the given ID");
        }
        return gateway;
    }

    @VisibleForTesting
    long countNetworksUsingVrfGateway(NsxVrfGatewayVO gateway) {
        List<NetworkVO> networks = networkDao.listByZone(gateway.getZoneId());
        if (CollectionUtils.isNullOrEmpty(networks)) {
            return 0;
        }
        return networks.stream()
                .filter(network -> network.getBroadcastDomainType() == Networks.BroadcastDomainType.NSX)
                .filter(network -> network.getRemoved() == null)
                .filter(network -> matchesTenant(gateway, network))
                .count();
    }

    private boolean matchesTenant(NsxVrfGatewayVO gateway, NetworkVO network) {
        if (gateway.getAccountId() != null) {
            return gateway.getAccountId().equals(network.getAccountId());
        }
        return gateway.getDomainId() != null && gateway.getDomainId().equals(network.getDomainId());
    }

    @VisibleForTesting
    NsxVrfGatewayResponse createNsxVrfGatewayResponse(NsxVrfGatewayVO gateway) {
        NsxVrfGatewayResponse response = new NsxVrfGatewayResponse();
        response.setId(gateway.getUuid());
        response.setTier0Gateway(gateway.getNsxTier0Name());
        response.setEdgeCluster(gateway.getEdgeCluster());
        response.setParentTier0Gateway(gateway.getParentTier0());
        response.setScope(gateway.getScope());
        response.setAllocated(!gateway.isUnclaimed());

        DataCenterVO zone = dataCenterDao.findById(gateway.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        if (gateway.getAccountId() != null) {
            AccountVO account = accountDao.findById(gateway.getAccountId());
            if (account != null) {
                response.setAccountId(account.getUuid());
                response.setAccountName(account.getAccountName());
            }
        }
        if (gateway.getDomainId() != null) {
            DomainVO domain = domainDao.findById(gateway.getDomainId());
            if (domain != null) {
                response.setDomainId(domain.getUuid());
                response.setDomainName(domain.getName());
            }
        }
        return response;
    }

    @VisibleForTesting
    void validateNetworkState(List<NetworkVO> networkList) {
        for (NetworkVO network : networkList) {
            if (network.getBroadcastDomainType() == Networks.BroadcastDomainType.NSX &&
                ((network.getState() != Network.State.Shutdown) && (network.getState() != Network.State.Destroy))) {
                    throw new CloudRuntimeException("This NSX Controller cannot be deleted as there are one or more logical networks provisioned by CloudStack on it.");
            }
        }
    }
}
