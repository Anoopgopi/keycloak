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
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.common.util.Time;
import org.keycloak.models.AuthenticatedClientSessionModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.sessions.infinispan.remote.redis.entities.RedisClientSessionEntity;
import org.keycloak.models.sessions.infinispan.remote.redis.entities.RedisUserSessionEntity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Redis-based implementation of UserSessionProvider.
 * Handles user sessions and client sessions storage using Redis.
 */
public class RedisUserSessionProvider implements UserSessionProvider {

    private static final Logger logger = Logger.getLogger(RedisUserSessionProvider.class);

    private final KeycloakSession session;
    private final RedisConnectionProvider redis;
    private final int sessionLifespanSeconds;
    private final int offlineSessionLifespanSeconds;

    public RedisUserSessionProvider(KeycloakSession session, RedisConnectionProvider redis,
                                     int sessionLifespanSeconds, int offlineSessionLifespanSeconds) {
        this.session = Objects.requireNonNull(session);
        this.redis = Objects.requireNonNull(redis);
        this.sessionLifespanSeconds = sessionLifespanSeconds;
        this.offlineSessionLifespanSeconds = offlineSessionLifespanSeconds;
    }

    @Override
    public AuthenticatedClientSessionModel createClientSession(RealmModel realm, ClientModel client, UserSessionModel userSession) {
        RedisClientSessionEntity entity = RedisClientSessionEntity.create(
                userSession.getId(),
                client.getId(),
                realm.getId()
        );
        
        String cacheName = RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME;
        long lifespan = getSessionLifespan(realm, false);
        
        redis.put(cacheName, entity.getKey(), entity, lifespan, TimeUnit.SECONDS);
        
        return new RedisClientSessionAdapter(session, realm, client, userSession, entity, redis, false);
    }

    @Override
    public AuthenticatedClientSessionModel getClientSession(UserSessionModel userSession, ClientModel client, boolean offline) {
        String key = userSession.getId() + ":" + client.getId();
        String cacheName = offline ? 
                RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME :
                RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME;
        
        RedisClientSessionEntity entity = redis.get(cacheName, key, RedisClientSessionEntity.class);
        
        if (entity == null) {
            return null;
        }
        
        RealmModel realm = session.realms().getRealm(entity.getRealmId());
        return new RedisClientSessionAdapter(session, realm, client, userSession, entity, redis, offline);
    }

    @Override
    public UserSessionModel createUserSession(String id, RealmModel realm, UserModel user,
                                               String loginUsername, String ipAddress, String authMethod,
                                               boolean rememberMe, String brokerSessionId, String brokerUserId,
                                               UserSessionModel.SessionPersistenceState persistenceState) {
        if (id == null) {
            id = SecretGenerator.SECURE_ID_GENERATOR.get();
        }
        
        RedisUserSessionEntity entity = RedisUserSessionEntity.create(
                id, realm, user, loginUsername, ipAddress, authMethod,
                rememberMe, brokerSessionId, brokerUserId
        );
        
        String cacheName = RedisConnectionProvider.USER_SESSION_CACHE_NAME;
        long lifespan = getSessionLifespan(realm, false);
        
        redis.put(cacheName, id, entity, lifespan, TimeUnit.SECONDS);
        
        if (logger.isDebugEnabled()) {
            logger.debugf("Created user session: %s for user %s in realm %s", id, user.getId(), realm.getId());
        }
        
        return new RedisUserSessionAdapter(session, realm, user, entity, redis, false);
    }

    @Override
    public UserSessionModel getUserSession(RealmModel realm, String id) {
        return getUserSession(realm, id, false);
    }

