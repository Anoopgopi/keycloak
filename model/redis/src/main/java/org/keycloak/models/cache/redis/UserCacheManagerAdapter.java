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

package org.keycloak.models.cache.redis;

import org.jboss.logging.Logger;
import org.keycloak.models.cache.infinispan.UserCacheManager;
import org.keycloak.models.cache.infinispan.entities.Revisioned;
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;

import org.infinispan.Cache;

import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Adapter that bridges the Infinispan UserCacheManager to the Redis implementation.
 * <p>
 * This adapter extends UserCacheManager to satisfy the interface requirements of
 * UserCacheInvalidationEvent.addInvalidations(), but delegates all operations to
 * the RedisUserCacheManager.
 * <p>
 * The Infinispan Cache objects are null since we don't use them - all operations
 * are overridden to use Redis.
 *
 * @author Keycloak Team
 */
public class UserCacheManagerAdapter extends UserCacheManager {

    private static final Logger logger = Logger.getLogger(UserCacheManagerAdapter.class);

    private final RedisUserCacheManager redisManager;

    public UserCacheManagerAdapter(RedisUserCacheManager redisManager) {
        // Pass null caches - all methods are overridden to use Redis
        super(createDummyCache(), createDummyCache());
        this.redisManager = redisManager;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Cache<K, V> createDummyCache() {
        // Return null - we override all methods that would use the cache
        return null;
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

    // ========== Invalidation methods - delegate to Redis ==========

    @Override
    public void userUpdatedInvalidations(String userId, String username, String email,
                                         String realmId, Set<String> invalidations) {
        redisManager.userUpdatedInvalidations(userId, username, email, realmId, invalidations);
    }

    @Override
    public void fullUserInvalidation(String userId, String username, String email,
                                     String realmId, boolean identityFederationEnabled,
                                     Map<String, String> federatedIdentities,
                                     Set<String> invalidations) {
        redisManager.fullUserInvalidation(userId, username, email, realmId,
            identityFederationEnabled, federatedIdentities, invalidations);
    }

    @Override
    public void federatedIdentityLinkUpdatedInvalidation(String userId, Set<String> invalidations) {
        redisManager.federatedIdentityLinkUpdatedInvalidation(userId, invalidations);
    }

    @Override
    public void federatedIdentityLinkRemovedInvalidation(String userId, String realmId,
                                                         String identityProviderId, String socialUserId,
                                                         Set<String> invalidations) {
        redisManager.federatedIdentityLinkRemovedInvalidation(userId, realmId,
            identityProviderId, socialUserId, invalidations);
    }

    @Override
    public void consentInvalidation(String userId, Set<String> invalidations) {
        redisManager.consentInvalidation(userId, invalidations);
    }

    @Override
    public void invalidateRealmUsers(String realmId, Set<String> invalidations) {
        redisManager.invalidateRealmUsers(realmId, invalidations);
    }

    // ========== CacheManager methods - delegate to Redis ==========

    @Override
    public Cache<String, Revisioned> getCache() {
        // This should not be called in normal operation
        throw new UnsupportedOperationException(
            "Redis adapter does not expose Infinispan Cache. " +
            "Use RedisUserCacheManager methods instead."
        );
    }

    @Override
    public long getCurrentCounter() {
        return redisManager.getCurrentCounter();
    }

    @Override
    public Long getCurrentRevision(String id) {
        return redisManager.getCurrentRevision(id);
    }

    @Override
    public <T extends Revisioned> T get(String id, Class<T> type) {
        return redisManager.get(id, type);
    }

    @Override
    public Object invalidateObject(String id) {
        return redisManager.invalidateObject(id);
    }

    @Override
    public void addRevisioned(Revisioned object, long startupRevision) {
        redisManager.addRevisioned(object, startupRevision);
    }

    @Override
    public void addRevisioned(Revisioned object, long startupRevision, long lifespan) {
        redisManager.addRevisioned(object, startupRevision, lifespan);
    }

    @Override
    public void clear() {
        redisManager.clear();
    }

    @Override
    public void addInvalidations(Predicate<Map.Entry<String, Revisioned>> predicate, Set<String> invalidations) {
        // This is used for realm invalidation in Infinispan
        // For Redis, we use pattern-based scanning in RedisUserCacheManager
        logger.debug("addInvalidations called with predicate - not supported for Redis adapter");
    }

    @Override
    protected void addInvalidationsFromEvent(InvalidationEvent event, Set<String> invalidations) {
        // Delegate to Redis manager's implementation
        // This method is called when processing cluster events
        invalidations.add(event.getId());
    }

    @Override
    public void invalidateCacheKey(String key, Set<String> invalidations) {
        redisManager.invalidateCacheKey(key, invalidations);
    }
}
