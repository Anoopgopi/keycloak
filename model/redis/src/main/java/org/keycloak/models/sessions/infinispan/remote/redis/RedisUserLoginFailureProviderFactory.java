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
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserLoginFailureProviderFactory;

/**
 * Factory for RedisUserLoginFailureProvider.
 */
public class RedisUserLoginFailureProviderFactory implements UserLoginFailureProviderFactory<UserLoginFailureProvider> {

    private static final Logger logger = Logger.getLogger(RedisUserLoginFailureProviderFactory.class);

    public static final String PROVIDER_ID = "redis";
    private static final long DEFAULT_FAILURE_LIFESPAN = 900; // 15 minutes

    private long failureLifespan = DEFAULT_FAILURE_LIFESPAN;

    @Override
    public UserLoginFailureProvider create(KeycloakSession session) {
        RedisConnectionProvider redis = session.getProvider(RedisConnectionProvider.class);
        if (redis == null) {
            throw new IllegalStateException("RedisConnectionProvider not available");
        }
        return new RedisUserLoginFailureProvider(session, redis, failureLifespan);
    }

    @Override
    public void init(Config.Scope config) {
        failureLifespan = config.getLong("failureLifespan", DEFAULT_FAILURE_LIFESPAN);
        logger.infof("Redis UserLoginFailureProviderFactory initialized with lifespan: %d seconds", failureLifespan);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing to do
    }

    @Override
    public void close() {
        // Nothing to close
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public int order() {
        return 10; // Higher priority than Infinispan
    }
}
