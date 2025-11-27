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

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
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
 * <p>
 * Supports both standalone Redis and Redis Cluster modes.
 * Connection configuration is read from Keycloak's SPI configuration.
 *
 * @author Keycloak Team
 */
public class DefaultRedisConnectionProviderFactory 
        implements RedisConnectionProviderFactory, ServerInfoAwareProviderFactory {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProviderFactory.class);

    // Configuration keys
    private static final String CONFIG_HOST = "host";
    private static final String CONFIG_PORT = "port";
    private static final String CONFIG_PASSWORD = "password";
    private static final String CONFIG_DATABASE = "database";
    private static final String CONFIG_SSL = "ssl";
    private static final String CONFIG_CLUSTER = "cluster";
    private static final String CONFIG_TIMEOUT = "timeout";
    private static final String CONFIG_POOL_SIZE = "poolSize";

    private Config.Scope config;
    private volatile ClientResources clientResources;
    private volatile RedisClient redisClient;
    private volatile RedisClusterClient redisClusterClient;
    private volatile StatefulRedisConnection<String, byte[]> connection;
    private volatile StatefulRedisConnection<String, String> stringConnection;
    private volatile StatefulRedisClusterConnection<String, byte[]> clusterConnection;
    private volatile StatefulRedisClusterConnection<String, String> clusterStringConnection;
    private volatile RedisConnectionProvider connectionProvider;
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

        String host = config.get(CONFIG_HOST, "localhost");
        int port = config.getInt(CONFIG_PORT, 6379);
        String password = config.get(CONFIG_PASSWORD);
        int database = config.getInt(CONFIG_DATABASE, 0);
        boolean useSsl = config.getBoolean(CONFIG_SSL, false);
        clusterMode = config.getBoolean(CONFIG_CLUSTER, false);
        int timeout = config.getInt(CONFIG_TIMEOUT, 5000);
        int poolSize = config.getInt(CONFIG_POOL_SIZE, 8);

        // Build Redis URI
        RedisURI.Builder uriBuilder = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withDatabase(database)
                .withTimeout(Duration.ofMillis(timeout));

        if (password != null && !password.isEmpty()) {
            uriBuilder.withPassword(password.toCharArray());
        }
        if (useSsl) {
            uriBuilder.withSsl(true)
                      .withVerifyPeer(true);
        }

        RedisURI redisURI = uriBuilder.build();
        connectionInfo = String.format("redis%s://%s:%d/%d", 
            useSsl ? "s" : "", host, port, database);

        // Create shared client resources
        clientResources = DefaultClientResources.builder()
                .ioThreadPoolSize(poolSize)
                .computationThreadPoolSize(poolSize)
                .build();

        if (clusterMode) {
            initClusterMode(redisURI, timeout);
        } else {
            initStandaloneMode(redisURI, timeout);
        }

        logger.infof("Redis connection established: %s (cluster=%s)", connectionInfo, clusterMode);
    }

    private void initStandaloneMode(RedisURI redisURI, int timeout) {
        // Create client with options
        ClientOptions clientOptions = ClientOptions.builder()
                .autoReconnect(true)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(timeout)))
                .build();

        redisClient = RedisClient.create(clientResources, redisURI);
        redisClient.setOptions(clientOptions);

        // Create connections with different codecs
        connection = redisClient.connect(
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        );
        stringConnection = redisClient.connect(StringCodec.UTF8);

        connectionProvider = new DefaultRedisConnectionProvider(
            connection, stringConnection, connectionInfo
        );

        logger.info("Connected to Redis standalone at " + redisURI.getHost() + ":" + redisURI.getPort());
    }

    private void initClusterMode(RedisURI redisURI, int timeout) {
        // Cluster topology refresh options
        ClusterTopologyRefreshOptions topologyRefreshOptions = ClusterTopologyRefreshOptions.builder()
                .enablePeriodicRefresh(Duration.ofMinutes(1))
                .enableAllAdaptiveRefreshTriggers()
                .build();

        ClusterClientOptions clusterOptions = ClusterClientOptions.builder()
                .autoReconnect(true)
                .topologyRefreshOptions(topologyRefreshOptions)
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(timeout)))
                .build();

        redisClusterClient = RedisClusterClient.create(clientResources, redisURI);
        redisClusterClient.setOptions(clusterOptions);

        // Create cluster connections with different codecs
        clusterConnection = redisClusterClient.connect(
            RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE)
        );
        clusterStringConnection = redisClusterClient.connect(StringCodec.UTF8);

        connectionProvider = new DefaultRedisConnectionProvider(
            clusterConnection, clusterStringConnection, connectionInfo
        );

        logger.info("Connected to Redis Cluster at " + redisURI.getHost() + ":" + redisURI.getPort());
    }

    @Override
    public void init(Config.Scope config) {
        this.config = config;
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Optionally pre-initialize the connection
        // lazyInit();
    }

    @Override
    public void close() {
        logger.debug("Closing Redis connections");

        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis connection", e);
            }
        }
        if (stringConnection != null) {
            try {
                stringConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis string connection", e);
            }
        }
        if (clusterConnection != null) {
            try {
                clusterConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis cluster connection", e);
            }
        }
        if (clusterStringConnection != null) {
            try {
                clusterStringConnection.close();
            } catch (Exception e) {
                logger.warn("Error closing Redis cluster string connection", e);
            }
        }
        if (redisClient != null) {
            try {
                redisClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Redis client", e);
            }
        }
        if (redisClusterClient != null) {
            try {
                redisClusterClient.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down Redis cluster client", e);
            }
        }
        if (clientResources != null) {
            try {
                clientResources.shutdown();
            } catch (Exception e) {
                logger.warn("Error shutting down client resources", e);
            }
        }
    }

    @Override
    public String getId() {
        return "default";
    }

    @Override
    public List<ProviderConfigProperty> getConfigMetadata() {
        return ProviderConfigurationBuilder.create()
                .property()
                    .name(CONFIG_HOST)
                    .type("string")
                    .label("Host")
                    .helpText("Redis server hostname")
                    .defaultValue("localhost")
                    .add()
                .property()
                    .name(CONFIG_PORT)
                    .type("int")
                    .label("Port")
                    .helpText("Redis server port")
                    .defaultValue("6379")
                    .add()
                .property()
                    .name(CONFIG_PASSWORD)
                    .type("password")
                    .label("Password")
                    .helpText("Redis server password (optional)")
                    .add()
                .property()
                    .name(CONFIG_DATABASE)
                    .type("int")
                    .label("Database")
                    .helpText("Redis database index (0-15)")
                    .defaultValue("0")
                    .add()
                .property()
                    .name(CONFIG_SSL)
                    .type("boolean")
                    .label("SSL/TLS")
                    .helpText("Enable SSL/TLS encryption")
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
                    .label("Timeout")
                    .helpText("Connection timeout in milliseconds")
                    .defaultValue("5000")
                    .add()
                .property()
                    .name(CONFIG_POOL_SIZE)
                    .type("int")
                    .label("Pool Size")
                    .helpText("Number of I/O and computation threads")
                    .defaultValue("8")
                    .add()
                .build();
    }

    @Override
    public Map<String, String> getOperationalInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("provider", "Lettuce Redis Client");
        info.put("clusterMode", String.valueOf(clusterMode));
        if (connectionInfo != null) {
            info.put("connection", connectionInfo);
        }
        if (connectionProvider != null) {
            info.put("healthy", String.valueOf(connectionProvider.isHealthy()));
        }
        return info;
    }
}