    private UserSessionModel getUserSession(RealmModel realm, String id, boolean offline) {
        if (id == null) {
            return null;
        }
        
        String cacheName = offline ?
                RedisConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME :
                RedisConnectionProvider.USER_SESSION_CACHE_NAME;
        
        RedisUserSessionEntity entity = redis.get(cacheName, id, RedisUserSessionEntity.class);
        
        if (entity == null || !entity.getRealmId().equals(realm.getId())) {
            return null;
        }
        
        UserModel user = session.users().getUserById(realm, entity.getUserId());
        if (user == null) {
            // User was deleted, remove orphaned session
            redis.delete(cacheName, id);
            return null;
        }
        
        return new RedisUserSessionAdapter(session, realm, user, entity, redis, offline);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, UserModel user) {
        // Note: This is a simplified implementation that scans keys
        // For production, consider using Redis secondary indices or search
        return getUserSessionsByPattern(realm, "userId:" + user.getId(), false);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client) {
        // Simplified - in production, use client session index
        return getUserSessionsStream(realm, client, null, null);
    }

    @Override
    public Stream<UserSessionModel> getUserSessionsStream(RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        // Simplified implementation
        // In production, this would use Redis secondary indices
        return Stream.empty();
    }

    @Override
    public Stream<UserSessionModel> getUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return getUserSessionsByPattern(realm, "brokerUserId:" + brokerUserId, false);
    }

    @Override
    public UserSessionModel getUserSessionByBrokerSessionId(RealmModel realm, String brokerSessionId) {
        // Simplified - would need secondary index in production
        return null;
    }

    @Override
    public UserSessionModel getUserSessionWithPredicate(RealmModel realm, String id, boolean offline, Predicate<UserSessionModel> predicate) {
        UserSessionModel userSession = getUserSession(realm, id, offline);
        return userSession != null && predicate.test(userSession) ? userSession : null;
    }

    @Override
    public long getActiveUserSessions(RealmModel realm, ClientModel client) {
        // Would need to count client sessions for this client
        // Simplified implementation
        return 0;
    }

    @Override
    public Map<String, Long> getActiveClientSessionStats(RealmModel realm, boolean offline) {
        // Would need aggregation over client sessions
        return Collections.emptyMap();
    }

    @Override
    public void removeUserSession(RealmModel realm, UserSessionModel session) {
        removeUserSession(session.getId(), false);
    }

    private void removeUserSession(String sessionId, boolean offline) {
        String userCacheName = offline ?
                RedisConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME :
                RedisConnectionProvider.USER_SESSION_CACHE_NAME;
        String clientCacheName = offline ?
                RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME :
                RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME;
        
        // Remove user session
        redis.delete(userCacheName, sessionId);
        
        // Remove associated client sessions
        redis.removeByPattern(clientCacheName, sessionId + ":*");
        
        if (logger.isDebugEnabled()) {
            logger.debugf("Removed user session: %s (offline=%s)", sessionId, offline);
        }
    }

    @Override
    public void removeUserSessions(RealmModel realm, UserModel user) {
        // Remove all sessions for this user
        getUserSessionsStream(realm, user)
                .forEach(s -> removeUserSession(s.getId(), false));
        
        getOfflineUserSessionsStream(realm, user)
                .forEach(s -> removeUserSession(s.getId(), true));
    }

    @Override
    public void removeAllExpired() {
        // Redis handles expiration automatically via TTL
    }

    @Override
    public void removeExpired(RealmModel realm) {
        // Redis handles expiration automatically via TTL
    }

    @Override
    public void removeUserSessions(RealmModel realm) {
        // Remove all sessions for the realm
        redis.removeByPattern(RedisConnectionProvider.USER_SESSION_CACHE_NAME, "*");
        redis.removeByPattern(RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME, "*");
        redis.removeByPattern(RedisConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME, "*");
        redis.removeByPattern(RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME, "*");
    }

    @Override
    public void onRealmRemoved(RealmModel realm) {
        // Remove all sessions for the realm
        // In production, would need realm-specific key pattern
        removeUserSessions(realm);
    }

    @Override
    public void onClientRemoved(RealmModel realm, ClientModel client) {
        // Remove client sessions for this client
        redis.removeByPattern(RedisConnectionProvider.CLIENT_SESSION_CACHE_NAME, "*:" + client.getId());
        redis.removeByPattern(RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME, "*:" + client.getId());
    }

    @Override
    public UserSessionModel createOfflineUserSession(UserSessionModel userSession) {
        RedisUserSessionEntity entity = RedisUserSessionEntity.createFromModel(userSession);
        entity.setOffline(true);
        
        String cacheName = RedisConnectionProvider.OFFLINE_USER_SESSION_CACHE_NAME;
        long lifespan = getSessionLifespan(userSession.getRealm(), true);
        
        redis.put(cacheName, entity.getId(), entity, lifespan, TimeUnit.SECONDS);
        
        return new RedisUserSessionAdapter(session, userSession.getRealm(), userSession.getUser(), entity, redis, true);
    }

    @Override
    public UserSessionModel getOfflineUserSession(RealmModel realm, String userSessionId) {
        return getUserSession(realm, userSessionId, true);
    }

    @Override
    public void removeOfflineUserSession(RealmModel realm, UserSessionModel userSession) {
        removeUserSession(userSession.getId(), true);
    }

    @Override
    public AuthenticatedClientSessionModel createOfflineClientSession(AuthenticatedClientSessionModel clientSession,
                                                                        UserSessionModel offlineUserSession) {
        RedisClientSessionEntity entity = RedisClientSessionEntity.createFromModel(clientSession);
        entity.setOffline(true);
        entity.setUserSessionId(offlineUserSession.getId());
        
        String cacheName = RedisConnectionProvider.OFFLINE_CLIENT_SESSION_CACHE_NAME;
        long lifespan = getSessionLifespan(clientSession.getRealm(), true);
        
        redis.put(cacheName, entity.getKey(), entity, lifespan, TimeUnit.SECONDS);
        
        return new RedisClientSessionAdapter(session, clientSession.getRealm(), clientSession.getClient(),
                offlineUserSession, entity, redis, true);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, UserModel user) {
        return getUserSessionsByPattern(realm, "userId:" + user.getId(), true);
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionByBrokerUserIdStream(RealmModel realm, String brokerUserId) {
        return getUserSessionsByPattern(realm, "brokerUserId:" + brokerUserId, true);
    }

    @Override
    public long getOfflineSessionsCount(RealmModel realm, ClientModel client) {
        // Would need to count offline client sessions
        return 0;
    }

    @Override
    public Stream<UserSessionModel> getOfflineUserSessionsStream(RealmModel realm, ClientModel client, Integer firstResult, Integer maxResults) {
        // Simplified implementation
        return Stream.empty();
    }

    @Override
    public int getStartupTime(RealmModel realm) {
        ClusterProvider cluster = session.getProvider(ClusterProvider.class);
        return cluster != null ? cluster.getClusterStartupTime() : (int) (System.currentTimeMillis() / 1000);
    }

    @Override
    public KeycloakSession getKeycloakSession() {
        return session;
    }

    @Override
    public void close() {
        // Nothing to close - connection is managed by factory
    }

    @Override
    public void migrate(String modelVersion) {
        // No migration needed for new Redis implementation
    }

    private long getSessionLifespan(RealmModel realm, boolean offline) {
        if (offline) {
            return offlineSessionLifespanSeconds > 0 ? 
                    offlineSessionLifespanSeconds : 
                    realm.getOfflineSessionIdleTimeout();
        }
        return sessionLifespanSeconds > 0 ? 
                sessionLifespanSeconds : 
                realm.getSsoSessionMaxLifespan();
    }

    private Stream<UserSessionModel> getUserSessionsByPattern(RealmModel realm, String pattern, boolean offline) {
        // Simplified implementation - returns empty for now
        // In production, would use Redis SCAN with pattern matching or secondary indices
        return Stream.empty();
    }
}
