#!/bin/bash

echo "=== Redis Cache Testing Script ==="

# Test local Redis
echo -e "\n1. Testing local Redis (port 6379)..."
redis-cli -p 6379 SET test:key "hello from site1"
redis-cli -p 6379 GET test:key

# Test remote Redis
echo -e "\n2. Testing remote Redis (port 6380)..."
redis-cli -p 6380 SET test:key "hello from site2"
redis-cli -p 6380 GET test:key

# Test Keycloak-style keys
echo -e "\n3. Testing Keycloak cache keys..."
redis-cli -p 6379 SET "kc:users:user-123" '{"id":"user-123","username":"testuser"}'
redis-cli -p 6379 SET "kc:userRevisions:user-123" "1"
redis-cli -p 6379 GET "kc:users:user-123"

# Test cross-DC stream (simulating invalidation)
echo -e "\n4. Testing Redis Streams (cross-DC events)..."
redis-cli -p 6379 XADD kc:crossdc:events '*' event '{"type":"USER_UPDATED"}' source site1
redis-cli -p 6379 XRANGE kc:crossdc:events - +

# Show all Keycloak keys
echo -e "\n5. Listing all kc:* keys..."
echo "Local Redis:"
redis-cli -p 6379 KEYS "kc:*"
echo "Remote Redis:"
redis-cli -p 6380 KEYS "kc:*"

# Cleanup
echo -e "\n6. Cleanup (optional - comment out to keep data)..."
# redis-cli -p 6379 FLUSHALL
# redis-cli -p 6380 FLUSHALL

echo -e "\n=== Done ==="