# Keycloak Redis Provider

This module provides Redis-based storage implementations for Keycloak sessions and single-use objects (authorization codes, tokens, etc.). It is designed as an alternative to the embedded Infinispan cache, enabling the use of managed Redis services like AWS ElastiCache.

## Features

- **User Sessions**: Store user sessions in Redis with configurable TTL
- **Client Sessions**: Store authenticated client sessions
- **Offline Sessions**: Support for offline/refresh token sessions
- **Single-Use Objects**: Authorization codes, one-time tokens (critical for OIDC flows)
- **Cluster Mode**: Support for Redis Cluster (AWS ElastiCache)
- **SSL/TLS**: Encrypted connections to Redis

## Architecture

The implementation follows the same pattern as Keycloak's Remote Infinispan providers:

```
┌─────────────────────────────────────────────────────────────────┐
│                      Keycloak Server                             │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────────────────────────┐ │
│  │ UserSession     │    │ SingleUseObject                     │ │
│  │ Provider (redis)│    │ Provider (redis)                    │ │
│  └────────┬────────┘    └────────────────┬────────────────────┘ │
│           │                              │                       │
│           └──────────────┬───────────────┘                       │
│                          ▼                                       │
│              ┌───────────────────────┐                           │
│              │ RedisConnectionProvider│                           │
│              │ (Lettuce Client)       │                           │
│              └───────────┬───────────┘                           │
└──────────────────────────┼───────────────────────────────────────┘
                           │
                           ▼
                    ┌──────────────┐
                    │    Redis     │
                    │  (Standalone │
                    │  or Cluster) │
                    └──────────────┘
```

## Providers Implemented

| Provider | Interface | Description |
|----------|-----------|-------------|
| `RedisUserSessionProvider` | `UserSessionProvider` | User and client sessions |
| `RedisSingleUseObjectProvider` | `SingleUseObjectProvider` | Auth codes, one-time tokens |
| `RedisConnectionProvider` | Custom SPI | Connection management |

## Configuration

### Basic Redis Connection

```bash
# Enable Redis providers
--spi-user-sessions-provider=redis
--spi-single-use-object-provider=redis

# Redis connection settings
--spi-redis-connection-default-host=localhost
--spi-redis-connection-default-port=6379
--spi-redis-connection-default-password=secret
--spi-redis-connection-default-database=0
--spi-redis-connection-default-ssl=false
```

### Redis Cluster (AWS ElastiCache)

```bash
--spi-redis-connection-default-cluster=true
--spi-redis-connection-default-host=redis-cluster.xxxxx.cache.amazonaws.com
--spi-redis-connection-default-port=6379
--spi-redis-connection-default-ssl=true
--spi-redis-connection-default-password=${REDIS_AUTH_TOKEN}
```

### Session Lifespan

```bash
--spi-user-sessions-redis-session-lifespan=36000
--spi-user-sessions-redis-offline-session-lifespan=5184000
```

## Redis Key Structure

| Cache | Key Pattern | TTL |
|-------|-------------|-----|
| User Sessions | `kc:sessions:{sessionId}` | SSO timeout |
| Offline Sessions | `kc:offlineSessions:{sessionId}` | Offline idle timeout |
| Client Sessions | `kc:clientSessions:{userSessionId}:{clientId}` | Client session timeout |
| Single-Use Objects | `kc:actionTokens:{key}` | Configurable per object |

## Building

```bash
# Build just the Redis module
./mvnw -f model/redis/pom.xml clean install

# Build entire Keycloak with Redis module
./mvnw clean install -DskipTests
```

## Testing

### Start Redis

```bash
docker run -d --name redis -p 6379:6379 redis:7-alpine
```

### Run Keycloak with Redis

```bash
./bin/kc.sh start-dev \
  --spi-redis-connection-default-host=localhost \
  --spi-redis-connection-default-port=6379 \
  --spi-user-sessions-provider=redis \
  --spi-single-use-object-provider=redis
```

### Verify Redis Keys

```bash
redis-cli KEYS "kc:*"
```

## Limitations

1. **No Secondary Indices**: Session queries by user/client are simplified
2. **Version Tracking**: Uses separate keys for optimistic locking
3. **No Authentication Sessions**: Not yet implemented
4. **No Login Failures**: Not yet implemented

## Future Work

- [ ] Authentication Session Provider
- [ ] Login Failure Provider  
- [ ] Cluster Provider (distributed locking)
- [ ] Redis Pub/Sub for cluster events
- [ ] Secondary indices using Redis Sorted Sets
- [ ] Connection pooling optimization

## Dependencies

- **Lettuce**: Redis client library (6.3.2.RELEASE)
- **Reactor Core**: For async operations
- **Jackson**: JSON serialization

## License

Apache License 2.0
