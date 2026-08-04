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

import com.cloud.network.IpAddress;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.NsxVrfGatewayDao;
import com.cloud.network.dao.NsxVrfGatewayPlacementDao;
import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.network.element.NsxVrfGatewayPlacementVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.dao.Site2SiteVpnGatewayVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.nsx.NsxVpnGatewayResult;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import org.apache.cloudstack.NsxAnswer;
import org.apache.cloudstack.NsxVrfGatewayValidationAnswer;
import org.apache.cloudstack.agent.api.CreateNsxStaticNatCommand;
import org.apache.cloudstack.agent.api.CreateNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.CreateNsxVpnGatewayCommand;
import org.apache.cloudstack.agent.api.CreateOrUpdateNsxTier1NatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxNatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxSegmentCommand;
import org.apache.cloudstack.agent.api.DeleteNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.ValidateNsxVrfGatewayCommand;
import org.apache.cloudstack.utils.NsxControllerUtils;
import org.apache.cloudstack.resourcedetail.UserIpAddressDetailVO;
import org.apache.cloudstack.resourcedetail.dao.UserIpAddressDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class NsxServiceImplTest {
    @Mock
    private NsxControllerUtils nsxControllerUtils;
    @Mock
    private VpcDao vpcDao;
    @Mock
    private NsxVrfGatewayDao nsxVrfGatewayDao;
    @Mock
    private NsxVrfGatewayPlacementDao nsxVrfGatewayPlacementDao;
    @Mock
    private NsxProviderDao nsxProviderDao;
    @Mock
    private NsxVrfGatewayLockManager nsxVrfGatewayLockManager;
    @Mock
    private DomainDao domainDao;
    @Mock
    private Site2SiteVpnConnectionDao site2SiteVpnConnectionDao;
    @Mock
    private Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;
    @Mock
    private UserIpAddressDetailsDao userIpAddressDetailsDao;
    NsxServiceImpl nsxService;

    AutoCloseable closeable;

    private static final long domainId = 1L;
    private static final long accountId = 2L;
    private static final long zoneId = 1L;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        nsxService = Mockito.spy(new NsxServiceImpl());
        nsxService.nsxControllerUtils = nsxControllerUtils;
        nsxService.vpcDao = vpcDao;
        nsxService.nsxVrfGatewayDao = nsxVrfGatewayDao;
        nsxService.nsxVrfGatewayPlacementDao = nsxVrfGatewayPlacementDao;
        nsxService.nsxProviderDao = nsxProviderDao;
        nsxService.nsxVrfGatewayLockManager = nsxVrfGatewayLockManager;
        nsxService.domainDao = domainDao;
        nsxService.site2SiteVpnConnectionDao = site2SiteVpnConnectionDao;
        nsxService.site2SiteVpnGatewayDao = site2SiteVpnGatewayDao;
        nsxService.userIpAddressDetailsDao = userIpAddressDetailsDao;
        Mockito.lenient().when(nsxVrfGatewayLockManager.withPlacementLock(anyBoolean(), anyLong(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get());
        Mockito.lenient().when(nsxVrfGatewayLockManager.withZoneLock(anyLong(), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        Mockito.lenient().when(nsxVrfGatewayPlacementDao.persist(any(NsxVrfGatewayPlacementVO.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Mockito.lenient().when(nsxVrfGatewayPlacementDao.update(anyLong(), any(NsxVrfGatewayPlacementVO.class))).thenReturn(true);
        NsxProviderVO provider = mock(NsxProviderVO.class);
        Mockito.lenient().when(provider.getTier0Gateway()).thenReturn("v5-T0");
        Mockito.lenient().when(nsxProviderDao.findByZoneId(anyLong())).thenReturn(provider);
        NsxVrfGatewayValidationAnswer validationAnswer = mock(NsxVrfGatewayValidationAnswer.class);
        Mockito.lenient().when(validationAnswer.getResult()).thenReturn(true);
        Mockito.lenient().when(nsxControllerUtils.sendNsxCommandForResult(any(ValidateNsxVrfGatewayCommand.class), anyLong()))
                .thenReturn(validationAnswer);
    }

    @After
    public void teardown() throws Exception {
        closeable.close();
    }

    private NsxVrfGatewayVO vrfGateway(String tier0, String edgeCluster) {
        return new NsxVrfGatewayVO(zoneId, tier0, edgeCluster, "v5-T0");
    }

    private NsxVrfGatewayPlacementVO placement(Long gatewayId, Long vpcId, Long networkId,
            String tier0, NsxVrfGatewayPlacementVO.State state) {
        NsxVrfGatewayPlacementVO placement = new NsxVrfGatewayPlacementVO(gatewayId, zoneId, domainId,
                accountId, vpcId, networkId, tier0);
        placement.setState(state);
        return placement;
    }

    @Test
    public void testResolveVrfGatewayReturnsNullWhenScopeIsNone() {
        Mockito.doReturn("NONE").when(nsxService).getVrfScope(zoneId);
        assertNull(nsxService.resolveVrfGateway(zoneId, accountId, domainId));
        verify(nsxVrfGatewayDao, never()).findByAccount(anyLong(), anyLong());
    }

    @Test
    public void testResolveVrfGatewayReturnsNullWhenZoneIsNull() {
        assertNull(nsxService.resolveVrfGateway(null, accountId, domainId));
    }

    @Test
    public void testResolveVrfGatewayPrefersTheAccountAssignment() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        NsxVrfGatewayVO expected = vrfGateway("CS-VRF-001", "v5-EdgeCluster-HOSTED");
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId)).thenReturn(expected);
        assertEquals(expected, nsxService.resolveVrfGateway(zoneId, accountId, domainId));
        verify(domainDao, never()).findById(anyLong());
    }

    @Test
    public void testResolveVrfGatewayFallsBackToTheDomainWhenTheAccountHasNone() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        NsxVrfGatewayVO expected = vrfGateway("CS-VRF-002", "v5-EdgeCluster-HOSTED");
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId)).thenReturn(null);
        DomainVO domain = mock(DomainVO.class);
        when(domain.getId()).thenReturn(domainId);
        when(domainDao.findById(domainId)).thenReturn(domain);
        when(nsxVrfGatewayDao.findByDomain(zoneId, domainId)).thenReturn(expected);
        assertEquals(expected, nsxService.resolveVrfGateway(zoneId, accountId, domainId));
    }

    @Test
    public void testResolveVrfGatewayWalksUpTheDomainChain() {
        Mockito.doReturn("DOMAIN").when(nsxService).getVrfScope(zoneId);
        long parentDomainId = 7L;
        NsxVrfGatewayVO expected = vrfGateway("CS-VRF-003", "v5-EdgeCluster-HOSTED");
        DomainVO child = mock(DomainVO.class);
        when(child.getId()).thenReturn(domainId);
        when(child.getParent()).thenReturn(parentDomainId);
        DomainVO parent = mock(DomainVO.class);
        when(parent.getId()).thenReturn(parentDomainId);
        when(domainDao.findById(domainId)).thenReturn(child);
        when(domainDao.findById(parentDomainId)).thenReturn(parent);
        when(nsxVrfGatewayDao.findByDomain(zoneId, domainId)).thenReturn(null);
        when(nsxVrfGatewayDao.findByDomain(zoneId, parentDomainId)).thenReturn(expected);
        assertEquals(expected, nsxService.resolveVrfGateway(zoneId, accountId, domainId));
    }

    @Test
    public void testResolveVrfGatewayFallsBackToSharedTier0WhenAllowed() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        Mockito.doReturn(true).when(nsxService).isVrfFallbackToSharedTier0Allowed(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId)).thenReturn(null);
        when(domainDao.findById(domainId)).thenReturn(null);
        assertNull(nsxService.resolveVrfGateway(zoneId, accountId, domainId));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testResolveVrfGatewayFailsWhenSegregatedAndUnassigned() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        Mockito.doReturn(false).when(nsxService).isVrfFallbackToSharedTier0Allowed(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId)).thenReturn(null);
        when(domainDao.findById(domainId)).thenReturn(null);
        nsxService.resolveVrfGateway(zoneId, accountId, domainId);
    }

    @Test
    public void testCreateVpcNetwork() {
        NsxAnswer createNsxTier1GatewayAnswer = mock(NsxAnswer.class);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxTier1GatewayCommand.class), anyLong())).thenReturn(createNsxTier1GatewayAnswer);
        when(createNsxTier1GatewayAnswer.getResult()).thenReturn(true);

        assertTrue(nsxService.createVpcNetwork(1L, 3L, 2L, 5L, "VPC01", false, null));
    }

    @Test
    public void testCreateNetworkAppliesVrfPlacementAndSourceNatSetting() {
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxTier1GatewayCommand.class), eq(zoneId)))
                .thenReturn(answer);
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId))
                .thenReturn(vrfGateway("CS-VRF-001", "v5-EdgeCluster-HOSTED"));
        ArgumentCaptor<CreateNsxTier1GatewayCommand> commandCaptor =
                ArgumentCaptor.forClass(CreateNsxTier1GatewayCommand.class);

        assertTrue(nsxService.createNetwork(zoneId, accountId, domainId, 5L, "Network01", true));

        verify(nsxControllerUtils).sendNsxCommand(commandCaptor.capture(), eq(zoneId));
        CreateNsxTier1GatewayCommand command = commandCaptor.getValue();
        assertEquals("CS-VRF-001", command.getTier0Gateway());
        assertEquals("v5-EdgeCluster-HOSTED", command.getEdgeCluster());
        assertTrue(command.isSourceNatEnabled());
    }

    @Test
    public void testCreateNetworkFailsCleanlyWhenVrfValidationReturnsNoAnswer() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId))
                .thenReturn(vrfGateway("CS-VRF-001", "v5-EdgeCluster-HOSTED"));
        when(nsxControllerUtils.sendNsxCommandForResult(any(ValidateNsxVrfGatewayCommand.class), eq(zoneId)))
                .thenReturn(null);
        ArgumentCaptor<NsxVrfGatewayPlacementVO> placementCaptor =
                ArgumentCaptor.forClass(NsxVrfGatewayPlacementVO.class);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> nsxService.createNetwork(zoneId, accountId, domainId, 5L, "Network01", true));

        assertTrue(exception.getMessage().contains("no answer was returned"));
        verify(nsxVrfGatewayPlacementDao).persist(placementCaptor.capture());
        assertEquals(NsxVrfGatewayPlacementVO.State.FAILED.name(), placementCaptor.getValue().getState());
        verify(nsxControllerUtils, never()).sendNsxCommand(any(CreateNsxTier1GatewayCommand.class), anyLong());
    }

    @Test
    public void testReserveTier1PlacementPersistsResolvedVrfBeforeReturningItsVlan() {
        long vpcId = 41L;
        long publicVlanId = 73L;
        NsxVrfGatewayVO gateway = vrfGateway("CS-VRF-001", "v5-EdgeCluster-HOSTED");
        gateway.setPublicVlanDbId(publicVlanId);
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId)).thenReturn(gateway);
        ArgumentCaptor<NsxVrfGatewayPlacementVO> placementCaptor =
                ArgumentCaptor.forClass(NsxVrfGatewayPlacementVO.class);

        assertEquals(Long.valueOf(publicVlanId), nsxService.reserveTier1PlacementAndGetPublicVlanId(
                zoneId, accountId, domainId, vpcId, null));

        verify(nsxVrfGatewayPlacementDao).persist(placementCaptor.capture());
        NsxVrfGatewayPlacementVO persisted = placementCaptor.getValue();
        assertEquals(Long.valueOf(vpcId), persisted.getVpcId());
        assertNull(persisted.getNetworkId());
        assertEquals("CS-VRF-001", persisted.getTier0Name());
        assertEquals(NsxVrfGatewayPlacementVO.State.PENDING_CREATE.name(), persisted.getState());
    }

    @Test
    public void testReserveTier1PlacementReusesExistingPlacementWithoutResolvingAgain() {
        long networkId = 42L;
        NsxVrfGatewayPlacementVO existing = placement(null, null, networkId, "v5-T0",
                NsxVrfGatewayPlacementVO.State.ACTIVE);
        when(nsxVrfGatewayPlacementDao.findByNetworkId(networkId)).thenReturn(existing);

        assertNull(nsxService.reserveTier1PlacementAndGetPublicVlanId(
                zoneId, accountId, domainId, null, networkId));

        verify(nsxVrfGatewayPlacementDao, never()).persist(any(NsxVrfGatewayPlacementVO.class));
        verify(nsxService, never()).getVrfScope(anyLong());
    }

    @Test
    public void testReserveTier1PlacementAllowsRetryAfterFailedCreation() {
        long vpcId = 43L;
        long gatewayId = 17L;
        long publicVlanId = 91L;
        NsxVrfGatewayPlacementVO existing = placement(gatewayId, vpcId, null, "CS-VRF-017",
                NsxVrfGatewayPlacementVO.State.FAILED);
        NsxVrfGatewayVO gateway = vrfGateway("CS-VRF-017", "v5-EdgeCluster-HOSTED");
        gateway.setPublicVlanDbId(publicVlanId);
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(existing);
        when(nsxVrfGatewayDao.findById(gatewayId)).thenReturn(gateway);

        assertEquals(Long.valueOf(publicVlanId), nsxService.reserveTier1PlacementAndGetPublicVlanId(
                zoneId, accountId, domainId, vpcId, null));

        verify(nsxVrfGatewayPlacementDao, never()).persist(any(NsxVrfGatewayPlacementVO.class));
    }

    @Test(expected = CloudRuntimeException.class)
    public void testReadOnlyPublicVlanLookupRejectsMissingLegacyPlacement() {
        long vpcId = 46L;
        try {
            nsxService.getPublicVlanId(zoneId, accountId, domainId, vpcId, null);
        } finally {
            verify(nsxVrfGatewayPlacementDao, never()).persist(any(NsxVrfGatewayPlacementVO.class));
            verify(nsxService, never()).getVrfScope(anyLong());
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testReadOnlyPublicVlanLookupRejectsFailedPlacement() {
        long vpcId = 44L;
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(placement(null, vpcId, null,
                "v5-T0", NsxVrfGatewayPlacementVO.State.FAILED));

        nsxService.getPublicVlanId(zoneId, accountId, domainId, vpcId, null);
    }

    @Test
    public void testReadOnlyPublicVlanLookupUsesRecordedVrfGateway() {
        long vpcId = 45L;
        long gatewayId = 17L;
        long publicVlanId = 91L;
        NsxVrfGatewayPlacementVO existing = placement(gatewayId, vpcId, null, "CS-VRF-017",
                NsxVrfGatewayPlacementVO.State.ACTIVE);
        NsxVrfGatewayVO gateway = vrfGateway("CS-VRF-017", "v5-EdgeCluster-HOSTED");
        gateway.setPublicVlanDbId(publicVlanId);
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(existing);
        when(nsxVrfGatewayDao.findById(gatewayId)).thenReturn(gateway);

        assertEquals(Long.valueOf(publicVlanId),
                nsxService.getPublicVlanId(zoneId, accountId, domainId, vpcId, null));
        verify(nsxService, never()).getVrfScope(anyLong());
    }

    @Test
    public void testCreateVpnGatewayPreservesStructuredFailureResult() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getDomainId()).thenReturn(domainId);
        when(vpc.getAccountId()).thenReturn(accountId);
        when(vpc.getZoneId()).thenReturn(zoneId);
        when(vpc.getId()).thenReturn(3L);
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.isEndpointMayBeInUse()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommandForResult(any(CreateNsxVpnGatewayCommand.class), eq(zoneId)))
                .thenReturn(answer);

        NsxVpnGatewayResult result = nsxService.createVpnGateway(vpc, "203.0.113.20");

        assertFalse(result.isSuccessful());
        assertTrue(result.isEndpointMayBeInUse());
    }

    @Test
    public void testDeleteVpcNetwork() {
        NsxAnswer deleteNsxTier1GatewayAnswer = mock(NsxAnswer.class);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxTier1GatewayCommand.class), anyLong())).thenReturn(deleteNsxTier1GatewayAnswer);
        when(deleteNsxTier1GatewayAnswer.getResult()).thenReturn(true);

        assertTrue(nsxService.deleteVpcNetwork(1L, 2L, 3L, 10L, "VPC01"));
    }

    @Test
    public void testDeleteVpcNetworkRemovesPlacementAfterBackendDeletion() {
        long vpcId = 10L;
        NsxVrfGatewayPlacementVO existing = placement(null, vpcId, null, "v5-T0",
                NsxVrfGatewayPlacementVO.State.ACTIVE);
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(existing);
        when(nsxVrfGatewayPlacementDao.expunge(existing.getId())).thenReturn(true);
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxTier1GatewayCommand.class), eq(zoneId)))
                .thenAnswer(invocation -> {
                    assertEquals(NsxVrfGatewayPlacementVO.State.PENDING_DELETE.name(), existing.getState());
                    return answer;
                });

        assertTrue(nsxService.deleteVpcNetwork(zoneId, accountId, domainId, vpcId, "VPC01"));

        verify(nsxVrfGatewayPlacementDao).update(existing.getId(), existing);
        verify(nsxVrfGatewayPlacementDao).expunge(existing.getId());
    }

    @Test
    public void testDeleteVpcNetworkMarksPlacementFailedWhenBackendDeletionFails() {
        long vpcId = 10L;
        NsxVrfGatewayPlacementVO existing = placement(null, vpcId, null, "v5-T0",
                NsxVrfGatewayPlacementVO.State.ACTIVE);
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(existing);
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("backend deletion failed");
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxTier1GatewayCommand.class), eq(zoneId)))
                .thenReturn(answer);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> nsxService.deleteVpcNetwork(zoneId, accountId, domainId, vpcId, "VPC01"));

        assertTrue(exception.getMessage().contains("backend deletion failed"));
        assertEquals(NsxVrfGatewayPlacementVO.State.FAILED.name(), existing.getState());
        verify(nsxVrfGatewayPlacementDao, times(2)).update(existing.getId(), existing);
        verify(nsxVrfGatewayPlacementDao, never()).expunge(anyLong());
    }

    @Test
    public void testDeleteNetworkOnVpc() {
        NetworkVO network = new NetworkVO();
        network.setVpcId(1L);
        when(vpcDao.findById(1L)).thenReturn(mock(VpcVO.class));
        NsxAnswer deleteNsxSegmentAnswer = mock(NsxAnswer.class);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxSegmentCommand.class), anyLong())).thenReturn(deleteNsxSegmentAnswer);
        when(deleteNsxSegmentAnswer.getResult()).thenReturn(true);

        assertTrue(nsxService.deleteNetwork(zoneId, accountId, domainId, network));
    }

    @Test
    public void testDeleteNetwork() {
        NetworkVO network = new NetworkVO();
        network.setVpcId(null);
        NsxAnswer deleteNsxSegmentAnswer = mock(NsxAnswer.class);
        when(deleteNsxSegmentAnswer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxSegmentCommand.class), anyLong())).thenReturn(deleteNsxSegmentAnswer);
        NsxAnswer deleteNsxTier1GatewayAnswer = mock(NsxAnswer.class);
        when(deleteNsxTier1GatewayAnswer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxTier1GatewayCommand.class), anyLong())).thenReturn(deleteNsxTier1GatewayAnswer);
        assertTrue(nsxService.deleteNetwork(zoneId, accountId, domainId, network));
    }

    @Test
    public void testUpdateVpcSourceNatIp() {
        VpcVO vpc = mock(VpcVO.class);
        IpAddress ipAddress = mock(IpAddress.class);
        Ip ip = Mockito.mock(Ip.class);
        when(ip.addr()).thenReturn("10.1.10.10");
        when(ipAddress.getAddress()).thenReturn(ip);
        long vpcId = 1L;
        when(vpc.getAccountId()).thenReturn(accountId);
        when(vpc.getDomainId()).thenReturn(domainId);
        when(vpc.getZoneId()).thenReturn(zoneId);
        when(vpc.getId()).thenReturn(vpcId);
        when(nsxVrfGatewayPlacementDao.findByVpcId(vpcId)).thenReturn(placement(null, vpcId, null,
                "v5-T0", NsxVrfGatewayPlacementVO.State.ACTIVE));
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateOrUpdateNsxTier1NatRuleCommand.class), eq(zoneId))).thenReturn(answer);
        nsxService.updateVpcSourceNatIp(vpc, ipAddress);
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(CreateOrUpdateNsxTier1NatRuleCommand.class), eq(zoneId));
    }

    @Test
    public void testCreateStaticNatRule() {
        long networkId = 1L;
        String networkName = "Network-Test";
        long vmId = 1L;
        String publicIp = "10.10.1.10";
        String vmIp = "192.168.1.20";
        NsxAnswer answer = Mockito.mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxStaticNatCommand.class), eq(zoneId))).thenReturn(answer);
        nsxService.createStaticNatRule(zoneId, domainId, accountId,
                networkId, networkName, true, vmId, publicIp, vmIp);
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(CreateNsxStaticNatCommand.class), eq(zoneId));
    }

    @Test
    public void testDeleteStaticNatRule() {
        long networkId = 1L;
        String networkName = "Network-Test";
        NsxAnswer answer = Mockito.mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxNatRuleCommand.class), eq(zoneId))).thenReturn(answer);
        nsxService.deleteStaticNatRule(zoneId, domainId, accountId, networkId, networkName, true);
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(DeleteNsxNatRuleCommand.class), eq(zoneId));
    }

    @Test
    public void testPollVpnConnectionStatusTransitionsUp() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        AtomicReference<Site2SiteVpnConnection.State> transitionedState = new AtomicReference<>();

        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
                return "UP";
            }

            @Override
            protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                        Site2SiteVpnConnection.State observedState,
                                                        Site2SiteVpnConnection.State newState) {
                transitionedState.set(newState);
            }
        };

        service.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Connected, transitionedState.get());
    }

    @Test
    public void testPollVpnConnectionStatusTransitionsDown() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        when(connection.getState()).thenReturn(Site2SiteVpnConnection.State.Connected);
        AtomicReference<Site2SiteVpnConnection.State> transitionedState = new AtomicReference<>();

        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
                return VPN_SESSION_STATUS_DOWN;
            }

            @Override
            protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                        Site2SiteVpnConnection.State observedState,
                                                        Site2SiteVpnConnection.State newState) {
                transitionedState.set(newState);
            }
        };

        service.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Disconnected, transitionedState.get());
    }

    @Test
    public void testPollVpnConnectionStatusKeepsPendingConnectionWhenSessionIsNotFound() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        when(connection.getState()).thenReturn(Site2SiteVpnConnection.State.Pending);
        AtomicBoolean transitioned = new AtomicBoolean();

        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
                return VPN_SESSION_STATUS_NOT_FOUND;
            }

            @Override
            protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                        Site2SiteVpnConnection.State observedState,
                                                        Site2SiteVpnConnection.State newState) {
                transitioned.set(true);
            }
        };

        service.pollVpnConnectionStatus(connection, vpc);

        assertFalse(transitioned.get());
    }

    @Test
    public void testPollVpnConnectionStatusMarksMissingConnectedSessionAsError() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        when(connection.getState()).thenReturn(Site2SiteVpnConnection.State.Connected);
        AtomicReference<Site2SiteVpnConnection.State> transitionedState = new AtomicReference<>();

        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
                return VPN_SESSION_STATUS_NOT_FOUND;
            }

            @Override
            protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                        Site2SiteVpnConnection.State observedState,
                                                        Site2SiteVpnConnection.State newState) {
                transitionedState.set(newState);
            }
        };

        service.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Error, transitionedState.get());
    }

    @Test
    public void testPollVpnConnectionStatusDoesNotTransitionOnQueryFailure() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        when(connection.getId()).thenReturn(11L);
        AtomicBoolean transitioned = new AtomicBoolean();

        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            public String getVpnConnectionStatus(Vpc vpc, String connectionUuid) {
                throw new CloudRuntimeException("NSX unavailable");
            }

            @Override
            protected void transitionVpnConnectionState(Site2SiteVpnConnectionVO connection, VpcVO vpc,
                                                        Site2SiteVpnConnection.State observedState,
                                                        Site2SiteVpnConnection.State newState) {
                transitioned.set(true);
            }
        };

        service.pollVpnConnectionStatus(connection, vpc);

        // A transient management-plane error must not turn a valid connection into Error.
        assertTrue(!transitioned.get());
    }

    @Test
    public void testTransitionVpnConnectionStateIgnoresStaleStatusObservation() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        Site2SiteVpnConnectionVO lock = mock(Site2SiteVpnConnectionVO.class);
        Site2SiteVpnConnectionVO current = mock(Site2SiteVpnConnectionVO.class);
        VpcVO vpc = mock(VpcVO.class);
        when(connection.getId()).thenReturn(11L);
        when(lock.getId()).thenReturn(11L);
        when(current.getState()).thenReturn(Site2SiteVpnConnection.State.Disconnected);
        when(site2SiteVpnConnectionDao.acquireInLockTable(11L)).thenReturn(lock);
        when(site2SiteVpnConnectionDao.findById(11L)).thenReturn(current);

        nsxService.transitionVpnConnectionState(connection, vpc, Site2SiteVpnConnection.State.Connecting,
                Site2SiteVpnConnection.State.Connected);

        verify(site2SiteVpnConnectionDao, never()).persist(current);
        verify(site2SiteVpnConnectionDao).releaseFromLockTable(11L);
    }

    @Test
    public void testVpnStatusPollerSkipsUnmarkedGatewayRegardlessOfCurrentOffering() {
        Site2SiteVpnConnectionVO connection = mockPollableVpnConnection();
        Site2SiteVpnGatewayVO gateway = mockVpnGatewayForPoller(connection);
        VpcVO vpc = mock(VpcVO.class);
        when(vpcDao.findById(gateway.getVpcId())).thenReturn(vpc);
        NsxServiceImpl service = nsxService;

        service.new VpnStatusPollTask().runInContext();

        verify(service, never()).pollVpnConnectionStatus(connection, vpc);
    }

    @Test
    public void testVpnStatusPollerUsesPersistedOwnershipAfterOfferingChanges() {
        Site2SiteVpnConnectionVO connection = mockPollableVpnConnection();
        Site2SiteVpnGatewayVO gateway = mockVpnGatewayForPoller(connection);
        VpcVO vpc = mock(VpcVO.class);
        when(vpcDao.findById(gateway.getVpcId())).thenReturn(vpc);
        when(userIpAddressDetailsDao.findDetail(gateway.getAddrId(), NsxElement.NSX_VPN_GATEWAY_IP_DETAIL))
                .thenReturn(mock(UserIpAddressDetailVO.class));
        NsxServiceImpl service = nsxService;
        doNothing().when(service).pollVpnConnectionStatus(connection, vpc);

        service.new VpnStatusPollTask().runInContext();

        verify(service).pollVpnConnectionStatus(connection, vpc);
    }

    @Test
    public void testVpnStatusPollerQueriesOnlyPollableStates() {
        nsxService.new VpnStatusPollTask().runInContext();

        verify(site2SiteVpnConnectionDao).listByStates(
                Site2SiteVpnConnection.State.Pending,
                Site2SiteVpnConnection.State.Connecting,
                Site2SiteVpnConnection.State.Connected,
                Site2SiteVpnConnection.State.Disconnected);
        verify(site2SiteVpnConnectionDao, never()).listAll();
    }

    @Test
    public void testVpnStatusPollerCanRestartInSameJvm() throws Exception {
        ScheduledExecutorService firstExecutor = mock(ScheduledExecutorService.class);
        ScheduledExecutorService secondExecutor = mock(ScheduledExecutorService.class);
        AtomicInteger executorIndex = new AtomicInteger();
        NsxServiceImpl service = new NsxServiceImpl() {
            @Override
            protected ScheduledExecutorService createVpnStatusPollExecutor() {
                return executorIndex.getAndIncrement() == 0 ? firstExecutor : secondExecutor;
            }
        };
        service.configure("NsxService", Map.of());
        try {
            assertTrue(service.start());
            verify(firstExecutor).scheduleWithFixedDelay(any(Runnable.class), eq(60L), eq(60L), eq(java.util.concurrent.TimeUnit.SECONDS));

            assertTrue(service.stop());
            verify(firstExecutor).shutdownNow();

            assertTrue(service.start());
            verify(secondExecutor).scheduleWithFixedDelay(any(Runnable.class), eq(60L), eq(60L), eq(java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, executorIndex.get());
        } finally {
            service.stop();
        }
        verify(secondExecutor).shutdownNow();
        verify(firstExecutor, times(1)).shutdownNow();
    }

    private Site2SiteVpnConnectionVO mockPollableVpnConnection() {
        Site2SiteVpnConnectionVO connection = mock(Site2SiteVpnConnectionVO.class);
        when(connection.getId()).thenReturn(11L);
        when(connection.getVpnGatewayId()).thenReturn(7L);
        when(site2SiteVpnConnectionDao.listByStates(
                Site2SiteVpnConnection.State.Pending,
                Site2SiteVpnConnection.State.Connecting,
                Site2SiteVpnConnection.State.Connected,
                Site2SiteVpnConnection.State.Disconnected)).thenReturn(List.of(connection));
        return connection;
    }

    private Site2SiteVpnGatewayVO mockVpnGatewayForPoller(Site2SiteVpnConnectionVO connection) {
        Site2SiteVpnGatewayVO gateway = mock(Site2SiteVpnGatewayVO.class);
        when(gateway.getVpcId()).thenReturn(9L);
        when(gateway.getAddrId()).thenReturn(30L);
        when(site2SiteVpnGatewayDao.findById(connection.getVpnGatewayId())).thenReturn(gateway);
        return gateway;
    }
}
