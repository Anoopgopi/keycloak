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
import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.infinispan.remote.redis.entities.RedisAuthenticationSessionEntity;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Redis-based implementation of AuthenticationSessionProvider.
 */
public class RedisAuthenticationSessionProvider implements AuthenticationSessionProvider {

    private static final Logger logger = Logger.getLogger(RedisAuthenticationSessionProvider.class);

    private final KeycloakSession session;
    private final RedisConnectionProvider redis;
    private final int authSessionLifespan;

    public RedisAuthenticationSessionProvider(KeycloakSession session, RedisConnectionProvider redis, int authSessionLifespan) {
        this.session = session;
        this.redis = redis;
        this.authSessionLifespan = authSessionLifespan;
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm) {
        return createRootAuthenticationSession(realm, null);
    }

    @Override
    public RootAuthenticationSessionModel createRootAuthenticationSession(RealmModel realm, String id) {
        String sessionId = id != null ? id : org.keycloak.models.utils.KeycloakModelUtils.generateId();
        int timestamp = Time.currentTime();

        RedisAuthenticationSessionEntity entity = new RedisAuthenticationSessionEntity(sessionId, realm.getId(), timestamp);

        redis.put(RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS, sessionId, entity, authSessionLifespan, TimeUnit.SECONDS);

        logger.debugf("Created root authentication session: %s for realm: %s", sessionId, realm.getId());
        return new RedisRootAuthenticationSessionAdapter(session, realm, entity, redis, authSessionLifespan);
    }

    @Override
    public RootAuthenticationSessionModel getRootAuthenticationSession(RealmModel realm, String authenticationSessionId) {
        if (authenticationSessionId == null) return null;

        RedisAuthenticationSessionEntity entity = redis.get(
                RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS,
                authenticationSessionId,
                RedisAuthenticationSessionEntity.class
        );

        if (entity == null || !entity.getRealmId().equals(realm.getId())) {
            return null;
        }

        return new RedisRootAuthenticationSessionAdapter(session, realm, entity, redis, authSessionLifespan);
    }

    @Override
    public void removeRootAuthenticationSession(RealmModel realm, RootAuthenticationSessionModel authenticationSession) {
        if (authenticationSession == null) return;

        redis.delete(RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS, authenticationSession.getId());
        logger.debugf("Removed root authentication session: %s", authenticationSession.getId());
    }

    @Override
    public void removeAllExpired() {
        logger.debug("removeAllExpired called - Redis handles this via TTL");
    }

    @Override
    public void removeExpired(RealmModel realm) {
        logger.debugf("removeExpired called for realm %s - Redis handles this via TTL", realm.getId());
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        logger.debugf("onRealmRemoved called for realm %s", realm.getId());
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        logger.debugf("onClientRemoved called for client %s", client.getClientId());
    }

    @Override
    public void updateNonlocalSessionAuthNotes(AuthenticationSessionCompoundId compoundId, Map<String, String> authNotesFragment) {
        if (compoundId == null) return;

        String rootSessionId = compoundId.getRootSessionId();
        String tabId = compoundId.getTabId();
        String clientUUID = compoundId.getClientUUID();

        RedisAuthenticationSessionEntity entity = redis.get(
                RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS,
                rootSessionId,
                RedisAuthenticationSessionEntity.class
        );

        if (entity != null) {
            RedisAuthenticationSessionEntity.RedisAuthenticationTabEntity tab = entity.getAuthenticationSession(tabId);
            if (tab != null && tab.getClientUUID().equals(clientUUID)) {
                tab.getAuthNotes().putAll(authNotesFragment);
                redis.put(RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS, rootSessionId, entity, authSessionLifespan, TimeUnit.SECONDS);
            }
        }
    }

    @Override
    public void close() {
    }
}
