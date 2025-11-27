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

import java.util.Map;
import java.util.stream.Stream;

import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.OrganizationDomainModel;
import org.keycloak.models.OrganizationModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.organization.InvitationManager;
import org.keycloak.organization.OrganizationProvider;

/**
 * Redis-compatible organization provider that delegates to JPA.
 * This provider is used when Redis user cache is enabled to avoid
 * ClassCastException with InfinispanOrganizationProvider.
 */
public class RedisOrganizationProvider implements OrganizationProvider {

    private final KeycloakSession session;
    private OrganizationProvider delegate;

    public RedisOrganizationProvider(KeycloakSession session) {
        this.session = session;
    }

    private OrganizationProvider getDelegate() {
        if (delegate == null) {
            delegate = session.getProvider(OrganizationProvider.class, "jpa");
        }
        return delegate;
    }

    @Override
    public OrganizationModel create(String id, String name, String alias) {
        return getDelegate().create(id, name, alias);
    }

    @Override
    public boolean remove(OrganizationModel organization) {
        return getDelegate().remove(organization);
    }

    @Override
    public void removeAll() {
        getDelegate().removeAll();
    }

    @Override
    public boolean addManagedMember(OrganizationModel organization, UserModel user) {
        return getDelegate().addManagedMember(organization, user);
    }

    @Override
    public boolean addMember(OrganizationModel organization, UserModel user) {
        return getDelegate().addMember(organization, user);
    }

    @Override
    public OrganizationModel getById(String id) {
        return getDelegate().getById(id);
    }

    @Override
    public OrganizationModel getByDomainName(String domainName) {
        return getDelegate().getByDomainName(domainName);
    }

    @Override
    public OrganizationModel getByAlias(String alias) {
        return getDelegate().getByAlias(alias);
    }

    @Override
    public Stream<OrganizationModel> getAllStream(String search, Boolean exact, Integer first, Integer max) {
        return getDelegate().getAllStream(search, exact, first, max);
    }

    @Override
    public Stream<OrganizationModel> getAllStream(Map<String, String> attributes, Integer first, Integer max) {
        return getDelegate().getAllStream(attributes, first, max);
    }

    @Override
    public Stream<UserModel> getMembersStream(OrganizationModel organization, String search, Boolean exact, Integer first, Integer max) {
        return getDelegate().getMembersStream(organization, search, exact, first, max);
    }

    @Override
    public UserModel getMemberById(OrganizationModel organization, String id) {
        return getDelegate().getMemberById(organization, id);
    }

    @Override
    public Stream<OrganizationModel> getByMember(UserModel member) {
        return getDelegate().getByMember(member);
    }

    @Override
    public boolean addIdentityProvider(OrganizationModel organization, IdentityProviderModel identityProvider) {
        return getDelegate().addIdentityProvider(organization, identityProvider);
    }

    @Override
    public Stream<IdentityProviderModel> getIdentityProviders(OrganizationModel organization) {
        return getDelegate().getIdentityProviders(organization);
    }

    @Override
    public boolean removeIdentityProvider(OrganizationModel organization, IdentityProviderModel identityProvider) {
        return getDelegate().removeIdentityProvider(organization, identityProvider);
    }

    @Override
    public boolean isManagedMember(OrganizationModel organization, UserModel member) {
        return getDelegate().isManagedMember(organization, member);
    }

    @Override
    public boolean removeMember(OrganizationModel organization, UserModel member) {
        return getDelegate().removeMember(organization, member);
    }

    @Override
    public long count() {
        return getDelegate().count();
    }

    @Override
    public long getMembersCount(OrganizationModel organization) {
        return getDelegate().getMembersCount(organization);
    }

    @Override
    public boolean isEnabled() {
        return getDelegate().isEnabled();
    }

    @Override
    public InvitationManager getInvitationManager() {
        return getDelegate().getInvitationManager();
    }

    @Override
    public void close() {
        // delegate is closed by the session
    }
}
