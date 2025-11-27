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

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.api.sync.RedisCommands;
import org.jboss.logging.Logger;
import org.keycloak.cluster.ClusterProvider;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.cache.infinispan.entities.Revisioned;
import org.keycloak.models.cache.infinispan.events.InvalidationEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-based CacheManager, mirroring the Infinispan CacheManager pattern.
 * <p>
 * This implementation provides:
 * <ul>
 *   <li>Revision-based optimistic locking for cache consistency</li>
 *   <li>TTL-based expiration via Redis PSETEX</li>
 *   <li>Cluster-wide invalidation via ClusterProvider events</li>
 *   <li>JSON serialization of cached entities</li>
 * </ul>
 * <p>
 * Key differences from Infinispan:
 * <ul>
 *   <li>Uses Redis keys with prefixes instead of embedded cache</li>
 *   <li>Uses Redis INCR for atomic revision counter</li>
 *   <li>Uses SCAN for bulk operations instead of streams</li>
 * </ul>
 *
 * @author Keycloak Team
 */
public abstract class RedisCacheManager {

    protected final RedisConnectionProvider redisProvider;
    protected final String cacheName;
    protected final String revisionsCacheName;
    protected final RedisSerializer serializer;

    // Local counter for revision tracking (similar to Infinispan's UpdateCounter)
    protected final AtomicLong localCounter;

    // Redis keys
    protected final String cacheKeyPrefix;
    protected final String revisionsKeyPrefix;

    public RedisCacheManager(RedisConnectionProvider redisProvider,
                             String cacheName,
                             String revisionsCacheName) {
        this.redisProvider = redisProvider;
        this.cacheName = cacheName;
        this.revisionsCacheName = revisionsCacheName;
        this.serializer = new JacksonRedisSerializer();

        this.cacheKeyPrefix = redisProvider.getCacheKeyPrefix(cacheName);
        this.revisionsKeyPrefix = redisProvider.getCacheKeyPrefix(revisionsCacheName);

        // Initialize counter from system time for uniqueness across restarts
        this.localCounter = new AtomicLong(System.currentTimeMillis());
    }

    protected abstract Logger getLogger();

    /**
     * Get the current local counter value.
     *
     * @return current counter
     */
    public long getCurrentCounter() {
        return localCounter.get();
    }

    /**
     * Get the current revision for a cache key.
     * If no revision exists, returns the current counter.
     *
     * @param id the cache entry ID
     * @return the revision number
     */
    public Long getCurrentRevision(String id) {
        RedisCommands<String, String> revisions = redisProvider.getRevisionsCache(revisionsCacheName);
        String revKey = revisionsKeyPrefix + id;
        String rev = revisions.get(revKey);
        if (rev == null) {
            return localCounter.get();
        }
        return Long.parseLong(rev);
    }

    /**
     * Get a cached object by ID.
     * Validates revision and removes stale entries.
     *
     * @param id   the cache entry ID
     * @param type the expected type
     * @param <T>  type parameter extending Revisioned
     * @return the cached object, or null if not found or stale
     */
    @SuppressWarnings("unchecked")
    public <T extends Revisioned> T get(String id, Class<T> type) {
        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        String key = cacheKeyPrefix + id;

        byte[] data = cache.get(key);
        if (data == null) {
            return null;
        }

        // Check revision
        Long rev = getCurrentRevision(id);
        Revisioned obj;
        try {
            obj = serializer.deserialize(data, type);
        } catch (RedisSerializationException e) {
            getLogger().warnf("Failed to deserialize cache entry %s, removing", id);
            cache.del(key);
            return null;
        }

        if (obj == null) {
            return null;
        }

        long objRev = obj.getRevision() == null ? -1L : obj.getRevision();
        if (rev > objRev) {
            if (getLogger().isTraceEnabled()) {
                getLogger().tracev("get() rev: {0} obj.rev: {1} - removing stale entry", rev, objRev);
            }
            // Object is outdated, remove it
            cache.del(key);
            return null;
        }

        return type.isInstance(obj) ? type.cast(obj) : null;
    }

    /**
     * Invalidate (remove) a cached object and bump its revision.
     *
     * @param id the cache entry ID
     * @return the removed object, or null if not found
     */
    public Object invalidateObject(String id) {
        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        String key = cacheKeyPrefix + id;

        // Use GETDEL for atomic get-and-delete (Redis 6.2+)
        // Fallback to GET + DEL for older versions
        byte[] removed;
        try {
            removed = cache.getdel(key);
        } catch (Exception e) {
            // Fallback for Redis < 6.2
            removed = cache.get(key);
            cache.del(key);
        }

        if (getLogger().isTraceEnabled()) {
            getLogger().tracef("Invalidated key='%s'", key);
        }

        bumpVersion(id);

        if (removed != null) {
            try {
                return serializer.deserialize(removed, Revisioned.class);
            } catch (RedisSerializationException e) {
                // Ignore deserialization errors on removal
                return null;
            }
        }
        return null;
    }

    /**
     * Bump the revision version for an ID.
     *
     * @param id the cache entry ID
     */
    protected void bumpVersion(String id) {
        RedisCommands<String, String> revisions = redisProvider.getRevisionsCache(revisionsCacheName);
        String revKey = revisionsKeyPrefix + id;
        long next = localCounter.incrementAndGet();
        revisions.set(revKey, String.valueOf(next));
    }

    /**
     * Add a revisioned object to the cache with infinite lifespan.
     *
     * @param object          the object to cache
     * @param startupRevision the revision at transaction start
     */
    public void addRevisioned(Revisioned object, long startupRevision) {
        addRevisioned(object, startupRevision, -1);
    }

