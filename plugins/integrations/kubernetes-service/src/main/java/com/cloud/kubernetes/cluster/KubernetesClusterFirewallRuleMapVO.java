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

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

import java.util.Date;

import org.apache.cloudstack.api.InternalIdentity;

import com.cloud.utils.db.GenericDao;

@Entity
@Table(name = "kubernetes_cluster_firewall_rule_map")
public class KubernetesClusterFirewallRuleMapVO implements InternalIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "cluster_id")
    private long clusterId;

    @Column(name = "firewall_rule_id")
    private long firewallRuleId;

    @Column(name = "logical_role")
    private String logicalRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_state")
    private KubernetesClusterNetworkRuleLifecycleState lifecycleState;

    @Column(name = GenericDao.CREATED_COLUMN)
    private Date created;

    @Column(name = "updated")
    private Date updated;

    public KubernetesClusterFirewallRuleMapVO() {
    }

    public KubernetesClusterFirewallRuleMapVO(long clusterId, long firewallRuleId, String logicalRole,
            KubernetesClusterNetworkRuleLifecycleState lifecycleState) {
        this.clusterId = clusterId;
        this.firewallRuleId = firewallRuleId;
        this.logicalRole = logicalRole;
        this.lifecycleState = lifecycleState;
        this.updated = new Date();
    }

    @Override
    public long getId() {
        return id;
    }

    public long getFirewallRuleId() {
        return firewallRuleId;
    }

    public long getClusterId() {
        return clusterId;
    }

    public String getLogicalRole() {
        return logicalRole;
    }

    public KubernetesClusterNetworkRuleLifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public void setLifecycleState(KubernetesClusterNetworkRuleLifecycleState lifecycleState) {
        this.lifecycleState = lifecycleState;
        this.updated = new Date();
    }

    public Date getCreated() {
        return created;
    }

    public Date getUpdated() {
        return updated;
    }
}
