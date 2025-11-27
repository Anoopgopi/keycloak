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

package org.keycloak.connections.redis;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.keycloak.provider.Provider;

import java.util.concurrent.Executor;

/**
 * Provider for Redis connections, analogous to InfinispanConnectionProvider.
 * <p>
 * This provider manages connections to Redis (standalone or cluster mode)
 * and provides access to cache operations via Lettuce commands.
 *
 * @author Keycloak Team
 */
public interface RedisConnectionProvider extends Provider {

    // Cache names - mirror Infinispan naming conventions
    String REALM_CACHE_NAME = "realms";
    String REALM_REVISIONS_CACHE_NAME = "realmRevisions";
    int REALM_REVISIONS_CACHE_DEFAULT_MAX = 20000;

    String USER_CACHE_NAME = "users";
    String USER_REVISIONS_CACHE_NAME = "userRevisions";
    int USER_REVISIONS_CACHE_DEFAULT_MAX = 100000;

    String USER_SESSION_CACHE_NAME = "sessions";
    String CLIENT_SESSION_CACHE_NAME = "clientSessions";
    String OFFLINE_USER_SESSION_CACHE_NAME = "offlineSessions";
    String OFFLINE_CLIENT_SESSION_CACHE_NAME = "offlineClientSessions";
    String LOGIN_FAILURE_CACHE_NAME = "loginFailures";
    String AUTHENTICATION_SESSIONS_CACHE_NAME = "authenticationSessions";
    String WORK_CACHE_NAME = "work";
    String AUTHORIZATION_CACHE_NAME = "authorization";
    String AUTHORIZATION_REVISIONS_CACHE_NAME = "authorizationRevisions";

    String ACTION_TOKEN_CACHE = "actionTokens";
    String KEYS_CACHE_NAME = "keys";
    String CRL_CACHE_NAME = "crl";

    // Default key prefix for all Keycloak caches
    String DEFAULT_KEY_PREFIX = "kc:";

    /**
     * Get Redis commands for a specific cache.
     * The cache is implemented as keys with a specific prefix.
     *
     * @param cacheName logical cache name
     * @return Redis commands interface for byte array values
     */
    RedisCommands<String, byte[]> getCache(String cacheName);

    /**
     * Get Redis commands for revisions cache (String values for atomic operations).
     *
     * @param cacheName logical cache name
     * @return Redis commands for String values
     */
    RedisCommands<String, String> getRevisionsCache(String cacheName);

    /**
     * Check if Redis cluster mode is enabled.
     *
     * @return true if connected to a Redis cluster
     */
    boolean isClusterMode();

    /**
     * Get cluster commands if in cluster mode.
     *
     * @return Redis cluster commands, or null if not in cluster mode
     */
    RedisClusterCommands<String, byte[]> getClusterCommands();

    /**
     * Get executor for async operations.
     *
     * @return executor service
     */
    Executor getExecutor();

    /**
     * Health check for Redis connection.
     *
     * @return true if Redis is healthy and responding
     */
    boolean isHealthy();

    /**
     * Get the key prefix for a cache.
     * Keys are formatted as: {prefix}{cacheName}:{key}
     *
     * @param cacheName the cache name
     * @return the full key prefix including cache name
     */
    String getCacheKeyPrefix(String cacheName);

    /**
     * Get connection information for operational metrics.
     *
     * @return connection info string
     */
    String getConnectionInfo();
}
