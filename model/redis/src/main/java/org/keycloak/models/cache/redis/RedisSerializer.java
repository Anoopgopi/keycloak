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

/**
 * Serializer interface for Redis cache entries.
 * <p>
 * Implementations handle serialization/deserialization of cached objects
 * to/from byte arrays for storage in Redis.
 *
 * @author Keycloak Team
 */
public interface RedisSerializer {

    /**
     * Serialize an object to a byte array.
     *
     * @param obj the object to serialize
     * @return serialized byte array
     * @throws RedisSerializationException if serialization fails
     */
    byte[] serialize(Object obj);

    /**
     * Deserialize a byte array to an object.
     *
     * @param data the byte array to deserialize
     * @param type the expected type
     * @param <T>  the type parameter
     * @return deserialized object, or null if data is null
     * @throws RedisSerializationException if deserialization fails
     */
    <T> T deserialize(byte[] data, Class<T> type);
}
