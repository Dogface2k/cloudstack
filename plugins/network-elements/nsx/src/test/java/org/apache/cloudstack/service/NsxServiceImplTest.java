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

import java.util.List;

import com.cloud.alert.AlertManager;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.dao.NsxVrfGatewayDao;
import com.cloud.network.element.NsxVrfGatewayVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.Site2SiteVpnConnectionDao;
import com.cloud.network.dao.Site2SiteVpnConnectionVO;
import com.cloud.network.dao.Site2SiteVpnGatewayDao;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import org.apache.cloudstack.NsxAnswer;
import org.apache.cloudstack.agent.api.CreateNsxStaticNatCommand;
import org.apache.cloudstack.agent.api.CreateNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.CreateNsxVpnConnectionCommand;
import org.apache.cloudstack.agent.api.CreateNsxVpnGatewayCommand;
import org.apache.cloudstack.agent.api.CreateOrUpdateNsxTier1NatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxNatRuleCommand;
import org.apache.cloudstack.agent.api.DeleteNsxSegmentCommand;
import org.apache.cloudstack.agent.api.DeleteNsxTier1GatewayCommand;
import org.apache.cloudstack.agent.api.DeleteNsxVpnConnectionCommand;
import org.apache.cloudstack.agent.api.DeleteNsxVpnGatewayCommand;
import org.apache.cloudstack.agent.api.GetNsxVpnSessionStatusCommand;
import org.apache.cloudstack.utils.NsxControllerUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
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
    private VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Mock
    private Site2SiteVpnConnectionDao site2SiteVpnConnectionDao;
    @Mock
    private Site2SiteVpnGatewayDao site2SiteVpnGatewayDao;
    @Mock
    private AlertManager alertManager;
    @Mock
    private NsxVrfGatewayDao nsxVrfGatewayDao;
    @Mock
    private DomainDao domainDao;
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
        nsxService.vpcOfferingServiceMapDao = vpcOfferingServiceMapDao;
        nsxService.site2SiteVpnConnectionDao = site2SiteVpnConnectionDao;
        nsxService.site2SiteVpnGatewayDao = site2SiteVpnGatewayDao;
        nsxService.alertManager = alertManager;
        nsxService.nsxVrfGatewayDao = nsxVrfGatewayDao;
        nsxService.domainDao = domainDao;
    }

    @After
    public void teardown() throws Exception {
        closeable.close();
    }

    private NsxVrfGatewayVO vrfGateway(String tier0, String edgeCluster) {
        return new NsxVrfGatewayVO(zoneId, tier0, edgeCluster, "v5-T0");
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
    public void testApplyVrfGatewaySetsTier0AndEdgeClusterOnTheCommand() {
        Mockito.doReturn("ACCOUNT").when(nsxService).getVrfScope(zoneId);
        when(nsxVrfGatewayDao.findByAccount(zoneId, accountId))
                .thenReturn(vrfGateway("CS-VRF-001", "v5-EdgeCluster-HOSTED"));
        CreateNsxTier1GatewayCommand cmd =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, 1L, "VPC01", true, true);

        nsxService.applyVrfGateway(cmd, zoneId, accountId, domainId);

        assertEquals("CS-VRF-001", cmd.getTier0Gateway());
        assertEquals("v5-EdgeCluster-HOSTED", cmd.getEdgeCluster());
    }

    @Test
    public void testApplyVrfGatewayLeavesCommandUntouchedWhenScopeIsNone() {
        Mockito.doReturn("NONE").when(nsxService).getVrfScope(zoneId);
        CreateNsxTier1GatewayCommand cmd =
                new CreateNsxTier1GatewayCommand(domainId, accountId, zoneId, 1L, "VPC01", true, true);

        nsxService.applyVrfGateway(cmd, zoneId, accountId, domainId);

        // Null on both means the resource keeps using the zone-wide values, which is what
        // makes this change a no-op for every deployment that does not opt in.
        assertNull(cmd.getTier0Gateway());
        assertNull(cmd.getEdgeCluster());
    }

    @Test
    public void testCreateVpcNetwork() {
        NsxAnswer createNsxTier1GatewayAnswer = mock(NsxAnswer.class);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxTier1GatewayCommand.class), anyLong())).thenReturn(createNsxTier1GatewayAnswer);
        when(createNsxTier1GatewayAnswer.getResult()).thenReturn(true);

        assertTrue(nsxService.createVpcNetwork(1L, 3L, 2L, 5L, "VPC01", false));
    }

    @Test
    public void testDeleteVpcNetwork() {
        NsxAnswer deleteNsxTier1GatewayAnswer = mock(NsxAnswer.class);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxTier1GatewayCommand.class), anyLong())).thenReturn(deleteNsxTier1GatewayAnswer);
        when(deleteNsxTier1GatewayAnswer.getResult()).thenReturn(true);

        assertTrue(nsxService.deleteVpcNetwork(1L, 2L, 3L, 10L, "VPC01"));
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

    private VpcVO mockVpc() {
        VpcVO vpc = mock(VpcVO.class);
        when(vpc.getDomainId()).thenReturn(domainId);
        when(vpc.getAccountId()).thenReturn(accountId);
        when(vpc.getZoneId()).thenReturn(zoneId);
        when(vpc.getId()).thenReturn(9L);
        when(vpc.getName()).thenReturn("VPC01");
        return vpc;
    }

    @Test
    public void testCreateVpnGateway() {
        VpcVO vpc = mockVpc();
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxVpnGatewayCommand.class), eq(zoneId))).thenReturn(answer);

        assertTrue(nsxService.createVpnGateway(vpc, "10.1.13.30"));
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(CreateNsxVpnGatewayCommand.class), eq(zoneId));
    }

    @Test
    public void testDeleteVpnGateway() {
        VpcVO vpc = mockVpc();
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxVpnGatewayCommand.class), eq(zoneId))).thenReturn(answer);

        assertTrue(nsxService.deleteVpnGateway(vpc));
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(DeleteNsxVpnGatewayCommand.class), eq(zoneId));
    }

    @Test
    public void testCreateVpnConnection() {
        VpcVO vpc = mockVpc();
        when(vpc.getCidr()).thenReturn("10.10.0.0/16");
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxVpnConnectionCommand.class), eq(zoneId))).thenReturn(answer);

        assertTrue(nsxService.createVpnConnection(vpc, "conn-uuid", "203.0.113.10", "presharedkey",
                "aes256-sha256;modp2048", "aes128-sha1", 86400L, 3600L, true, "ikev2", false,
                List.of("192.168.100.0/24"), "169.254.64.21", "169.254.64.22", 30, "10.1.13.30"));
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(CreateNsxVpnConnectionCommand.class), eq(zoneId));
    }

    @Test
    public void testDeleteVpnConnection() {
        VpcVO vpc = mockVpc();
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(DeleteNsxVpnConnectionCommand.class), eq(zoneId))).thenReturn(answer);

        assertTrue(nsxService.deleteVpnConnection(vpc, "conn-uuid"));
        Mockito.verify(nsxControllerUtils).sendNsxCommand(any(DeleteNsxVpnConnectionCommand.class), eq(zoneId));
    }

    @Test
    public void testGetVpnConnectionStatus() {
        VpcVO vpc = mockVpc();
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getDetails()).thenReturn("UP");
        when(nsxControllerUtils.sendNsxCommand(any(GetNsxVpnSessionStatusCommand.class), eq(zoneId))).thenReturn(answer);

        assertEquals("UP", nsxService.getVpnConnectionStatus(vpc, "conn-uuid"));
    }

    @Test
    public void testCreateVpnConnectionCommandPayload() {
        VpcVO vpc = mockVpc();
        when(vpc.getCidr()).thenReturn("10.10.0.0/16");
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(nsxControllerUtils.sendNsxCommand(any(CreateNsxVpnConnectionCommand.class), eq(zoneId))).thenReturn(answer);

        assertTrue(nsxService.createVpnConnection(vpc, "conn-uuid", "203.0.113.10", "presharedkey",
                "aes256-sha256;modp2048", "aes128-sha1", 86400L, 3600L, true, "ikev2", true,
                List.of("192.168.100.0/24"), "169.254.64.21", "169.254.64.22", 30, "10.1.13.30"));

        ArgumentCaptor<CreateNsxVpnConnectionCommand> captor = ArgumentCaptor.forClass(CreateNsxVpnConnectionCommand.class);
        verify(nsxControllerUtils).sendNsxCommand(captor.capture(), eq(zoneId));
        CreateNsxVpnConnectionCommand command = captor.getValue();
        assertEquals("conn-uuid", command.getConnectionUuid());
        assertEquals("203.0.113.10", command.getPeerAddress());
        assertEquals("presharedkey", command.getPsk());
        assertEquals("169.254.64.21", command.getVtiLocalIp());
        assertEquals("169.254.64.22", command.getVtiPeerIp());
        assertEquals(30, command.getVtiPrefixLength());
        assertEquals("10.10.0.0/16", command.getVpcCidr());
        assertEquals(List.of("192.168.100.0/24"), command.getPeerCidrs());
        assertEquals("10.1.13.30", command.getLocalEndpointIp());
        assertTrue(command.isPassive());
    }

    private Site2SiteVpnConnectionVO vpnConnection(Site2SiteVpnConnection.State state) {
        Site2SiteVpnConnectionVO connection = new Site2SiteVpnConnectionVO(accountId, domainId, 4L, 5L, false);
        connection.setState(state);
        return connection;
    }

    private NsxAnswer mockVpnStatusAnswer(String status) {
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getDetails()).thenReturn(status);
        when(nsxControllerUtils.sendNsxCommand(any(GetNsxVpnSessionStatusCommand.class), eq(zoneId))).thenReturn(answer);
        return answer;
    }

    @Test
    public void testPollVpnConnectionStatusTransitionsToConnectedOnUp() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Disconnected);
        mockVpnStatusAnswer("UP");
        when(site2SiteVpnConnectionDao.acquireInLockTable(connection.getId())).thenReturn(connection);
        when(site2SiteVpnConnectionDao.findById(connection.getId())).thenReturn(connection);

        nsxService.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Connected, connection.getState());
        verify(site2SiteVpnConnectionDao).persist(connection);
        verify(alertManager).sendAlert(any(), eq(zoneId), isNull(), anyString(), anyString());
    }

    @Test
    public void testPollVpnConnectionStatusTransitionsToDisconnectedOnDown() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Connected);
        mockVpnStatusAnswer("DOWN");
        when(site2SiteVpnConnectionDao.acquireInLockTable(connection.getId())).thenReturn(connection);
        when(site2SiteVpnConnectionDao.findById(connection.getId())).thenReturn(connection);

        nsxService.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Disconnected, connection.getState());
        verify(site2SiteVpnConnectionDao).persist(connection);
    }

    @Test
    public void testPollVpnConnectionStatusFailuresNeverTransitionState() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Connected);
        when(nsxControllerUtils.sendNsxCommand(any(GetNsxVpnSessionStatusCommand.class), eq(zoneId)))
                .thenThrow(new CloudRuntimeException("NSX unreachable"));

        for (int i = 0; i < NsxServiceImpl.VPN_STATUS_POLL_FAILURE_THRESHOLD; i++) {
            nsxService.pollVpnConnectionStatus(connection, vpc);
        }

        assertEquals(Site2SiteVpnConnection.State.Connected, connection.getState());
        verify(site2SiteVpnConnectionDao, never()).persist(any());
        verify(alertManager, times(1)).sendAlert(any(), eq(zoneId), isNull(), anyString(), anyString());

        // the failure counter was reset at the threshold: a single further failure does not alert again
        nsxService.pollVpnConnectionStatus(connection, vpc);
        verify(alertManager, times(1)).sendAlert(any(), eq(zoneId), isNull(), anyString(), anyString());
    }

    @Test
    public void testPollVpnConnectionStatusFailureCounterResetsOnSuccess() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Connected);
        NsxAnswer answer = mock(NsxAnswer.class);
        when(answer.getDetails()).thenReturn("UP");
        when(nsxControllerUtils.sendNsxCommand(any(GetNsxVpnSessionStatusCommand.class), eq(zoneId)))
                .thenThrow(new CloudRuntimeException("NSX unreachable"))
                .thenThrow(new CloudRuntimeException("NSX unreachable"))
                .thenReturn(answer)
                .thenThrow(new CloudRuntimeException("NSX unreachable"))
                .thenThrow(new CloudRuntimeException("NSX unreachable"));

        for (int i = 0; i < 5; i++) {
            nsxService.pollVpnConnectionStatus(connection, vpc);
        }

        // never three consecutive failures, so no alert and no transition
        verify(alertManager, never()).sendAlert(any(), anyLong(), isNull(), anyString(), anyString());
        assertEquals(Site2SiteVpnConnection.State.Connected, connection.getState());
    }

    @Test
    public void testPollVpnConnectionStatusTransitionsToErrorWhenSessionVanished() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Connected);
        mockVpnStatusAnswer("NOT_FOUND");
        when(site2SiteVpnConnectionDao.acquireInLockTable(connection.getId())).thenReturn(connection);
        when(site2SiteVpnConnectionDao.findById(connection.getId())).thenReturn(connection);

        nsxService.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Error, connection.getState());
        verify(site2SiteVpnConnectionDao).persist(connection);
    }

    @Test
    public void testPollVpnConnectionStatusKeepsConnectingStateWhenSessionNotFound() {
        VpcVO vpc = mockVpc();
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Connecting);
        mockVpnStatusAnswer("NOT_FOUND");

        nsxService.pollVpnConnectionStatus(connection, vpc);

        assertEquals(Site2SiteVpnConnection.State.Connecting, connection.getState());
        verify(site2SiteVpnConnectionDao, never()).persist(any());
    }

    @Test
    public void testTransitionVpnConnectionStateSkipsWhenLockCannotBeAcquired() {
        VpcVO vpc = mock(VpcVO.class);
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Disconnected);
        when(site2SiteVpnConnectionDao.acquireInLockTable(connection.getId())).thenReturn(null);

        nsxService.transitionVpnConnectionState(connection, vpc, Site2SiteVpnConnection.State.Connected);

        verify(site2SiteVpnConnectionDao, never()).persist(any());
    }

    @Test
    public void testTransitionVpnConnectionStateRechecksStateUnderLock() {
        VpcVO vpc = mock(VpcVO.class);
        Site2SiteVpnConnectionVO connection = vpnConnection(Site2SiteVpnConnection.State.Disconnected);
        Site2SiteVpnConnectionVO lockedConnection = vpnConnection(Site2SiteVpnConnection.State.Pending);
        when(site2SiteVpnConnectionDao.acquireInLockTable(connection.getId())).thenReturn(connection);
        when(site2SiteVpnConnectionDao.findById(connection.getId())).thenReturn(lockedConnection);

        nsxService.transitionVpnConnectionState(connection, vpc, Site2SiteVpnConnection.State.Connected);

        verify(site2SiteVpnConnectionDao, never()).persist(any());
        verify(alertManager, never()).sendAlert(any(), anyLong(), isNull(), anyString(), anyString());
    }
}
