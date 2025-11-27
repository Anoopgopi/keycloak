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

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import org.jboss.logging.Logger;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * Default implementation of RedisConnectionProvider.
 * <p>
 * Manages connections to Redis standalone or cluster mode and provides
 * cache operations via Lettuce commands.
 *
 * @author Keycloak Team
 */
public class DefaultRedisConnectionProvider implements RedisConnectionProvider {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProvider.class);

    private final StatefulRedisConnection<String, byte[]> connection;
    private final StatefulRedisConnection<String, String> stringConnection;
    private final StatefulRedisClusterConnection<String, byte[]> clusterConnection;
    private final StatefulRedisClusterConnection<String, String> clusterStringConnection;
    private final boolean clusterMode;
    private final String keyPrefix;
    private final String connectionInfo;

    /**
     * Constructor for standalone Redis mode.
     */
    public DefaultRedisConnectionProvider(
            StatefulRedisConnection<String, byte[]> connection,
            StatefulRedisConnection<String, String> stringConnection,
            String connectionInfo) {
        this.connection = connection;
        this.stringConnection = stringConnection;
        this.clusterConnection = null;
        this.clusterStringConnection = null;
        this.clusterMode = false;
        this.keyPrefix = DEFAULT_KEY_PREFIX;
        this.connectionInfo = connectionInfo;
    }

    /**
     * Constructor for Redis cluster mode.
     */
    public DefaultRedisConnectionProvider(
            StatefulRedisClusterConnection<String, byte[]> clusterConnection,
            StatefulRedisClusterConnection<String, String> clusterStringConnection,
            String connectionInfo) {
        this.connection = null;
        this.stringConnection = null;
        this.clusterConnection = clusterConnection;
        this.clusterStringConnection = clusterStringConnection;
        this.clusterMode = true;
        this.keyPrefix = DEFAULT_KEY_PREFIX;
        this.connectionInfo = connectionInfo;
    }

    @Override
    @SuppressWarnings("unchecked")
    public RedisCommands<String, byte[]> getCache(String cacheName) {
        if (clusterMode) {
            // RedisAdvancedClusterCommands extends RedisClusterCommands which has same methods
            return (RedisCommands<String, byte[]>) (Object) clusterConnection.sync();
        }
        return connection.sync();
    }

    @Override
    @SuppressWarnings("unchecked")
    public RedisCommands<String, String> getRevisionsCache(String cacheName) {
        if (clusterMode) {
            return (RedisCommands<String, String>) (Object) clusterStringConnection.sync();
        }
        return stringConnection.sync();
    }

    @Override
    public boolean isClusterMode() {
        return clusterMode;
    }

    @Override
    public RedisClusterCommands<String, byte[]> getClusterCommands() {
        if (!clusterMode) {
            return null;
        }
        return clusterConnection.sync();
    }

    @Override
    public Executor getExecutor() {
        // Use common pool for now; can be configured later
        return ForkJoinPool.commonPool();
    }

    @Override
    public boolean isHealthy() {
        try {
            if (clusterMode) {
                String pong = clusterStringConnection.sync().ping();
                return "PONG".equalsIgnoreCase(pong);
            } else {
                String pong = stringConnection.sync().ping();
                return "PONG".equalsIgnoreCase(pong);
            }
        } catch (Exception e) {
            logger.warn("Redis health check failed", e);
            return false;
        }
    }

    @Override
    public String getCacheKeyPrefix(String cacheName) {
        return keyPrefix + cacheName + ":";
    }

    @Override
    public String getConnectionInfo() {
        return connectionInfo;
    }

    @Override
    public void close() {
        // Connections are managed by the factory
        // This provider instance doesn't own the connections
    }
}
