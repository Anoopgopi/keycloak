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

package org.keycloak.models.sessions.infinispan.remote.redis;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.provider.ServerInfoAwareProviderFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory for creating Redis connection providers using Lettuce.
 * Supports both standalone and cluster mode configurations.
 */
public class DefaultRedisConnectionProviderFactory implements RedisConnectionProviderFactory, ServerInfoAwareProviderFactory {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);

    public static final String PROVIDER_ID = "default";

    // Configuration keys
    private static final String CONFIG_HOST = "host";
    private static final String CONFIG_PORT = "port";
    private static final String CONFIG_PASSWORD = "password";
    private static final String CONFIG_DATABASE = "database";
    private static final String CONFIG_SSL = "ssl";
    private static final String CONFIG_CLUSTER = "cluster";
    private static final String CONFIG_TIMEOUT = "timeout";
    private static final String CONFIG_KEY_PREFIX = "keyPrefix";

    // Default values
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_DATABASE = 0;
    private static final int DEFAULT_TIMEOUT = 5000;
    private static final String DEFAULT_KEY_PREFIX = "kc:";

    private Config.Scope config;
    private volatile ClientResources clientResources;
    private volatile RedisClient redisClient;
    private volatile RedisClusterClient redisClusterClient;
    private volatile StatefulRedisConnection<String, String> connection;
    private volatile StatefulRedisClusterConnection<String, String> clusterConnection;
    private volatile DefaultRedisConnectionProvider connectionProvider;
    private volatile boolean clusterMode;
    private volatile String connectionInfo;

    @Override
    public RedisConnectionProvider create(KeycloakSession session) {
        lazyInit();
        return connectionProvider;
    }

    private synchronized void lazyInit() {
        if (connectionProvider != null) {
            return;
        }

        String host = config.get(CONFIG_HOST, DEFAULT_HOST);
        int port = config.getInt(CONFIG_PORT, DEFAULT_PORT);
        String password = config.get(CONFIG_PASSWORD);
        int database = config.getInt(CONFIG_DATABASE, DEFAULT_DATABASE);
        boolean ssl = config.getBoolean(CONFIG_SSL, false);
        clusterMode = config.getBoolean(CONFIG_CLUSTER, false);
        int timeout = config.getInt(CONFIG_TIMEOUT, DEFAULT_TIMEOUT);
        String keyPrefix = config.get(CONFIG_KEY_PREFIX, DEFAULT_KEY_PREFIX);

        logger.infof("Initializing Redis connection: host=%s, port=%d, cluster=%s, ssl=%s",
                host, port, clusterMode, ssl);

        // Create client resources (thread pool, etc.)
        clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(4)
                .computationThreadPoolSize(4)
                .build();

        // Build Redis URI
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withSsl(ssl)
                .withTimeout(Duration.ofMillis(timeout));

        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }

        RedisURI redisURI = uriBuilder.build();
        connectionInfo = String.format("redis%s://%s:%d/%d", ssl ? "s" : "", host, port, database);

        if (clusterMode) {
            initClusterMode(redisURI, keyPrefix);
        } else {
            initStandaloneMode(redisURI, keyPrefix);
        }

        logger.info("Redis connection initialized successfully");
    }

    private void initStandaloneMode(RedisURI redisURI, String keyPrefix) {
        redisClient = RedisClient.create(clientResources, redisURI);
        connection = redisClient.connect();
        connectionProvider = new DefaultRedisConnectionProvider(redisClient, connection, keyPrefix, connectionInfo);
    }

    private void initClusterMode(RedisURI redisURI, String keyPrefix) {
        redisClusterClient = RedisClusterClient.create(clientResources, redisURI);
        clusterConnection = redisClusterClient.connect();
        connectionProvider = new DefaultRedisConnectionProvider(redisClusterClient, clusterConnection, keyPrefix, connectionInfo);
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Lazy initialization - connection will be created on first use
    }

    @Override
    public void close() {
        logger.info("Closing Redis connections");

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis connection", e);
            }
            connection = null;
        }

        if (clusterConnection != null) {
            try {
                clusterConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis cluster connection", e);
            }
            clusterConnection = null;
        }

        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Redis client", e);
            }
            redisClient = null;
        }

        if (redisClusterClient != null) {
            try {
                redisClusterClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Redis cluster client", e);
            }
            redisClusterClient = null;
        }

        if (clientResources != null) {
            try {
                clientResources.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down client resources", e);
            }
            clientResources = null;
        }

        connectionProvider = null;
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(CONFIG_HOST)
                    .type("string")
                    .label("Redis Host")
                    .helpText("Redis server hostname or IP address")
                    .defaultValue(DEFAULT_HOST)
                    .add()
                .property()
                    .name(CONFIG_PORT)
                    .type("int")
                    .label("Redis Port")
                    .helpText("Redis server port")
                    .defaultValue(String.valueOf(DEFAULT_PORT))
                    .add()
                .property()
                    .name(CONFIG_PASSWORD)
                    .type("password")
                    .label("Redis Password")
                    .helpText("Redis server password (optional)")
                    .add()
                .property()
                    .name(CONFIG_DATABASE)
                    .type("int")
                    .label("Redis Database")
                    .helpText("Redis database number (0-15)")
                    .defaultValue(String.valueOf(DEFAULT_DATABASE))
                    .add()
                .property()
                    .name(CONFIG_SSL)
                    .type("boolean")
                    .label("SSL/TLS")
                    .helpText("Enable SSL/TLS for Redis connection")
                    .defaultValue("false")
                    .add()
                .property()
                    .name(CONFIG_CLUSTER)
                    .type("boolean")
                    .label("Cluster Mode")
                    .helpText("Enable Redis Cluster mode")
                    .defaultValue("false")
                    .add()
                .property()
                    .name(CONFIG_TIMEOUT)
                    .type("int")
                    .label("Connection Timeout")
                    .helpText("Connection timeout in milliseconds")
                    .defaultValue(String.valueOf(DEFAULT_TIMEOUT))
                    .add()
                .property()
                    .name(CONFIG_KEY_PREFIX)
                    .type("string")
                    .label("Key Prefix")
                    .helpText("Prefix for all Redis keys")
                    .defaultValue(DEFAULT_KEY_PREFIX)
                    .add()
                .build();
    }

    @Override
    public Map<String, String> getOperationalInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("provider", PROVIDER_ID);

        if (connectionProvider != null) {
            info.put("connected", String.valueOf(connectionProvider.isHealthy()));
            info.put("clusterMode", String.valueOf(clusterMode));
            info.put("connectionInfo", connectionInfo);
        } else {
            info.put("connected", "false");
            info.put("status", "not initialized");
        }

        return info;
    }
}
