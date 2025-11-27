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

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for JacksonRedisSerializer.
 */
public class JacksonRedisSerializerTest {

    private JacksonRedisSerializer serializer;

    @Before
    public void setUp() {
        serializer = new JacksonRedisSerializer();
    }

    @Test
    public void testSerializeNull() {
        byte[] result = serializer.serialize(null);
        assertNull(result);
    }

    @Test
    public void testDeserializeNull() {
        Object result = serializer.deserialize(null, Object.class);
        assertNull(result);
    }

    @Test
    public void testDeserializeEmptyArray() {
        Object result = serializer.deserialize(new byte[0], Object.class);
        assertNull(result);
    }

    @Test
    public void testSerializeAndDeserializeString() {
        String original = "test string";
        byte[] serialized = serializer.serialize(original);
        assertNotNull(serialized);
        assertTrue(serialized.length > 0);

        String deserialized = serializer.deserialize(serialized, String.class);
        assertEquals(original, deserialized);
    }

    @Test
    public void testSerializeAndDeserializeSimpleObject() {
        TestObject original = new TestObject("test-id", "test-value", 42);
        byte[] serialized = serializer.serialize(original);
        assertNotNull(serialized);

        TestObject deserialized = serializer.deserialize(serialized, TestObject.class);
        assertNotNull(deserialized);
        assertEquals(original.id, deserialized.id);
        assertEquals(original.value, deserialized.value);
        assertEquals(original.number, deserialized.number);
    }

    @Test(expected = RedisSerializationException.class)
    public void testDeserializeInvalidData() {
        byte[] invalidData = "not valid json{{{".getBytes();
        serializer.deserialize(invalidData, TestObject.class);
    }

    // Simple test class
    public static class TestObject {
        public String id;
        public String value;
        public int number;

        public TestObject() {}

        public TestObject(String id, String value, int number) {
            this.id = id;
            this.value = value;
            this.number = number;
        }
    }
}
