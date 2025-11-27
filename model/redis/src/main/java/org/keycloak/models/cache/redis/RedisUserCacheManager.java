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
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.connections.redis.crossdc.CrossDCInvalidationEvent;
import org.keycloak.connections.redis.crossdc.RedisCrossDCProvider;
import org.keycloak.models.cache.infinispan.UserCacheManager;
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserCacheInvalidationEvent;

import java.util.Map;
import java.util.Set;

/**
 * Redis-based UserCacheManager, mirroring InfinispanUserCacheManager.
 * <p>
 * Handles user-specific cache operations and invalidation logic.
 * Supports optional cross-datacenter replication for active-active deployments.
 *
 * @author Keycloak Team
 */
public class RedisUserCacheManager extends RedisCacheManager {

    private static final Logger logger = Logger.getLogger(RedisUserCacheManager.class);

    private volatile RedisCrossDCProvider crossDCProvider;

    public RedisUserCacheManager(RedisConnectionProvider redisProvider) {
        super(redisProvider,
              RedisConnectionProvider.USER_CACHE_NAME,
              RedisConnectionProvider.USER_REVISIONS_CACHE_NAME);
    }

    /**
     * Set the cross-DC provider for multi-region replication.
     * 
     * @param crossDCProvider the cross-DC provider, or null to disable
     */
    public void setCrossDCProvider(RedisCrossDCProvider crossDCProvider) {
        this.crossDCProvider = crossDCProvider;
        if (crossDCProvider != null) {
            // Register handler for incoming cross-DC events
            crossDCProvider.setEventHandler(this::handleCrossDCEvent);
            logger.info("Cross-DC replication enabled for user cache");
        }
    }

    /**
     * Handle incoming cross-DC invalidation events.
     */
    private void handleCrossDCEvent(CrossDCInvalidationEvent event) {
        if (!"users".equals(event.getCacheName())) {
            return;
        }

        switch (event.getEventType()) {
            case CLEAR_CACHE:
                logger.debugf("Processing cross-DC cache clear from site '%s'", event.getSourceSite());
                clearLocal();
                break;

            case INVALIDATE_REALM:
                logger.debugf("Processing cross-DC realm invalidation from site '%s': %s",
                        event.getSourceSite(), event.getRealmId());
                invalidateRealmUsersLocal(event.getRealmId());
                break;

            case INVALIDATE_KEYS:
            case USER_UPDATED:
                logger.debugf("Processing cross-DC key invalidation from site '%s': %d keys",
                        event.getSourceSite(), event.getKeys().size());
                for (String key : event.getKeys()) {
                    invalidateObjectLocal(key);
                }
                break;

            default:
                logger.debugf("Unknown cross-DC event type: %s", event.getEventType());
        }
    }

    /**
     * Clear cache locally without triggering cross-DC replication.
     */
    private void clearLocal() {
        super.clear();
    }

    /**
     * Invalidate realm users locally without triggering cross-DC replication.
     */
    private void invalidateRealmUsersLocal(String realmId) {
        java.util.HashSet<String> invalidations = new java.util.HashSet<>();
        addInvalidationsByPattern(realmId + ".*", invalidations);
        for (String key : invalidations) {
            super.invalidateObject(key);
        }
    }

    /**
     * Invalidate object locally without triggering cross-DC replication.
     */
    private void invalidateObjectLocal(String key) {
        super.invalidateObject(key);
    }

    @Override
    public void clear() {
        super.clear();
        
        // Replicate to other sites
        if (crossDCProvider != null && crossDCProvider.isActive()) {
            crossDCProvider.sendCacheClear("users");
        }
    }

    @Override
    protected Logger getLogger() {
        return logger;
    }

    /**
     * Add invalidations for a user update.
     *
     * @param userId        the user ID
     * @param username      the username
     * @param email         the user's email (may be null)
     * @param realmId       the realm ID
     * @param invalidations set to add invalidation keys to
     */
    public void userUpdatedInvalidations(String userId, String username, String email,
                                         String realmId, Set<String> invalidations) {
        invalidations.add(userId);
        if (email != null) {
            invalidations.add(RedisUserCacheSession.getUserByEmailCacheKey(realmId, email));
        }
        invalidations.add(RedisUserCacheSession.getUserByUsernameCacheKey(realmId, username));
    }

