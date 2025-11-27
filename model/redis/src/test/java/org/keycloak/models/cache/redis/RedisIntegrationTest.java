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
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.keycloak.models.cache.infinispan.entities.Revisioned;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import static org.junit.Assert.*;

/**
 * Integration tests for Redis cache using Testcontainers.
 */
public class RedisIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @ClassRule
    @SuppressWarnings("resource")
    public static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(REDIS_PORT);

    private RedisClient redisClient;
    private StatefulRedisConnection<String, byte[]> connection;
    private StatefulRedisConnection<String, String> stringConnection;
    private RedisCommands<String, byte[]> commands;
    private RedisCommands<String, String> stringCommands;
    private JacksonRedisSerializer serializer;

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
        
        commands = connection.sync();
        stringCommands = stringConnection.sync();
        serializer = new JacksonRedisSerializer();

        // Clear Redis before each test
        commands.flushall();
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
    public void testBasicSetAndGet() {
        String key = "test:key";
        String value = "Hello Redis!";

        stringCommands.set(key, value);
        String result = stringCommands.get(key);

        assertEquals(value, result);
    }

    @Test
    public void testSerializationRoundTrip() {
        TestRevisioned original = new TestRevisioned("user-123", 1L);
        byte[] serialized = serializer.serialize(original);
        assertNotNull(serialized);

        String key = "kc:users:user-123";
        commands.set(key, serialized);

        byte[] retrieved = commands.get(key);
        assertNotNull(retrieved);

        TestRevisioned deserialized = serializer.deserialize(retrieved, TestRevisioned.class);
        assertNotNull(deserialized);
        assertEquals(original.getId(), deserialized.getId());
        assertEquals(original.getRevision(), deserialized.getRevision());
    }

    @Test
    public void testRevisionBasedCaching() {
        String objectKey = "kc:users:user-456";
        String revisionKey = "kc:userRevisions:user-456";

        // Set initial revision
        stringCommands.set(revisionKey, "1");

        // Store object with revision 1
        TestRevisioned obj = new TestRevisioned("user-456", 1L);
        commands.set(objectKey, serializer.serialize(obj));

        // Verify retrieval
        byte[] data = commands.get(objectKey);
        TestRevisioned retrieved = serializer.deserialize(data, TestRevisioned.class);
        assertEquals(Long.valueOf(1L), retrieved.getRevision());

        // Bump revision (simulate invalidation)
        stringCommands.set(revisionKey, "2");

        // Object should now be considered stale
        String storedRevision = stringCommands.get(revisionKey);
        assertEquals("2", storedRevision);
        assertTrue(Long.parseLong(storedRevision) > retrieved.getRevision());
    }

    @Test
    public void testKeyExpiration() throws InterruptedException {
        String key = "kc:users:expiring";
        TestRevisioned obj = new TestRevisioned("expiring", 1L);

        // Set with 1 second TTL
        commands.setex(key, 1, serializer.serialize(obj));

        // Should exist immediately
        assertNotNull(commands.get(key));

        // Wait for expiration
        Thread.sleep(1500);

        // Should be gone
        assertNull(commands.get(key));
    }

    @Test
    public void testKeyPatternScan() {
        // Insert multiple keys
        for (int i = 0; i < 10; i++) {
            String key = "kc:users:realm1.user" + i;
            commands.set(key, serializer.serialize(new TestRevisioned("user" + i, 1L)));
        }

        // Add some keys from another realm
        for (int i = 0; i < 5; i++) {
            String key = "kc:users:realm2.user" + i;
            commands.set(key, serializer.serialize(new TestRevisioned("user" + i, 1L)));
        }

        // Count keys matching pattern for realm1
        int realm1Count = 0;
        io.lettuce.core.ScanIterator<String> iterator = 
            io.lettuce.core.ScanIterator.scan(commands, io.lettuce.core.ScanArgs.Builder.matches("kc:users:realm1.*"));
        while (iterator.hasNext()) {
            iterator.next();
            realm1Count++;
        }

        assertEquals(10, realm1Count);
    }

    @Test
    public void testAtomicIncrement() {
        String counterKey = "kc:counter";

        // Initialize counter
        stringCommands.set(counterKey, "0");

        // Increment atomically
        Long newValue = stringCommands.incr(counterKey);
        assertEquals(Long.valueOf(1L), newValue);

        newValue = stringCommands.incr(counterKey);
        assertEquals(Long.valueOf(2L), newValue);

        // Verify final value
        assertEquals("2", stringCommands.get(counterKey));
    }

    @Test
    public void testDeleteKeys() {
        String key1 = "kc:users:delete1";
        String key2 = "kc:users:delete2";

        commands.set(key1, serializer.serialize(new TestRevisioned("d1", 1L)));
        commands.set(key2, serializer.serialize(new TestRevisioned("d2", 1L)));

        // Both should exist
        assertNotNull(commands.get(key1));
        assertNotNull(commands.get(key2));

        // Delete one
        commands.del(key1);
        assertNull(commands.get(key1));
        assertNotNull(commands.get(key2));

        // Delete multiple
        commands.del(key2);
        assertNull(commands.get(key2));
    }

    @Test
    public void testPing() {
        String pong = stringCommands.ping();
        assertEquals("PONG", pong);
    }

    /**
     * Simple test implementation of Revisioned interface.
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
