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
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.common.constants.ServiceAccountConstants;
import org.keycloak.component.ComponentModel;
import org.keycloak.credential.CredentialInput;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientScopeModel;
import org.keycloak.models.CredentialValidationOutput;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialManager;
import org.keycloak.models.UserConsentModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserProvider;
import org.keycloak.models.cache.CachedUserModel;
import org.keycloak.models.cache.OnUserCache;
import org.keycloak.models.cache.UserCache;
import org.keycloak.models.cache.infinispan.ClearCacheEvent;
import org.keycloak.models.cache.infinispan.entities.CachedUser;
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;
import org.keycloak.models.cache.infinispan.events.UserUpdatedEvent;
import org.keycloak.storage.DatastoreProvider;
import org.keycloak.storage.OnCreateComponent;
import org.keycloak.storage.OnUpdateComponent;
import org.keycloak.storage.StorageId;
import org.keycloak.storage.StoreManagers;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.userprofile.AttributeMetadata;
import org.keycloak.userprofile.UserProfileDecorator;
import org.keycloak.userprofile.UserProfileMetadata;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Redis-based UserCache session implementation.
 * <p>
 * This is a simplified implementation that delegates most operations to the
 * underlying UserProvider while providing Redis-based caching for user lookups.
 * <p>
 * Note: This implementation does not use the Infinispan UserAdapter due to tight
 * coupling. Instead, it provides direct caching of user entities and delegates
 * to the underlying storage for most operations.
 *
 * @author Keycloak Team
 */
public class RedisUserCacheSession implements UserCache, OnCreateComponent, OnUpdateComponent, UserProfileDecorator {

    protected static final Logger logger = Logger.getLogger(RedisUserCacheSession.class);

    protected final RedisUserCacheManager cache;
    protected final KeycloakSession session;
    protected UserProvider delegate;
    protected boolean transactionActive;
    protected boolean setRollbackOnly;
    protected final long startupRevision;

    protected Set<String> invalidations = new HashSet<>();
    protected Set<String> realmInvalidations = new HashSet<>();
    protected Set<InvalidationEvent> invalidationEvents = new HashSet<>();
    protected Map<String, UserModel> managedUsers = new HashMap<>();
    private StoreManagers datastoreProvider;

    public RedisUserCacheSession(RedisUserCacheManager cache, KeycloakSession session) {
        this.cache = cache;
        this.session = session;
        this.startupRevision = cache.getCurrentCounter();
        this.datastoreProvider = (StoreManagers) session.getProvider(DatastoreProvider.class);
        session.getTransactionManager().enlistAfterCompletion(getTransaction());
    }

    @Override
    public void clear() {
        cache.clear();
        ClusterProvider cluster = session.getProvider(ClusterProvider.class);
        cluster.notify(RedisUserCacheProviderFactory.USER_CLEAR_CACHE_EVENTS, ClearCacheEvent.getInstance(), true);
    }

    public UserProvider getDelegate() {
        if (!transactionActive) throw new IllegalStateException("Cannot access delegate without a transaction");
        if (delegate != null) return delegate;
        delegate = this.datastoreProvider.userStorageManager();
        return delegate;
    }

    @Override
    public void evict(RealmModel realm, UserModel user) {
        if (!transactionActive) throw new IllegalStateException("Cannot call evict() without a transaction");
        getDelegate();
        cache.userUpdatedInvalidations(user.getId(), user.getUsername(), user.getEmail(), realm.getId(), invalidations);
        invalidationEvents.add(UserUpdatedEvent.create(user.getId(), user.getUsername(), user.getEmail(), realm.getId()));
    }

    @Override
    public void evict(RealmModel realm) {
        realmInvalidations.add(realm.getId());
    }

    protected void runInvalidations() {
        for (String realmId : realmInvalidations) {
            cache.invalidateRealmUsers(realmId, invalidations);
        }
        for (String invalidation : invalidations) {
            cache.invalidateObject(invalidation);
        }
        cache.sendInvalidationEvents(session, invalidationEvents, RedisUserCacheProviderFactory.USER_INVALIDATION_EVENTS);
    }

