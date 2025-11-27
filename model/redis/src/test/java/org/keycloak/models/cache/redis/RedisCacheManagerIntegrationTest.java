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

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisClusterCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.keycloak.connections.redis.RedisConnectionProvider;
import org.keycloak.models.cache.infinispan.entities.Revisioned;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/**
 * Integration tests for RedisCacheManager functionality.
 */
public class RedisCacheManagerIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @ClassRule
    @SuppressWarnings("resource")
    public static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private RedisClient redisClient;
    private StatefulRedisConnection<String, byte[]> connection;
    private StatefulRedisConnection<String, String> stringConnection;
    private TestRedisConnectionProvider connectionProvider;
    private RedisUserCacheManager cacheManager;

    @Before
    public void setUp() {
        String host = redis.getHost();
        Integer port = redis.getMappedPort(REDIS_PORT);

        RedisURI uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .build();

        redisClient = RedisClient.create(uri);

        RedisCodec<String, byte[]> byteCodec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        connection = redisClient.connect(byteCodec);
        stringConnection = redisClient.connect();

        connectionProvider = new TestRedisConnectionProvider(
                connection.sync(),
                stringConnection.sync()
        );
        cacheManager = new RedisUserCacheManager(connectionProvider);

        // Clear Redis before each test
        connection.sync().flushall();
    }

    @After
    public void tearDown() {
        if (connection != null) {
            connection.close();
        }
        if (stringConnection != null) {
            stringConnection.close();
        }
        if (redisClient != null) {
            redisClient.shutdown();
        }
    }

    @Test
    public void testAddAndGetRevisioned() {
        long startupRevision = cacheManager.getCurrentCounter();
        TestRevisioned entity = new TestRevisioned("user-1", startupRevision);

        cacheManager.addRevisioned(entity, startupRevision);

        TestRevisioned retrieved = cacheManager.get("user-1", TestRevisioned.class);
        assertNotNull("Should retrieve cached entity", retrieved);
        assertEquals("user-1", retrieved.getId());
    }

    @Test
    public void testGetNonExistent() {
        TestRevisioned retrieved = cacheManager.get("non-existent", TestRevisioned.class);
        assertNull(retrieved);
    }

    @Test
    public void testInvalidateObject() {
        long startupRevision = cacheManager.getCurrentCounter();
        TestRevisioned entity = new TestRevisioned("user-2", startupRevision);

        cacheManager.addRevisioned(entity, startupRevision);
        
        // Verify it was cached
        TestRevisioned cached = cacheManager.get("user-2", TestRevisioned.class);
        assertNotNull("Should find cached entity before invalidation", cached);

        // Invalidate
        cacheManager.invalidateObject("user-2");
        
        // After invalidation, the revision is bumped so the entry becomes stale
        // The actual data is deleted by invalidateObject
        TestRevisioned afterInvalidation = cacheManager.get("user-2", TestRevisioned.class);
        assertNull("Should not find entity after invalidation", afterInvalidation);
    }

    @Test
    public void testClear() {
        long startupRevision = cacheManager.getCurrentCounter();

        // Add multiple entities with current revision
        for (int i = 0; i < 5; i++) {
            TestRevisioned entity = new TestRevisioned("user-" + i, startupRevision);
            cacheManager.addRevisioned(entity, startupRevision);
        }

        // Verify at least one exists
        TestRevisioned before = cacheManager.get("user-0", TestRevisioned.class);
        assertNotNull("Should find entity before clear", before);

        // Clear cache
        cacheManager.clear();

        // All should be gone
        assertNull("Should not find entity after clear", cacheManager.get("user-0", TestRevisioned.class));
        assertNull("Should not find entity after clear", cacheManager.get("user-4", TestRevisioned.class));
    }

    @Test
    public void testUserUpdatedInvalidations() {
        Set<String> invalidations = new HashSet<>();

        cacheManager.userUpdatedInvalidations(
                "user-123",
                "johndoe",
                "john@example.com",
                "realm-1",
                invalidations
        );

        assertTrue(invalidations.contains("user-123"));
        assertTrue(invalidations.contains("realm-1.username.johndoe"));
        assertTrue(invalidations.contains("realm-1.email.john@example.com"));
    }

    @Test
    public void testFullUserInvalidation() {
        Set<String> invalidations = new HashSet<>();

        cacheManager.fullUserInvalidation(
                "user-456",
                "janedoe",
                "jane@example.com",
                "realm-2",
                true,
                Map.of("google", "google-id-123", "github", "github-id-456"),
                invalidations
        );

        assertTrue(invalidations.contains("user-456"));
        assertTrue(invalidations.contains("realm-2.username.janedoe"));
        assertTrue(invalidations.contains("realm-2.email.jane@example.com"));
        assertTrue(invalidations.contains("user-456.idplinks"));
        assertTrue(invalidations.contains("user-456.consents"));
        // Check federated identity keys
        assertTrue(invalidations.stream().anyMatch(k -> k.contains("google")));
        assertTrue(invalidations.stream().anyMatch(k -> k.contains("github")));
    }

    @Test
    public void testConsentInvalidation() {
        Set<String> invalidations = new HashSet<>();
        cacheManager.consentInvalidation("user-789", invalidations);
        assertTrue(invalidations.contains("user-789.consents"));
    }

    @Test
    public void testFederatedIdentityLinkInvalidation() {
        Set<String> invalidations = new HashSet<>();
        cacheManager.federatedIdentityLinkUpdatedInvalidation("user-abc", invalidations);
        assertTrue(invalidations.contains("user-abc.idplinks"));
    }

    @Test
    public void testRevisionBumping() {
        // Bump version by invalidating a key
        cacheManager.invalidateObject("dummy-key-1");
        long rev1 = cacheManager.getCurrentCounter();
        
        cacheManager.invalidateObject("dummy-key-2");
        long rev2 = cacheManager.getCurrentCounter();
        
        // Counter should have incremented
        assertTrue("Counter should increment after invalidations", rev2 > rev1);
    }

    @Test
    public void testAddRevisionedWithLifespan() {
        long startupRevision = cacheManager.getCurrentCounter();
        TestRevisioned entity = new TestRevisioned("expiring-user", startupRevision);

        // Add with 2 second lifespan
        cacheManager.addRevisioned(entity, startupRevision, 2000);

        // Should exist immediately
        TestRevisioned retrieved = cacheManager.get("expiring-user", TestRevisioned.class);
        assertNotNull("Entity with lifespan should exist immediately after caching", retrieved);
    }

    /**
     * Test implementation of RedisConnectionProvider for testing.
     */
    private static class TestRedisConnectionProvider implements RedisConnectionProvider {
        private final RedisCommands<String, byte[]> commands;
        private final RedisCommands<String, String> stringCommands;
        private final Executor executor = Executors.newSingleThreadExecutor();

        TestRedisConnectionProvider(RedisCommands<String, byte[]> commands,
                                    RedisCommands<String, String> stringCommands) {
            this.commands = commands;
            this.stringCommands = stringCommands;
        }

        @Override
        public RedisCommands<String, byte[]> getCache(String cacheName) {
            return commands;
        }

        @Override
        public RedisCommands<String, String> getRevisionsCache(String cacheName) {
            return stringCommands;
        }

        @Override
        public boolean isClusterMode() {
            return false;
        }

        @Override
        public RedisClusterCommands<String, byte[]> getClusterCommands() {
            return null;
        }

        @Override
        public Executor getExecutor() {
            return executor;
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        @Override
        public String getCacheKeyPrefix(String cacheName) {
            return "kc:" + cacheName + ":";
        }

        @Override
        public String getConnectionInfo() {
            return "testcontainer:6379";
        }

        @Override
        public void close() {
        }
    }

    /**
     * Test implementation of Revisioned interface.
     */
    public static class TestRevisioned implements Revisioned {
        private String id;
        private Long revision;

        public TestRevisioned() {}

        public TestRevisioned(String id, Long revision) {
            this.id = id;
            this.revision = revision;
        }

        @Override
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @Override
        public Long getRevision() {
            return revision;
        }

        @Override
        public void setRevision(Long revision) {
            this.revision = revision;
        }
    }
}
