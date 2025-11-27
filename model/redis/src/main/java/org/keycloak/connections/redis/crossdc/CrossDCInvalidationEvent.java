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

package org.keycloak.connections.redis.crossdc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.util.Set;
import java.util.UUID;

/**
 * Event for cross-datacenter cache invalidation.
 * <p>
 * These events are propagated across sites to ensure cache consistency
 * in active-active deployments.
 *
 * @author Keycloak Team
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
public class CrossDCInvalidationEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String eventId;
    private final String sourceSite;
    private final long timestamp;
    private final EventType eventType;
    private final String cacheName;
    private final Set<String> keys;
    private final String realmId;

    @JsonCreator
    public CrossDCInvalidationEvent(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("sourceSite") String sourceSite,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("eventType") EventType eventType,
            @JsonProperty("cacheName") String cacheName,
            @JsonProperty("keys") Set<String> keys,
            @JsonProperty("realmId") String realmId) {
        this.eventId = eventId;
        this.sourceSite = sourceSite;
        this.timestamp = timestamp;
        this.eventType = eventType;
        this.cacheName = cacheName;
        this.keys = keys;
        this.realmId = realmId;
    }

    /**
     * Create a new invalidation event.
     */
    public static CrossDCInvalidationEvent create(
            String sourceSite,
            EventType eventType,
            String cacheName,
            Set<String> keys,
            String realmId) {
        return new CrossDCInvalidationEvent(
                UUID.randomUUID().toString(),
                sourceSite,
                System.currentTimeMillis(),
                eventType,
                cacheName,
                keys,
                realmId
        );
    }

    /**
     * Create a cache clear event.
     */
    public static CrossDCInvalidationEvent createClearEvent(String sourceSite, String cacheName) {
        return new CrossDCInvalidationEvent(
                UUID.randomUUID().toString(),
                sourceSite,
                System.currentTimeMillis(),
                EventType.CLEAR_CACHE,
                cacheName,
                Set.of(),
                null
        );
    }

    /**
     * Create a realm invalidation event.
     */
    public static CrossDCInvalidationEvent createRealmEvent(
            String sourceSite,
            String cacheName,
            String realmId) {
        return new CrossDCInvalidationEvent(
                UUID.randomUUID().toString(),
                sourceSite,
                System.currentTimeMillis(),
                EventType.INVALIDATE_REALM,
                cacheName,
                Set.of(),
                realmId
        );
    }

    public String getEventId() {
        return eventId;
    }

    public String getSourceSite() {
        return sourceSite;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getCacheName() {
        return cacheName;
    }

    public Set<String> getKeys() {
        return keys;
    }

    public String getRealmId() {
        return realmId;
    }

    /**
     * Check if this event originated from a specific site.
     */
    public boolean isFromSite(String siteName) {
        return sourceSite != null && sourceSite.equals(siteName);
    }

    /**
     * Check if the event has expired based on TTL.
     */
    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - timestamp > ttlMs;
    }

    @Override
    public String toString() {
        return String.format("CrossDCInvalidationEvent[id=%s, source=%s, type=%s, cache=%s, keys=%d]",
                eventId, sourceSite, eventType, cacheName, keys != null ? keys.size() : 0);
    }

    /**
     * Types of cross-DC invalidation events.
     */
    public enum EventType {
        /**
         * Invalidate specific cache keys.
         */
        INVALIDATE_KEYS,

        /**
         * Invalidate all entries for a realm.
         */
        INVALIDATE_REALM,

        /**
         * Clear entire cache.
         */
        CLEAR_CACHE,

        /**
         * User-specific invalidation with metadata.
         */
        USER_UPDATED,

        /**
         * Session invalidation.
         */
        SESSION_INVALIDATED,

        /**
         * Realm configuration changed.
         */
        REALM_UPDATED
    }
}