    private KeycloakTransaction getTransaction() {
        return new KeycloakTransaction() {
            @Override
            public void begin() {
                transactionActive = true;
            }

            @Override
            public void commit() {
                runInvalidations();
                transactionActive = false;
            }

            @Override
            public void rollback() {
                setRollbackOnly = true;
                runInvalidations();
                transactionActive = false;
            }

            @Override
            public void setRollbackOnly() {
                setRollbackOnly = true;
            }

            @Override
            public boolean getRollbackOnly() {
                return setRollbackOnly;
            }

            @Override
            public boolean isActive() {
                return transactionActive;
            }
        };
    }

    private boolean isRegisteredForInvalidation(RealmModel realm, String userId) {
        return realmInvalidations.contains(realm.getId()) || invalidations.contains(userId);
    }

    // ========== Cache key generation methods ==========

    static String getUserByUsernameCacheKey(String realmId, String username) {
        return realmId + ".username." + username;
    }

    static String getUserByEmailCacheKey(String realmId, String email) {
        return realmId + ".email." + email;
    }

    static String getUserByFederatedIdentityCacheKey(String realmId, String identityProvider, String socialUserId) {
        return realmId + ".idp." + identityProvider + "." + socialUserId;
    }

    static String getFederatedIdentityLinksCacheKey(String userId) {
        return userId + ".idplinks";
    }

    static String getConsentCacheKey(String userId) {
        return userId + ".consents";
    }

    // ========== UserProvider implementation - delegating with caching ==========

    @Override
    public UserModel getUserById(RealmModel realm, String id) {
        logger.tracev("getUserById {0}", id);
        if (isRegisteredForInvalidation(realm, id)) {
            return getDelegate().getUserById(realm, id);
        }
        if (managedUsers.containsKey(id)) {
            return managedUsers.get(id);
        }

        // Get from delegate (caching disabled for now - complex serialization issues)
        // TODO: Implement proper serialization for CachedUser or use a simpler cache format
        UserModel user = getDelegate().getUserById(realm, id);
        if (user != null) {
            managedUsers.put(id, user);
        }
        return user;
    }

    @Override
    public UserModel getUserByUsername(RealmModel realm, String username) {
        if (username == null) return null;
        String normalizedUsername = username.toLowerCase();
        logger.tracev("getUserByUsername: {0}", normalizedUsername);

        // Get from delegate (caching simplified for now)
        UserModel user = getDelegate().getUserByUsername(realm, normalizedUsername);
        if (user != null) {
            managedUsers.put(user.getId(), user);
        }
        return user;
    }

    @Override
    public UserModel getUserByEmail(RealmModel realm, String email) {
        if (email == null) return null;
        String normalizedEmail = email.toLowerCase();

        // Get from delegate (caching simplified for now)
        UserModel user = getDelegate().getUserByEmail(realm, normalizedEmail);
        if (user != null) {
            managedUsers.put(user.getId(), user);
        }
        return user;
    }

    private void cacheUser(RealmModel realm, UserModel user) {
        try {
            int notBefore = getDelegate().getNotBeforeOfUser(realm, user);
            Long revision = cache.getCurrentRevision(user.getId());
            CachedUser cachedUser = new CachedUser(revision, realm, user, notBefore);
            long lifespan = getLifespan(realm, user);
            cache.addRevisioned(cachedUser, startupRevision, lifespan);
        } catch (Exception e) {
            logger.warnf("Failed to cache user %s: %s", user.getId(), e.getMessage());
        }
    }

    private long getLifespan(RealmModel realm, UserModel user) {
        if (!user.isFederated()) {
            return -1; // cache infinite
        }

        String providerId = user.getFederationLink();
        if (providerId == null) {
            providerId = StorageId.providerId(user.getId());
        }

        ComponentModel component = realm.getComponent(providerId);
        if (component == null) {
            return -1;
        }

        UserStorageProviderModel model = new UserStorageProviderModel(component);
        if (model.isEnabled()) {
            UserStorageProviderModel.CachePolicy policy = model.getCachePolicy();
            if (policy == null) {
                return -1;
            }
            if (!UserStorageProviderModel.CachePolicy.NO_CACHE.equals(policy)) {
                long lifespan = model.getLifespan();
                return lifespan > 0 ? lifespan : -1;
            }
        }
        return 0;
    }

    @Override
    public void close() {
        if (delegate != null) delegate.close();
    }

    // ========== Delegate methods ==========

