/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.connections.redis.crossdc;

import java.util.Collections;
import java.util.List;

/**
 * Configuration for cross-datacenter Redis deployment.
 * <p>
 * Supports active-active multi-region deployments where cache invalidations
 * need to be propagated across datacenters.
 *
 * @author Keycloak Team
 */
public class CrossDCConfig {

    private final boolean enabled;
    private final String localSiteName;
    private final List<RemoteSiteConfig> remoteSites;
    private final ReplicationMode replicationMode;
    private final long syncTimeoutMs;
    private final int circuitBreakerThreshold;
    private final long circuitBreakerResetMs;
    private final int maxRetries;
    private final long retryDelayMs;

    private CrossDCConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.localSiteName = builder.localSiteName;
        this.remoteSites = Collections.unmodifiableList(builder.remoteSites);
        this.replicationMode = builder.replicationMode;
        this.syncTimeoutMs = builder.syncTimeoutMs;
        this.circuitBreakerThreshold = builder.circuitBreakerThreshold;
        this.circuitBreakerResetMs = builder.circuitBreakerResetMs;
        this.maxRetries = builder.maxRetries;
        this.retryDelayMs = builder.retryDelayMs;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getLocalSiteName() {
        return localSiteName;
    }

    public List<RemoteSiteConfig> getRemoteSites() {
        return remoteSites;
    }

    public ReplicationMode getReplicationMode() {
        return replicationMode;
    }

    public long getSyncTimeoutMs() {
        return syncTimeoutMs;
    }

    public int getCircuitBreakerThreshold() {
        return circuitBreakerThreshold;
    }

    public long getCircuitBreakerResetMs() {
        return circuitBreakerResetMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Replication mode for cross-DC synchronization.
     */
    public enum ReplicationMode {
        /**
         * Synchronous replication - wait for all sites to acknowledge.
         * Provides strong consistency but higher latency.
         */
        SYNC,

        /**
         * Asynchronous replication - fire and forget.
         * Lower latency but eventual consistency.
         */
        ASYNC,

        /**
         * Best-effort async with retry queue.
         * Balances consistency and performance.
         */
        ASYNC_WITH_RETRY
    }

    /**
     * Configuration for a remote site.
     */
    public static class RemoteSiteConfig {
        private final String siteName;
        private final String host;
        private final int port;
        private final String password;
        private final boolean ssl;
        private final int database;

        public RemoteSiteConfig(String siteName, String host, int port, 
                               String password, boolean ssl, int database) {
            this.siteName = siteName;
            this.host = host;
            this.port = port;
            this.password = password;
            this.ssl = ssl;
            this.database = database;
        }

        public String getSiteName() {
            return siteName;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public String getPassword() {
            return password;
        }

        public boolean isSsl() {
            return ssl;
        }

        public int getDatabase() {
            return database;
        }
    }

    public static class Builder {
        private boolean enabled = false;
        private String localSiteName = "site1";
        private List<RemoteSiteConfig> remoteSites = Collections.emptyList();
        private ReplicationMode replicationMode = ReplicationMode.ASYNC_WITH_RETRY;
        private long syncTimeoutMs = 5000;
        private int circuitBreakerThreshold = 5;
        private long circuitBreakerResetMs = 30000;
        private int maxRetries = 3;
        private long retryDelayMs = 1000;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder localSiteName(String localSiteName) {
            this.localSiteName = localSiteName;
            return this;
        }

        public Builder remoteSites(List<RemoteSiteConfig> remoteSites) {
            this.remoteSites = remoteSites;
            return this;
        }

        public Builder replicationMode(ReplicationMode mode) {
            this.replicationMode = mode;
            return this;
        }

        public Builder syncTimeoutMs(long syncTimeoutMs) {
            this.syncTimeoutMs = syncTimeoutMs;
            return this;
        }

        public Builder circuitBreakerThreshold(int threshold) {
            this.circuitBreakerThreshold = threshold;
            return this;
        }

        public Builder circuitBreakerResetMs(long resetMs) {
            this.circuitBreakerResetMs = resetMs;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder retryDelayMs(long retryDelayMs) {
            this.retryDelayMs = retryDelayMs;
            return this;
        }

        public CrossDCConfig build() {
            return new CrossDCConfig(this);
        }
    }
}