    /**
     * Add a revisioned object to the cache with optional TTL.
     * <p>
     * Implements optimistic locking:
     * - Skips caching if revision has advanced past startupRevision
     * - Updates revision if object revision is newer
     *
     * @param object          the object to cache
     * @param startupRevision the revision at transaction start
     * @param lifespanMs      TTL in milliseconds, -1 for infinite
     */
    public void addRevisioned(Revisioned object, long startupRevision, long lifespanMs) {
        String id = object.getId();
        String key = cacheKeyPrefix + id;
        String revKey = revisionsKeyPrefix + id;

        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        RedisCommands<String, String> revisions = redisProvider.getRevisionsCache(revisionsCacheName);

        // Get or initialize revision
        String revStr = revisions.get(revKey);
        Long rev;
        if (revStr == null) {
            rev = localCounter.get();
            revisions.set(revKey, String.valueOf(rev));
        } else {
            rev = Long.parseLong(revStr);
        }

        // Check if we should cache (optimistic locking)
        if (rev > startupRevision) {
            if (getLogger().isTraceEnabled()) {
                getLogger().tracev("Skipped cache. Current rev {0}, Transaction start rev {1}",
                    rev, startupRevision);
            }
            return;
        }

        Long objRevision = object.getRevision();
        if (objRevision != null && rev.equals(objRevision)) {
            put(key, object, lifespanMs);
            return;
        }

        if (objRevision != null && rev > objRevision) {
            if (getLogger().isTraceEnabled()) {
                getLogger().tracev("Skipped cache. Object rev {0}, Cache rev {1}",
                    objRevision, rev);
            }
            return;
        }

        // Update revision and cache
        if (objRevision != null) {
            revisions.set(revKey, String.valueOf(objRevision));
        }
        put(key, object, lifespanMs);
    }

    /**
     * Put an object in the cache with optional TTL.
     */
    private void put(String key, Revisioned object, long lifespanMs) {
        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        byte[] data = serializer.serialize(object);

        if (lifespanMs <= 0) {
            cache.set(key, data);
        } else {
            cache.psetex(key, lifespanMs, data);
        }
    }

    /**
     * Clear all entries in this cache.
     * Uses SCAN to avoid blocking on large datasets.
     */
    public void clear() {
        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        RedisCommands<String, String> revisions = redisProvider.getRevisionsCache(revisionsCacheName);

        deleteByPattern(cache, cacheKeyPrefix + "*");
        deleteByPattern(revisions, revisionsKeyPrefix + "*");

        getLogger().debugf("Cleared cache: %s", cacheName);
    }

    /**
     * Delete all keys matching a pattern using SCAN.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private void deleteByPattern(RedisCommands commands, String pattern) {
        ScanArgs scanArgs = ScanArgs.Builder.matches(pattern).limit(1000);
        KeyScanCursor<String> cursor = commands.scan(scanArgs);

        while (true) {
            List<String> keys = cursor.getKeys();
            if (!keys.isEmpty()) {
                commands.del(keys.toArray(new String[0]));
            }

            if (cursor.isFinished()) {
                break;
            }

            cursor = commands.scan(cursor, scanArgs);
        }
    }

    /**
     * Add invalidations for all keys matching a predicate.
     * Uses SCAN to find matching keys.
     *
     * @param pattern       key pattern to match
     * @param invalidations set to add invalidation keys to
     */
    public void addInvalidationsByPattern(String pattern, Set<String> invalidations) {
        RedisCommands<String, byte[]> cache = redisProvider.getCache(cacheName);
        String fullPattern = cacheKeyPrefix + pattern;

        ScanArgs scanArgs = ScanArgs.Builder.matches(fullPattern).limit(1000);
        KeyScanCursor<String> cursor = cache.scan(scanArgs);

        while (true) {
            for (String key : cursor.getKeys()) {
                // Extract the ID from the full key
                String id = key.substring(cacheKeyPrefix.length());
                invalidations.add(id);
            }

            if (cursor.isFinished()) {
                break;
            }

            cursor = cache.scan(cursor, scanArgs);
        }
    }

    /**
     * Send invalidation events to all cluster nodes.
     *
     * @param session           the Keycloak session
     * @param invalidationEvents the events to send
     * @param eventKey          the event key for listeners
     */
    public void sendInvalidationEvents(KeycloakSession session,
                                       Collection<InvalidationEvent> invalidationEvents,
                                       String eventKey) {
        session.getProvider(ClusterProvider.class)
               .notify(eventKey, invalidationEvents, true);
    }

    /**
     * Handle an invalidation event received from another cluster node.
     *
     * @param event the invalidation event
     */
    public void invalidationEventReceived(InvalidationEvent event) {
        Set<String> invalidations = new HashSet<>();
        addInvalidationsFromEvent(event, invalidations);

        getLogger().debugf("Invalidating %d cache items after received event %s",
            invalidations.size(), event);

        for (String invalidation : invalidations) {
            invalidateObject(invalidation);
        }
    }

    /**
     * Extract invalidation IDs from an event.
     * Subclasses implement this to handle specific event types.
     *
     * @param event         the invalidation event
     * @param invalidations set to add invalidation IDs to
     */
    protected abstract void addInvalidationsFromEvent(InvalidationEvent event, Set<String> invalidations);

    /**
     * Invalidate a specific cache key.
     *
     * @param key           the key to invalidate
     * @param invalidations set to add the key to
     */
    public void invalidateCacheKey(String key, Set<String> invalidations) {
        invalidations.add(key);
    }
}