    @Override
    public UserModel getUserByFederatedIdentity(RealmModel realm, FederatedIdentityModel socialLink) {
        return getDelegate().getUserByFederatedIdentity(realm, socialLink);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, Integer firstResult, Integer maxResults) {
        return getDelegate().getGroupMembersStream(realm, group, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group, String search, Boolean exact, Integer firstResult, Integer maxResults) {
        return getDelegate().getGroupMembersStream(realm, group, search, exact, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> getGroupMembersStream(RealmModel realm, GroupModel group) {
        return getDelegate().getGroupMembersStream(realm, group);
    }

    @Override
    public Stream<UserModel> getRoleMembersStream(RealmModel realm, RoleModel role, Integer firstResult, Integer maxResults) {
        return getDelegate().getRoleMembersStream(realm, role, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> getRoleMembersStream(RealmModel realm, RoleModel role) {
        return getDelegate().getRoleMembersStream(realm, role);
    }

    @Override
    public UserModel getServiceAccount(ClientModel client) {
        return getDelegate().getServiceAccount(client);
    }

    @Override
    public CredentialValidationOutput getUserByCredential(RealmModel realm, CredentialInput input) {
        return getDelegate().getUserByCredential(realm, input);
    }

    @Override
    public int getUsersCount(RealmModel realm, boolean includeServiceAccount) {
        return getDelegate().getUsersCount(realm, includeServiceAccount);
    }

    @Override
    public int getUsersCount(RealmModel realm, Set<String> groupIds) {
        return getDelegate().getUsersCount(realm, groupIds);
    }

    @Override
    public int getUsersCount(RealmModel realm, String search) {
        return getDelegate().getUsersCount(realm, search);
    }

    @Override
    public int getUsersCount(RealmModel realm, String search, Set<String> groupIds) {
        return getDelegate().getUsersCount(realm, search, groupIds);
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params) {
        return getDelegate().getUsersCount(realm, params);
    }

    @Override
    public int getUsersCount(RealmModel realm, Map<String, String> params, Set<String> groupIds) {
        return getDelegate().getUsersCount(realm, params, groupIds);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, String search) {
        return getDelegate().searchForUserStream(realm, search);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, String search, Integer firstResult, Integer maxResults) {
        return getDelegate().searchForUserStream(realm, search, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> attributes) {
        return getDelegate().searchForUserStream(realm, attributes);
    }

    @Override
    public Stream<UserModel> searchForUserStream(RealmModel realm, Map<String, String> attributes, Integer firstResult, Integer maxResults) {
        return getDelegate().searchForUserStream(realm, attributes, firstResult, maxResults);
    }

    @Override
    public Stream<UserModel> searchForUserByUserAttributeStream(RealmModel realm, String attrName, String attrValue) {
        return getDelegate().searchForUserByUserAttributeStream(realm, attrName, attrValue);
    }

    @Override
    public Stream<FederatedIdentityModel> getFederatedIdentitiesStream(RealmModel realm, UserModel user) {
        return getDelegate().getFederatedIdentitiesStream(realm, user);
    }

    @Override
    public FederatedIdentityModel getFederatedIdentity(RealmModel realm, UserModel user, String socialProvider) {
        return getDelegate().getFederatedIdentity(realm, user, socialProvider);
    }

    @Override
    public void updateConsent(RealmModel realm, String userId, UserConsentModel consent) {
        invalidations.add(getConsentCacheKey(userId));
        getDelegate().updateConsent(realm, userId, consent);
    }

    @Override
    public boolean revokeConsentForClient(RealmModel realm, String userId, String clientInternalId) {
        invalidations.add(getConsentCacheKey(userId));
        return getDelegate().revokeConsentForClient(realm, userId, clientInternalId);
    }

    @Override
    public void addConsent(RealmModel realm, String userId, UserConsentModel consent) {
        invalidations.add(getConsentCacheKey(userId));
        getDelegate().addConsent(realm, userId, consent);
    }

    @Override
    public UserConsentModel getConsentByClient(RealmModel realm, String userId, String clientId) {
        return getDelegate().getConsentByClient(realm, userId, clientId);
    }

    @Override
    public Stream<UserConsentModel> getConsentsStream(RealmModel realm, String userId) {
        return getDelegate().getConsentsStream(realm, userId);
    }

    // ========== Component lifecycle callbacks ==========

    @Override
    public void onCreate(KeycloakSession session, RealmModel realm, ComponentModel model) {
    }

    @Override
    public void onUpdate(KeycloakSession session, RealmModel realm, ComponentModel oldModel, ComponentModel newModel) {
    }

    // ========== UserProfileDecorator implementation ==========

    @Override
    public List<AttributeMetadata> decorateUserProfile(String providerId, UserProfileMetadata metadata) {
        // No decoration needed for Redis cache
        return Collections.emptyList();
    }

    @Override
    public UserCredentialManager getUserCredentialManager(UserModel user) {
        return getDelegate().getUserCredentialManager(user);
    }

    // ========== UserProvider mutation methods ==========

    @Override
    public UserModel addUser(RealmModel realm, String id, String username, boolean addDefaultRoles, boolean addDefaultRequiredActions) {
        return getDelegate().addUser(realm, id, username, addDefaultRoles, addDefaultRequiredActions);
    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        return getDelegate().addUser(realm, username);
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        evict(realm, user);
        return getDelegate().removeUser(realm, user);
    }

    @Override
    public void addFederatedIdentity(RealmModel realm, UserModel user, FederatedIdentityModel socialLink) {
        invalidations.add(getFederatedIdentityLinksCacheKey(user.getId()));
        getDelegate().addFederatedIdentity(realm, user, socialLink);
    }

    @Override
    public boolean removeFederatedIdentity(RealmModel realm, UserModel user, String socialProvider) {
        invalidations.add(getFederatedIdentityLinksCacheKey(user.getId()));
        return getDelegate().removeFederatedIdentity(realm, user, socialProvider);
    }

    @Override
    public void updateFederatedIdentity(RealmModel realm, UserModel federatedUser, FederatedIdentityModel federatedIdentityModel) {
        invalidations.add(getFederatedIdentityLinksCacheKey(federatedUser.getId()));
        getDelegate().updateFederatedIdentity(realm, federatedUser, federatedIdentityModel);
    }

    @Override
    public void setNotBeforeForUser(RealmModel realm, UserModel user, int notBefore) {
        evict(realm, user);
        getDelegate().setNotBeforeForUser(realm, user, notBefore);
    }

    @Override
    public int getNotBeforeOfUser(RealmModel realm, UserModel user) {
        return getDelegate().getNotBeforeOfUser(realm, user);
    }

    @Override
    public void grantToAllUsers(RealmModel realm, RoleModel role) {
        realmInvalidations.add(realm.getId());
        getDelegate().grantToAllUsers(realm, role);
    }

    @Override
    public void preRemove(RealmModel realm) {
        realmInvalidations.add(realm.getId());
        getDelegate().preRemove(realm);
    }

    @Override
    public void preRemove(RealmModel realm, RoleModel role) {
        getDelegate().preRemove(realm, role);
    }

    @Override
    public void preRemove(RealmModel realm, GroupModel group) {
        getDelegate().preRemove(realm, group);
    }

    @Override
    public void preRemove(RealmModel realm, ClientModel client) {
        getDelegate().preRemove(realm, client);
    }

    @Override
    public void preRemove(RealmModel realm, IdentityProviderModel provider) {
        getDelegate().preRemove(realm, provider);
    }

    @Override
    public void preRemove(RealmModel realm, ComponentModel component) {
        getDelegate().preRemove(realm, component);
    }

    @Override
    public void preRemove(ClientScopeModel clientScope) {
        getDelegate().preRemove(clientScope);
    }

    @Override
    public void preRemove(ProtocolMapperModel protocolMapper) {
        getDelegate().preRemove(protocolMapper);
    }

    @Override
    public void removeImportedUsers(RealmModel realm, String storageProviderId) {
        realmInvalidations.add(realm.getId());
        getDelegate().removeImportedUsers(realm, storageProviderId);
    }

    @Override
    public void unlinkUsers(RealmModel realm, String storageProviderId) {
        realmInvalidations.add(realm.getId());
        getDelegate().unlinkUsers(realm, storageProviderId);
    }

    // ========== Helper methods for invalidation registration ==========

    public void registerInvalidation(String id) {
        invalidations.add(id);
    }

    public boolean isInvalid(String key) {
        return invalidations.contains(key);
    }
}
