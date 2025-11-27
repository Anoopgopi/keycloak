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

package org.keycloak.models.cache.redis;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterEvent;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.connections.redis.crossdc.CrossDCConfig;
import org.keycloak.connections.redis.crossdc.RedisCrossDCProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.cache.UserCacheProviderFactory;
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.provider.ServerInfoAwareProviderFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis-based UserCacheProviderFactory.
 * <p>
 * This factory creates Redis-backed user cache sessions and manages
 * cluster-wide cache invalidation via ClusterProvider events.
 * <p>
 * Supports multi-region active-active deployments with cross-DC replication.
 * <p>
 * To enable Redis user cache, configure:
 * <pre>
 * spi-user-cache-provider=redis
 * </pre>
 * <p>
 * For cross-DC replication:
 * <pre>
 * spi-user-cache-redis-cross-dc-enabled=true
 * spi-user-cache-redis-cross-dc-site-name=site1
 * spi-user-cache-redis-cross-dc-remote-sites=site2:redis2.example.com:6379,site3:redis3.example.com:6379
 * </pre>
 *
 * @author Keycloak Team
 */
public class RedisUserCacheProviderFactory 
        implements UserCacheProviderFactory, ServerInfoAwareProviderFactory {

    private static final Logger logger = Logger.getLogger(RedisUserCacheProviderFactory.class);

    public static final String PROVIDER_ID = "redis";

    // Cluster event keys for cache invalidation
    public static final String USER_CLEAR_CACHE_EVENTS = "REDIS_USER_CLEAR_CACHE_EVENTS";
    public static final String USER_INVALIDATION_EVENTS = "REDIS_USER_INVALIDATION_EVENTS";

    protected volatile RedisUserCacheManager userCache;
    protected volatile RedisCrossDCProvider crossDCProvider;
    private volatile boolean initialized = false;
    private Config.Scope config;

    @Override
    public UserCache create(KeycloakSession session) {
        lazyInit(session);
        return new RedisUserCacheSession(userCache, session);
    }

    private void lazyInit(KeycloakSession session) {
        if (userCache == null) {
            synchronized (this) {
                if (userCache == null) {
                    RedisConnectionProvider redisProvider = 
                        session.getProvider(RedisConnectionProvider.class);

                    if (redisProvider == null) {
                        throw new IllegalStateException(
                            "RedisConnectionProvider not available. " +
                            "Ensure Redis cache is properly configured with " +
                            "'spi-connections-redis-default-*' properties."
                        );
                    }

                    userCache = new RedisUserCacheManager(redisProvider);

                    // Initialize cross-DC if configured
                    initializeCrossDC();

                    // Register cluster listeners for cross-node invalidation
                    ClusterProvider cluster = session.getProvider(ClusterProvider.class);

                    cluster.registerListener(USER_INVALIDATION_EVENTS, (ClusterEvent event) -> {
                        if (event instanceof InvalidationEvent) {
                            InvalidationEvent invalidationEvent = (InvalidationEvent) event;
                            userCache.invalidationEventReceived(invalidationEvent);
                        }
                    });

                    cluster.registerListener(USER_CLEAR_CACHE_EVENTS, (ClusterEvent event) -> {
                        userCache.clear();
                    });

                    initialized = true;
                    logger.info("Redis UserCache initialized and cluster listeners registered");
                }
            }
        }
    }

    private void initializeCrossDC() {
        if (config == null) {
            return;
        }

        boolean crossDCEnabled = config.getBoolean("cross-dc-enabled", false);
        if (!crossDCEnabled) {
            logger.debug("Cross-DC replication disabled");
            return;
        }

        String localSiteName = config.get("cross-dc-site-name", "site1");
        String remoteSitesConfig = config.get("cross-dc-remote-sites", "");
        String replicationMode = config.get("cross-dc-replication-mode", "ASYNC_WITH_RETRY");
        long syncTimeout = config.getLong("cross-dc-sync-timeout", 5000L);
        int circuitBreakerThreshold = config.getInt("cross-dc-circuit-breaker-threshold", 5);
        long circuitBreakerReset = config.getLong("cross-dc-circuit-breaker-reset", 30000L);
        int maxRetries = config.getInt("cross-dc-max-retries", 3);
        long retryDelay = config.getLong("cross-dc-retry-delay", 1000L);

        List<CrossDCConfig.RemoteSiteConfig> remoteSites = parseRemoteSites(remoteSitesConfig);

        if (remoteSites.isEmpty()) {
            logger.warn("Cross-DC enabled but no remote sites configured");
            return;
        }

        CrossDCConfig crossDCConfig = CrossDCConfig.builder()
                .enabled(true)
                .localSiteName(localSiteName)
                .remoteSites(remoteSites)
                .replicationMode(CrossDCConfig.ReplicationMode.valueOf(replicationMode))
                .syncTimeoutMs(syncTimeout)
                .circuitBreakerThreshold(circuitBreakerThreshold)
                .circuitBreakerResetMs(circuitBreakerReset)
                .maxRetries(maxRetries)
                .retryDelayMs(retryDelay)
                .build();

        crossDCProvider = new RedisCrossDCProvider(crossDCConfig);
        crossDCProvider.start();
        userCache.setCrossDCProvider(crossDCProvider);

        logger.infof("Cross-DC replication enabled: local site '%s', remote sites: %d",
                localSiteName, remoteSites.size());
    }

    /**
     * Parse remote sites configuration string.
     * Format: site1:host1:port1[:password],site2:host2:port2[:password]
     */
    private List<CrossDCConfig.RemoteSiteConfig> parseRemoteSites(String configStr) {
        List<CrossDCConfig.RemoteSiteConfig> sites = new ArrayList<>();
        if (configStr == null || configStr.trim().isEmpty()) {
            return sites;
        }

        for (String siteStr : configStr.split(",")) {
            String[] parts = siteStr.trim().split(":");
            if (parts.length >= 3) {
                String siteName = parts[0].trim();
                String host = parts[1].trim();
                int port = Integer.parseInt(parts[2].trim());
                String password = parts.length > 3 ? parts[3].trim() : null;
                boolean ssl = parts.length > 4 && Boolean.parseBoolean(parts[4].trim());
                int database = parts.length > 5 ? Integer.parseInt(parts[5].trim()) : 0;

                sites.add(new CrossDCConfig.RemoteSiteConfig(
                        siteName, host, port, password, ssl, database
                ));
                logger.debugf("Configured remote site: %s -> %s:%d", siteName, host, port);
            } else {
                logger.warnf("Invalid remote site configuration: %s (expected format: name:host:port[:password:ssl:database])", siteStr);
            }
        }
        return sites;
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
        logger.debug("Redis UserCacheProviderFactory initialized");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Post-initialization if needed
    }

    @Override
    public void close() {
        // Close cross-DC provider
        if (crossDCProvider != null) {
            crossDCProvider.close();
            crossDCProvider = null;
        }
        userCache = null;
        initialized = false;
        logger.debug("Redis UserCacheProviderFactory closed");
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public int order() {
        // Higher order than Infinispan default (0) so Redis is not used unless explicitly configured
        return 100;
    }

    // Note: dependsOn() is not used as RedisConnectionProvider is not a standard Provider type
    // The dependency is handled through lazy initialization in create()

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name("enabled")
                    .type("boolean")
                    .label("Enabled")
                    .helpText("Enable Redis user cache provider")
                    .defaultValue("true")
                    .add()
                .property()
                    .name("cross-dc-enabled")
                    .type("boolean")
                    .label("Cross-DC Enabled")
                    .helpText("Enable cross-datacenter cache replication")
                    .defaultValue("false")
                    .add()
                .property()
                    .name("cross-dc-site-name")
                    .type("string")
                    .label("Local Site Name")
                    .helpText("Name of the local datacenter/site")
                    .defaultValue("site1")
                    .add()
                .property()
                    .name("cross-dc-remote-sites")
                    .type("string")
                    .label("Remote Sites")
                    .helpText("Comma-separated list of remote sites (format: name:host:port[:password:ssl:database])")
                    .add()
                .property()
                    .name("cross-dc-replication-mode")
                    .type("string")
                    .label("Replication Mode")
                    .helpText("Replication mode: SYNC, ASYNC, or ASYNC_WITH_RETRY")
                    .defaultValue("ASYNC_WITH_RETRY")
                    .add()
                .property()
                    .name("cross-dc-sync-timeout")
                    .type("long")
                    .label("Sync Timeout")
                    .helpText("Timeout for synchronous replication (ms)")
                    .defaultValue("5000")
                    .add()
                .property()
                    .name("cross-dc-circuit-breaker-threshold")
                    .type("int")
                    .label("Circuit Breaker Threshold")
                    .helpText("Number of failures before opening circuit breaker")
                    .defaultValue("5")
                    .add()
                .property()
                    .name("cross-dc-circuit-breaker-reset")
                    .type("long")
                    .label("Circuit Breaker Reset Time")
                    .helpText("Time before attempting to reset circuit breaker (ms)")
                    .defaultValue("30000")
                    .add()
                .build();
    }

    @Override
    public Map<String, String> getOperationalInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("provider", PROVIDER_ID);
        info.put("initialized", String.valueOf(initialized));
        if (userCache != null) {
            info.put("cacheManager", "RedisUserCacheManager");
            info.put("crossDCActive", String.valueOf(userCache.isCrossDCActive()));
        }
        if (crossDCProvider != null) {
            info.put("crossDCRetryQueueSize", String.valueOf(crossDCProvider.getRetryQueueSize()));
            crossDCProvider.getCircuitBreakerStats().forEach((site, stats) -> {
                info.put("crossDC." + site + ".state", stats.state.name());
                info.put("crossDC." + site + ".failures", String.valueOf(stats.failureCount));
            });
        }
        return info;
    }
}
