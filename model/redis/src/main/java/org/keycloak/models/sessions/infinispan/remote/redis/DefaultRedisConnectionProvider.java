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

import io.lettuce.core.RedisClient;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.jboss.logging.Logger;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultRedisConnectionProvider implements RedisConnectionProvider {

    private static final Logger logger = Logger.getLogger(DefaultRedisConnectionProvider.class);

    private final StatefulRedisConnection<String, String> standaloneConnection;
    private final StatefulRedisClusterConnection<String, String> clusterConnection;
    private final RedisSerializer serializer;
    private final String keyPrefix;
    private final boolean clusterMode;
    private final String connectionInfo;
    private static final AtomicLong versionCounter = new AtomicLong(System.currentTimeMillis());

    public DefaultRedisConnectionProvider(RedisClient client, StatefulRedisConnection<String, String> conn, String prefix, String info) {
        this.standaloneConnection = Objects.requireNonNull(conn);
        this.clusterConnection = null;
        this.serializer = RedisSerializer.getInstance();
        this.keyPrefix = prefix != null ? prefix : DEFAULT_KEY_PREFIX;
        this.clusterMode = false;
        this.connectionInfo = info;
    }

    public DefaultRedisConnectionProvider(RedisClusterClient client, StatefulRedisClusterConnection<String, String> conn, String prefix, String info) {
        this.standaloneConnection = null;
        this.clusterConnection = Objects.requireNonNull(conn);
        this.serializer = RedisSerializer.getInstance();
        this.keyPrefix = prefix != null ? prefix : DEFAULT_KEY_PREFIX;
        this.clusterMode = true;
        this.connectionInfo = info;
    }

    @Override
    public <V> V get(String cacheName, String key, Class<V> type) {
        String fullKey = getCacheKey(cacheName, key);
        String json = clusterMode ? clusterConnection.sync().get(fullKey) : standaloneConnection.sync().get(fullKey);
        return deserialize(json, type);
    }

    @Override
    public <V> CompletionStage<V> getAsync(String cacheName, String key, Class<V> type) {
        String fullKey = getCacheKey(cacheName, key);
        var future = clusterMode ? clusterConnection.async().get(fullKey) : standaloneConnection.async().get(fullKey);
        return future.thenApply(json -> deserialize(json, type)).toCompletableFuture();
    }

    @Override
    public <V> VersionedValue<V> getWithVersion(String cacheName, String key, Class<V> type) {
        String fullKey = getCacheKey(cacheName, key);
        String versionKey = keyPrefix + cacheName + ":_ver:" + key;
        String json = clusterMode ? clusterConnection.sync().get(fullKey) : standaloneConnection.sync().get(fullKey);
        if (json == null) return null;
        String vs = clusterMode ? clusterConnection.sync().get(versionKey) : standaloneConnection.sync().get(versionKey);
        long ver = vs != null ? Long.parseLong(vs) : 0L;
        return new VersionedValue<>(deserialize(json, type), ver);
    }

    @Override
    public <V> CompletionStage<VersionedValue<V>> getWithVersionAsync(String cacheName, String key, Class<V> type) {
        return getAsync(cacheName, key, type).thenApply(v -> v != null ? new VersionedValue<>(v, 0L) : null);
    }

    @Override
    public <V> void put(String cacheName, String key, V value, long lifespan, TimeUnit unit) {
        String fullKey = getCacheKey(cacheName, key);
        String json = serialize(value);
        long ms = unit.toMillis(lifespan);
        if (clusterMode) clusterConnection.sync().psetex(fullKey, ms, json);
        else standaloneConnection.sync().psetex(fullKey, ms, json);
    }

    @Override
    public <V> CompletionStage<Void> putAsync(String cacheName, String key, V value, long lifespan, TimeUnit unit) {
        String fullKey = getCacheKey(cacheName, key);
        String json = serialize(value);
        long ms = unit.toMillis(lifespan);
        var f = clusterMode ? clusterConnection.async().psetex(fullKey, ms, json) : standaloneConnection.async().psetex(fullKey, ms, json);
        return f.thenApply(ok -> (Void) null).toCompletableFuture();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V putIfAbsent(String cacheName, String key, V value, long lifespan, TimeUnit unit) {
        String fullKey = getCacheKey(cacheName, key);
        String json = serialize(value);
        SetArgs args = SetArgs.Builder.nx().px(unit.toMillis(lifespan));
        String result = clusterMode ? clusterConnection.sync().set(fullKey, json, args) : standaloneConnection.sync().set(fullKey, json, args);
        if (result != null) return null;
        String existing = clusterMode ? clusterConnection.sync().get(fullKey) : standaloneConnection.sync().get(fullKey);
        return deserialize(existing, (Class<V>) value.getClass());
    }

    @Override
    public <V> CompletionStage<V> putIfAbsentAsync(String cacheName, String key, V value, long lifespan, TimeUnit unit) {
        return CompletableFuture.supplyAsync(() -> putIfAbsent(cacheName, key, value, lifespan, unit));
    }

    @Override
    public <V> boolean replaceWithVersion(String cacheName, String key, V value, long version, long lifespan, TimeUnit unit) {
        put(cacheName, key, value, lifespan, unit);
        return true;
    }

    @Override
    public <V> CompletionStage<Boolean> replaceWithVersionAsync(String cacheName, String key, V value, long version, long lifespan, TimeUnit unit) {
        return putAsync(cacheName, key, value, lifespan, unit).thenApply(v -> true);
    }

    @Override
    public <V> V remove(String cacheName, String key, Class<V> type) {
        String fullKey = getCacheKey(cacheName, key);
        String json = clusterMode ? clusterConnection.sync().get(fullKey) : standaloneConnection.sync().get(fullKey);
        V prev = deserialize(json, type);
        if (clusterMode) clusterConnection.sync().del(fullKey);
        else standaloneConnection.sync().del(fullKey);
        return prev;
    }

    @Override
    public <V> CompletionStage<V> removeAsync(String cacheName, String key, Class<V> type) {
        return CompletableFuture.supplyAsync(() -> remove(cacheName, key, type));
    }

    @Override
    public boolean delete(String cacheName, String key) {
        String fullKey = getCacheKey(cacheName, key);
        Long del = clusterMode ? clusterConnection.sync().del(fullKey) : standaloneConnection.sync().del(fullKey);
        return del != null && del > 0;
    }

    @Override
    public CompletionStage<Boolean> deleteAsync(String cacheName, String key) {
        return CompletableFuture.supplyAsync(() -> delete(cacheName, key));
    }

    @Override
    public boolean containsKey(String cacheName, String key) {
        String fullKey = getCacheKey(cacheName, key);
        Long ex = clusterMode ? clusterConnection.sync().exists(fullKey) : standaloneConnection.sync().exists(fullKey);
        return ex != null && ex > 0;
    }

    @Override
    public long removeByPattern(String cacheName, String pattern) { return 0; }

    @Override
    public CompletionStage<Long> removeByPatternAsync(String cacheName, String pattern) {
        return CompletableFuture.completedFuture(0L);
    }

    @Override
    public void publish(String channel, String message) {
        if (clusterMode) clusterConnection.sync().publish(channel, message);
        else standaloneConnection.sync().publish(channel, message);
    }

    @Override
    public String getCacheKey(String cacheName, String key) {
        return keyPrefix + cacheName + ":" + key;
    }

    @Override
    public boolean isClusterMode() { return clusterMode; }

    @Override
    public boolean isHealthy() {
        try {
            String pong = clusterMode ? clusterConnection.sync().ping() : standaloneConnection.sync().ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) { return false; }
    }

    @Override
    public String getConnectionInfo() { return connectionInfo; }

    @Override
    public void close() {}

    private <V> String serialize(V value) { return serializer.serializeToString(value); }
    private <V> V deserialize(String json, Class<V> type) { return serializer.deserialize(json, type); }
}
