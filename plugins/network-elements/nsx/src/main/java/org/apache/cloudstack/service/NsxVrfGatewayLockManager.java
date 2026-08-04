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

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import com.cloud.network.nsx.NsxService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class NsxVrfGatewayLockManager {

    private static final int LOCK_TIMEOUT_SECONDS = 30;

    public <T> T withZoneLock(long zoneId, Supplier<T> operation) {
        return withLock(NsxService.getVrfZoneLockName(zoneId), operation);
    }

    public <T> T withPlacementLock(boolean vpc, long resourceId, Supplier<T> operation) {
        return withLock(String.format("NsxVrfGateway.Placement.%s.%s", vpc ? "Vpc" : "Network", resourceId),
                operation);
    }

    private <T> T withLock(String name, Supplier<T> operation) {
        GlobalLock lock = GlobalLock.getInternLock(name);
        try {
            if (!lock.lock(LOCK_TIMEOUT_SECONDS)) {
                throw new CloudRuntimeException(String.format("Timed out waiting for lock %s", name));
            }
            try {
                return operation.get();
            } finally {
                lock.unlock();
            }
        } finally {
            lock.releaseRef();
        }
    }
}
