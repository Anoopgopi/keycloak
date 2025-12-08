# Redis Provider Configuration Guide

This guide explains how to configure Keycloak to use Redis for session storage and caching.

## Prerequisites

- Redis 6.0+ (standalone or cluster mode)
- AWS ElastiCache (Redis) is fully supported

## Quick Start with Docker

```bash
# Start Redis
cd model/redis/docker
docker-compose up -d

# Verify Redis is running
docker exec -it redis redis-cli ping
# Should return: PONG
```

## Keycloak Configuration

### Environment Variables

```bash
# Redis Connection
KC_SPI_REDIS_CONNECTION_DEFAULT_URL=redis://localhost:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_KEY_PREFIX=kc:

# For Redis with password
KC_SPI_REDIS_CONNECTION_DEFAULT_URL=redis://:password@localhost:6379

# For Redis Cluster
KC_SPI_REDIS_CONNECTION_DEFAULT_URL=redis://node1:6379,node2:6379,node3:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_CLUSTER_MODE=true

# For AWS ElastiCache with TLS
KC_SPI_REDIS_CONNECTION_DEFAULT_URL=rediss://your-cluster.cache.amazonaws.com:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_CLUSTER_MODE=true
```

### Provider Selection

To use Redis providers instead of Infinispan:

```bash
# Enable Redis providers
KC_SPI_USER_SESSION_PROVIDER=redis
KC_SPI_SINGLE_USE_OBJECT_PROVIDER=redis
KC_SPI_AUTHENTICATION_SESSION_PROVIDER=redis
KC_SPI_USER_LOGIN_FAILURE_PROVIDER=redis
KC_SPI_CLUSTER_PROVIDER=redis
```

### Session Lifespans

```bash
# User session lifespan (seconds) - default 36000 (10 hours)
KC_SPI_USER_SESSION_REDIS_SESSION_LIFESPAN=36000

# Offline session lifespan (seconds) - default 2592000 (30 days)
KC_SPI_USER_SESSION_REDIS_OFFLINE_SESSION_LIFESPAN=2592000

# Authentication session lifespan (seconds) - default 300 (5 minutes)
KC_SPI_AUTHENTICATION_SESSION_REDIS_AUTH_SESSION_LIFESPAN=300

# Login failure lifespan (seconds) - default 900 (15 minutes)
KC_SPI_USER_LOGIN_FAILURE_REDIS_FAILURE_LIFESPAN=900
```

## Docker Compose Example

```yaml
version: '3.8'

services:
  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes
    volumes:
      - redis-data:/data

  keycloak:
    image: quay.io/keycloak/keycloak:latest
    ports:
      - "8080:8080"
    environment:
      - KEYCLOAK_ADMIN=admin
      - KEYCLOAK_ADMIN_PASSWORD=admin
      - KC_DB=postgres
      - KC_DB_URL=jdbc:postgresql://postgres:5432/keycloak
      - KC_DB_USERNAME=keycloak
      - KC_DB_PASSWORD=keycloak
      # Redis configuration
      - KC_SPI_REDIS_CONNECTION_DEFAULT_URL=redis://redis:6379
      - KC_SPI_USER_SESSION_PROVIDER=redis
      - KC_SPI_SINGLE_USE_OBJECT_PROVIDER=redis
      - KC_SPI_AUTHENTICATION_SESSION_PROVIDER=redis
      - KC_SPI_USER_LOGIN_FAILURE_PROVIDER=redis
      - KC_SPI_CLUSTER_PROVIDER=redis
    command: start-dev
    depends_on:
      - redis
      - postgres

  postgres:
    image: postgres:15-alpine
    environment:
      - POSTGRES_DB=keycloak
      - POSTGRES_USER=keycloak
      - POSTGRES_PASSWORD=keycloak
    volumes:
      - postgres-data:/var/lib/postgresql/data

volumes:
  redis-data:
  postgres-data:
```

## AWS ElastiCache Configuration

For production deployment with AWS ElastiCache:

```bash
# ElastiCache Redis Cluster with TLS
KC_SPI_REDIS_CONNECTION_DEFAULT_URL=rediss://your-cluster.xxxxx.cache.amazonaws.com:6379
KC_SPI_REDIS_CONNECTION_DEFAULT_CLUSTER_MODE=true

# If using IAM authentication (requires additional setup)
# KC_SPI_REDIS_CONNECTION_DEFAULT_USERNAME=your-iam-user
```

### Security Group Requirements

Ensure your Keycloak instances can reach ElastiCache:
- Inbound rule: TCP port 6379 from Keycloak security group
- ElastiCache should be in the same VPC or have VPC peering

## Redis Key Structure

All keys follow this pattern: `{prefix}{cacheName}:{key}`

| Cache Name | Purpose | Example Key |
|------------|---------|-------------|
| `sessions` | User sessions | `kc:sessions:abc123` |
| `offlineSessions` | Offline sessions | `kc:offlineSessions:xyz789` |
| `clientSessions` | Client sessions | `kc:clientSessions:def456` |
| `authenticationSessions` | Auth flows | `kc:authenticationSessions:ghi012` |
| `actionTokens` | Single-use tokens | `kc:actionTokens:jkl345` |
| `loginFailures` | Brute force data | `kc:loginFailures:realm:user` |
| `work` | Cluster locks | `kc:work:lock:taskKey` |

## Monitoring

### Redis CLI Commands

```bash
# Check key count per cache
redis-cli KEYS "kc:sessions:*" | wc -l
redis-cli KEYS "kc:authenticationSessions:*" | wc -l

# Monitor real-time commands
redis-cli MONITOR

# Check memory usage
redis-cli INFO memory

# Check connected clients
redis-cli INFO clients
```

### Health Check

The `RedisConnectionProvider.isHealthy()` method performs a PING check. Use it in your health endpoints.

## Troubleshooting

### Connection Issues

1. **Verify Redis is reachable:**
   ```bash
   redis-cli -h <host> -p <port> ping
   ```

2. **Check TLS configuration** for ElastiCache:
   - Use `rediss://` scheme (note the double 's')
   - Ensure certificates are trusted

3. **Cluster mode issues:**
   - Verify all nodes are accessible
   - Check for MOVED/ASK redirections in logs

### Session Issues

1. **Sessions not persisting:**
   - Check Redis connectivity
   - Verify TTL settings are appropriate
   - Check for serialization errors in logs

2. **High latency:**
   - Consider using Redis cluster for distribution
   - Check network latency to Redis
   - Monitor Redis memory and eviction policies

## Migration from Infinispan

To migrate from embedded/remote Infinispan to Redis:

1. **Plan for session loss:** Switching providers will invalidate existing sessions
2. **Schedule maintenance window:** Users will need to re-authenticate
3. **Update configuration:** Switch provider settings
4. **Monitor:** Watch for errors during initial usage

## Performance Tuning

### Redis Configuration

```conf
# redis.conf recommendations
maxmemory 2gb
maxmemory-policy volatile-lru
tcp-keepalive 300
timeout 0
```

### Connection Pool

The Lettuce client manages connections automatically. For high-load scenarios:

```bash
# Increase connection pool size (future configuration)
KC_SPI_REDIS_CONNECTION_DEFAULT_POOL_SIZE=20
```