    /**
     * Fully invalidate a user including consents and federated identity links.
     *
     * @param userId                    the user ID
     * @param username                  the username
     * @param email                     the user's email (may be null)
     * @param realmId                   the realm ID
     * @param identityFederationEnabled whether identity federation is enabled
     * @param federatedIdentities       map of provider ID to social user ID
     * @param invalidations             set to add invalidation keys to
     */
    public void fullUserInvalidation(String userId, String username, String email,
                                     String realmId, boolean identityFederationEnabled,
                                     Map<String, String> federatedIdentities,
                                     Set<String> invalidations) {
        userUpdatedInvalidations(userId, username, email, realmId, invalidations);

        if (identityFederationEnabled) {
            // Invalidate all keys for lookup this user by any identityProvider link
            for (Map.Entry<String, String> socialLink : federatedIdentities.entrySet()) {
                String fedIdentityCacheKey = RedisUserCacheSession
                    .getUserByFederatedIdentityCacheKey(realmId, socialLink.getKey(), socialLink.getValue());
                invalidations.add(fedIdentityCacheKey);
            }

            // Invalidate federationLinks of user
            invalidations.add(RedisUserCacheSession.getFederatedIdentityLinksCacheKey(userId));
        }

        // Consents
        invalidations.add(RedisUserCacheSession.getConsentCacheKey(userId));
    }

    /**
     * Add invalidation for federated identity link update.
     *
     * @param userId        the user ID
     * @param invalidations set to add invalidation keys to
     */
    public void federatedIdentityLinkUpdatedInvalidation(String userId, Set<String> invalidations) {
        invalidations.add(RedisUserCacheSession.getFederatedIdentityLinksCacheKey(userId));
    }

    /**
     * Add invalidations for federated identity link removal.
     *
     * @param userId             the user ID
     * @param realmId            the realm ID
     * @param identityProviderId the identity provider ID
     * @param socialUserId       the social user ID
     * @param invalidations      set to add invalidation keys to
     */
    public void federatedIdentityLinkRemovedInvalidation(String userId, String realmId,
                                                         String identityProviderId, String socialUserId,
                                                         Set<String> invalidations) {
        invalidations.add(RedisUserCacheSession.getFederatedIdentityLinksCacheKey(userId));
        if (identityProviderId != null) {
            invalidations.add(RedisUserCacheSession
                .getUserByFederatedIdentityCacheKey(realmId, identityProviderId, socialUserId));
        }
    }

    /**
     * Add invalidation for user consent change.
     *
     * @param userId        the user ID
     * @param invalidations set to add invalidation keys to
     */
    public void consentInvalidation(String userId, Set<String> invalidations) {
        invalidations.add(RedisUserCacheSession.getConsentCacheKey(userId));
    }

    @Override
    protected void addInvalidationsFromEvent(InvalidationEvent event, Set<String> invalidations) {
        if (event instanceof UserCacheInvalidationEvent) {
            // Create an adapter that bridges the Infinispan UserCacheManager interface
            // to our Redis implementation
            UserCacheManagerAdapter adapter = new UserCacheManagerAdapter(this);
            ((UserCacheInvalidationEvent) event).addInvalidations(adapter, invalidations);
        } else {
            // Unknown event type, just add the event ID
            invalidations.add(event.getId());
        }
    }

    /**
     * Invalidate all users in a realm.
     * Uses pattern matching to find all user keys for the realm.
     *
     * @param realmId       the realm ID
     * @param invalidations set to add invalidation keys to
     */
    public void invalidateRealmUsers(String realmId, Set<String> invalidations) {
        // Pattern: realmId.* matches username, email, and idp lookups
        addInvalidationsByPattern(realmId + ".*", invalidations);

        // Replicate to other sites
        if (crossDCProvider != null && crossDCProvider.isActive()) {
            crossDCProvider.sendRealmInvalidation("users", realmId);
        }
    }

    /**
     * Send user invalidations to other datacenters.
     * Called after local invalidation processing.
     *
     * @param realmId       the realm ID
     * @param invalidations the set of invalidation keys
     */
    public void sendCrossDCInvalidations(String realmId, Set<String> invalidations) {
        if (crossDCProvider != null && crossDCProvider.isActive() && !invalidations.isEmpty()) {
            crossDCProvider.sendUserInvalidation(realmId, invalidations);
        }
    }

    /**
     * Check if cross-DC replication is active.
     */
    public boolean isCrossDCActive() {
        return crossDCProvider != null && crossDCProvider.isActive();
    }

    /**
     * Get the cross-DC provider for advanced operations.
     */
    public RedisCrossDCProvider getCrossDCProvider() {
        return crossDCProvider;
    }
}
