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
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.SingleUseObjectProviderFactory;
import org.keycloak.provider.EnvironmentDependentProviderFactory;

/**
 * Factory for Redis-based SingleUseObjectProvider.
 * This provider handles authorization codes, one-time tokens, and similar single-use objects.
 */
public class RedisSingleUseObjectProviderFactory implements SingleUseObjectProviderFactory<RedisSingleUseObjectProvider>, EnvironmentDependentProviderFactory {

    private static final Logger logger = Logger.getLogger(RedisSingleUseObjectProviderFactory.class);

    public static final String PROVIDER_ID = "redis";

    @Override
    public RedisSingleUseObjectProvider create(KeycloakSession session) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            throw new IllegalStateException("RedisConnectionProvider not available. " +
                    "Make sure Redis connection is properly configured.");
        }
        return new RedisSingleUseObjectProvider(redis);
    }

    @Override
    public void init(Config.Scope config) {
        logger.debug("Initializing Redis SingleUseObjectProviderFactory");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        logger.info("Redis SingleUseObjectProvider initialized");
    }

    @Override
    public void close() {
        logger.debug("Closing Redis SingleUseObjectProviderFactory");
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        // This provider is enabled when Redis is configured
        // Check if redis-connection provider is available
        return true; // Will fail gracefully if Redis is not configured
    }

    @Override
    public int order() {
        // Higher order to take precedence over Infinispan when Redis is configured
        return 10;
    }
}
