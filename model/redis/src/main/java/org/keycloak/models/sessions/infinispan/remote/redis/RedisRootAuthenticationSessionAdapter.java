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

import org.keycloak.common.util.Time;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.sessions.infinispan.remote.redis.entities.RedisAuthenticationSessionEntity;
import org.keycloak.sessions.AuthenticationSessionModel;
import org.keycloak.sessions.RootAuthenticationSessionModel;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Adapter for RootAuthenticationSessionModel backed by Redis.
 */
public class RedisRootAuthenticationSessionAdapter implements RootAuthenticationSessionModel {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final RedisAuthenticationSessionEntity entity;
    private final RedisConnectionProvider redis;
    private final int lifespan;

    public RedisRootAuthenticationSessionAdapter(KeycloakSession session, RealmModel realm,
                                                 RedisAuthenticationSessionEntity entity,
                                                 RedisConnectionProvider redis, int lifespan) {
        this.session = session;
        this.realm = realm;
        this.entity = entity;
        this.redis = redis;
        this.lifespan = lifespan;
    }

    @Override
    public String getId() { return entity.getId(); }

    @Override
    public RealmModel getRealm() { return realm; }

    @Override
    public int getTimestamp() { return entity.getTimestamp(); }

    @Override
    public void setTimestamp(int timestamp) {
        entity.setTimestamp(timestamp);
        save();
    }

    @Override
    public Map<String, AuthenticationSessionModel> getAuthenticationSessions() {
        Map<String, AuthenticationSessionModel> result = new HashMap<>();
        for (Map.Entry<String, RedisAuthenticationSessionEntity.RedisAuthenticationTabEntity> entry : entity.getAuthenticationSessions().entrySet()) {
            ClientModel client = realm.getClientById(entry.getValue().getClientUUID());
            if (client != null) {
                result.put(entry.getKey(), new RedisAuthenticationSessionAdapter(session, this, client, entry.getValue()));
            }
        }
        return result;
    }

    @Override
    public AuthenticationSessionModel getAuthenticationSession(ClientModel client, String tabId) {
        if (client == null || tabId == null) return null;
        RedisAuthenticationSessionEntity.RedisAuthenticationTabEntity tab = entity.getAuthenticationSession(tabId);
        if (tab == null || !tab.getClientUUID().equals(client.getId())) return null;
        return new RedisAuthenticationSessionAdapter(session, this, client, tab);
    }

    @Override
    public AuthenticationSessionModel createAuthenticationSession(ClientModel client) {
        String tabId = org.keycloak.models.utils.KeycloakModelUtils.generateId();
        RedisAuthenticationSessionEntity.RedisAuthenticationTabEntity tab =
                new RedisAuthenticationSessionEntity.RedisAuthenticationTabEntity(tabId, client.getId());
        entity.setAuthenticationSession(tabId, tab);
        save();
        return new RedisAuthenticationSessionAdapter(session, this, client, tab);
    }

    @Override
    public void removeAuthenticationSessionByTabId(String tabId) {
        entity.removeAuthenticationSession(tabId);
        save();
    }

    @Override
    public void restartSession(RealmModel realm) {
        entity.getAuthenticationSessions().clear();
        entity.setTimestamp(Time.currentTime());
        save();
    }

    void save() {
        redis.put(RedisConnectionProvider.CACHE_AUTHENTICATION_SESSIONS, entity.getId(), entity, lifespan, TimeUnit.SECONDS);
    }
}
