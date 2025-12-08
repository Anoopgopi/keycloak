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

import org.keycloak.provider.Provider;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Provider for Redis connections and operations.
 * This interface abstracts Redis operations to allow both standalone and cluster mode support.
 */
public interface RedisConnectionProvider extends Provider {

    // Cache name constants - matching Infinispan cache names for compatibility
    String USER_SESSION_CACHE_NAME = "sessions";
    String OFFLINE_USER_SESSION_CACHE_NAME = "offlineSessions";
    String CLIENT_SESSION_CACHE_NAME = "clientSessions";
    String OFFLINE_CLIENT_SESSION_CACHE_NAME = "offlineClientSessions";
    String AUTHENTICATION_SESSIONS_CACHE_NAME = "authenticationSessions";
    String LOGIN_FAILURE_CACHE_NAME = "loginFailures";
    String SINGLE_USE_OBJECT_CACHE_NAME = "actionTokens";
    String WORK_CACHE_NAME = "work";

    // Aliases for convenience
    String CACHE_AUTHENTICATION_SESSIONS = AUTHENTICATION_SESSIONS_CACHE_NAME;
    String CACHE_LOGIN_FAILURES = LOGIN_FAILURE_CACHE_NAME;

    // Default key prefix
    String DEFAULT_KEY_PREFIX = "kc:";

    /**
     * Get a value from Redis.
     *
     * @param cacheName The logical cache name
     * @param key       The key
     * @param type      The expected value type
     * @return The value or null if not found
     */
    <V> V get(String cacheName, String key, Class<V> type);

    /**
     * Async version of get.
     */
    <V> CompletionStage<V> getAsync(String cacheName, String key, Class<V> type);

    /**
     * Get a value with its version for optimistic locking.
     *
     * @param cacheName The logical cache name
     * @param key       The key
     * @param type      The expected value type
     * @return The value with version metadata, or null if not found
     */
    <V> VersionedValue<V> getWithVersion(String cacheName, String key, Class<V> type);

    /**
     * Async version of getWithVersion.
     */
    <V> CompletionStage<VersionedValue<V>> getWithVersionAsync(String cacheName, String key, Class<V> type);

    /**
     * Put a value in Redis with TTL.
     *
     * @param cacheName  The logical cache name
     * @param key        The key
     * @param value      The value
     * @param lifespan   The lifespan
     * @param unit       The time unit
     */
    <V> void put(String cacheName, String key, V value, long lifespan, TimeUnit unit);

    /**
     * Async version of put.
     */
    <V> CompletionStage<Void> putAsync(String cacheName, String key, V value, long lifespan, TimeUnit unit);

    /**
     * Put a value only if the key doesn't exist.
     *
     * @param cacheName  The logical cache name
     * @param key        The key
     * @param value      The value
     * @param lifespan   The lifespan
     * @param unit       The time unit
     * @return The previous value if key existed, null if put was successful
     */
    <V> V putIfAbsent(String cacheName, String key, V value, long lifespan, TimeUnit unit);

    /**
     * Async version of putIfAbsent.
     */
    <V> CompletionStage<V> putIfAbsentAsync(String cacheName, String key, V value, long lifespan, TimeUnit unit);

    /**
     * Replace a value only if the version matches (optimistic locking).
     *
     * @param cacheName The logical cache name
     * @param key       The key
     * @param value     The new value
     * @param version   The expected version
     * @param lifespan  The lifespan
     * @param unit      The time unit
     * @return true if replaced, false if version mismatch or key doesn't exist
     */
    <V> boolean replaceWithVersion(String cacheName, String key, V value, long version, long lifespan, TimeUnit unit);

    /**
     * Async version of replaceWithVersion.
     */
    <V> CompletionStage<Boolean> replaceWithVersionAsync(String cacheName, String key, V value, long version, long lifespan, TimeUnit unit);

    /**
     * Remove a key from Redis.
     *
     * @param cacheName The logical cache name
     * @param key       The key
     * @return The previous value, or null if key didn't exist
     */
    <V> V remove(String cacheName, String key, Class<V> type);

    /**
     * Async version of remove.
     */
    <V> CompletionStage<V> removeAsync(String cacheName, String key, Class<V> type);

    /**
     * Remove a key without returning the value.
     */
    boolean delete(String cacheName, String key);

    /**
     * Async version of delete.
     */
    CompletionStage<Boolean> deleteAsync(String cacheName, String key);

    /**
     * Check if a key exists.
     */
    boolean containsKey(String cacheName, String key);

    /**
     * Remove all keys matching a pattern.
     *
     * @param cacheName The logical cache name
     * @param pattern   The pattern (e.g., "realm:*")
     * @return The number of keys removed
     */
    long removeByPattern(String cacheName, String pattern);

    /**
     * Async version of removeByPattern.
     */
    CompletionStage<Long> removeByPatternAsync(String cacheName, String pattern);

    /**
     * Publish an event to a Redis channel.
     *
     * @param channel The channel name
     * @param message The message to publish
     */
    void publish(String channel, String message);

    /**
     * Get the full Redis key for a cache entry.
     */
    String getCacheKey(String cacheName, String key);

    /**
     * Check if running in cluster mode.
     */
    boolean isClusterMode();

    /**
     * Check if the connection is healthy.
     */
    boolean isHealthy();

    /**
     * Get connection info for operational monitoring.
     */
    String getConnectionInfo();

    /**
     * Value wrapper that includes version information for optimistic locking.
     */
    record VersionedValue<V>(V value, long version) {
        public boolean hasValue() {
            return value != null;
        }
    }
}
