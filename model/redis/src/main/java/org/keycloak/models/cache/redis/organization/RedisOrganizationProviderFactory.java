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
package org.keycloak.models.cache.redis.organization;

import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.cache.redis.RedisUserCacheSession;
import org.keycloak.organization.OrganizationProvider;
import org.keycloak.organization.OrganizationProviderFactory;

/**
 * Factory for Redis-compatible organization provider.
 * This factory has higher order than Infinispan to be selected when Redis user cache is active.
 */
public class RedisOrganizationProviderFactory implements OrganizationProviderFactory {

    public static final String PROVIDER_ID = "redis";

    @Override
    public OrganizationProvider create(KeycloakSession session) {
        // Only use this provider if Redis user cache is being used
        UserCache userCache = session.getProvider(UserCache.class);
        if (userCache instanceof RedisUserCacheSession) {
            return new RedisOrganizationProvider(session);
        }
        // Fall back to null so the default (Infinispan) provider is used
        return null;
    }

    @Override
    public void init(Scope config) {
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

    @Override
    public int order() {
        // Higher than Infinispan (10) to be selected first
        return 20;
    }
}
