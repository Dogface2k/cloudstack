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
package org.apache.cloudstack.utils;

import com.cloud.dc.DataCenter;
import com.cloud.domain.DomainVO;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import org.apache.cloudstack.agent.api.CreateNsxDhcpRelayConfigCommand;
import org.apache.cloudstack.agent.api.CreateNsxSegmentCommand;
import org.apache.cloudstack.agent.api.CreateOrUpdateNsxTier1NatRuleCommand;

import java.util.List;
import java.util.Set;

public class NsxHelper {

    public static final int VPN_VTI_PREFIX_LENGTH = 30;

    private static final long VPN_VTI_SUBNET_BASE = NetUtils.ip2Long("169.254.64.0");
    // 169.254.64.0/18 provides 4096 /30 slots
    private static final long VPN_VTI_SUBNET_SLOTS = 4096L;

    private NsxHelper() {
    }

    /**
     * Derives the preferred VTI /30 for a Site-to-Site VPN connection from its database id:
     * 169.254.64.0/18 base + 4 x (id mod 4096), local = .1 and peer = .2 within the /30.
     * Collisions between connections whose ids are congruent mod 4096 are resolved by
     * {@link #findFreeVpnVtiAddressPair(String, Set)} against the tier-1's in-use VTI addresses.
     */
    public static Pair<String, String> getVpnVtiAddressPair(long connectionId) {
        long slotBase = VPN_VTI_SUBNET_BASE + (connectionId % VPN_VTI_SUBNET_SLOTS) * 4;
        return new Pair<>(NetUtils.long2Ip(slotBase + 1), NetUtils.long2Ip(slotBase + 2));
    }

    /**
     * Returns the preferred VTI /30 when its local address is not in use on the tier-1 gateway,
     * otherwise linear-probes the following slots (wrapping within 169.254.64.0/18) for the first
     * free one; throws once all 4096 slots are taken.
     */
    public static Pair<String, String> findFreeVpnVtiAddressPair(String preferredVtiLocalIp, Set<String> inUseVtiLocalIps) {
        long startSlot = (NetUtils.ip2Long(preferredVtiLocalIp) - 1 - VPN_VTI_SUBNET_BASE) / 4;
        for (long probe = 0; probe < VPN_VTI_SUBNET_SLOTS; probe++) {
            long slotBase = VPN_VTI_SUBNET_BASE + ((startSlot + probe) % VPN_VTI_SUBNET_SLOTS) * 4;
            String vtiLocalIp = NetUtils.long2Ip(slotBase + 1);
            if (!inUseVtiLocalIps.contains(vtiLocalIp)) {
                return new Pair<>(vtiLocalIp, NetUtils.long2Ip(slotBase + 2));
            }
        }
        throw new CloudRuntimeException("No free VTI slots are left in 169.254.64.0/18 for the Site-to-Site VPN connection");
    }

    public static CreateNsxDhcpRelayConfigCommand createNsxDhcpRelayConfigCommand(DomainVO domain, Account account, DataCenter zone, VpcVO vpc, Network network, List<String> addresses) {
        Long vpcId = vpc != null ? vpc.getId() : null;
        String vpcName = vpc != null ? vpc.getName() : null;
        return new CreateNsxDhcpRelayConfigCommand(domain.getId(), account.getId(), zone.getId(),
                vpcId, vpcName, network.getId(), network.getName(), addresses);
    }

    public static CreateNsxSegmentCommand createNsxSegmentCommand(DomainVO domain, Account account, DataCenter zone, String vpcName, NetworkVO networkVO) {
        return new CreateNsxSegmentCommand(domain.getId(), account.getId(), zone.getId(),
                networkVO.getVpcId(), vpcName, networkVO.getId(), networkVO.getName(), networkVO.getGateway(), networkVO.getCidr());
    }

    public static CreateOrUpdateNsxTier1NatRuleCommand createOrUpdateNsxNatRuleCommand(long domainId, long accountId, long zoneId,
                                                                                       String tier1Gateway, String action, String ipAddress,
                                                                                       String natRuleId) {
        return new CreateOrUpdateNsxTier1NatRuleCommand(domainId, accountId, zoneId, tier1Gateway, action, ipAddress, natRuleId);
    }
}
