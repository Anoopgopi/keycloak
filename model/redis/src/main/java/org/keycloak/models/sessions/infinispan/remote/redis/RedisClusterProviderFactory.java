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

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.cluster.ClusterProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.UUID;

/**
 * Factory for RedisClusterProvider.
 */
public class RedisClusterProviderFactory implements ClusterProviderFactory {

    private static final Logger logger = Logger.getLogger(RedisClusterProviderFactory.class);

    public static final String PROVIDER_ID = "redis";

    private String nodeId;

    @Override
    public ClusterProvider create(KeycloakSession session) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            throw new IllegalStateException("RedisConnectionProvider not available");
        }
        return new RedisClusterProvider(redis, nodeId);
    }

    @Override
    public void init(Config.Scope config) {
        nodeId = config.get("nodeId", UUID.randomUUID().toString());
        logger.infof("Redis ClusterProviderFactory initialized with nodeId: %s", nodeId);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
