# Keycloak Redis Cache Module

This module provides Redis-based cache implementations for Keycloak using the [Lettuce](https://lettuce.io/) Redis client.

## Overview

The Redis cache module is an alternative to the default Infinispan cache implementation. It provides:

- **User Cache**: Caches user data to reduce database lookups
- **Cluster-wide Invalidation**: Uses Keycloak's ClusterProvider for cross-node cache invalidation
- **Redis Standalone and Cluster Support**: Works with both single Redis instances and Redis Cluster
- **SSL/TLS Support**: Secure connections to Redis

## Configuration

### Enable Redis Cache

To enable the Redis user cache provider, add the following to your Keycloak configuration:

```properties
# Enable Redis user cache provider
spi-user-cache-provider=redis

# Redis connection settings
spi-connections-redis-default-host=localhost
spi-connections-redis-default-port=6379
spi-connections-redis-default-password=your-password
spi-connections-redis-default-database=0
spi-connections-redis-default-ssl=false
spi-connections-redis-default-cluster=false
spi-connections-redis-default-timeout=5000
spi-connections-redis-default-pool-size=8
```

### Environment Variables (Docker/Kubernetes)

```yaml
env:
  - name: KC_SPI_USER_CACHE_PROVIDER
    value: "redis"
  - name: KC_SPI_CONNECTIONS_REDIS_DEFAULT_HOST
    value: "redis.default.svc.cluster.local"
  - name: KC_SPI_CONNECTIONS_REDIS_DEFAULT_PORT
    value: "6379"
  - name: KC_SPI_CONNECTIONS_REDIS_DEFAULT_CLUSTER
    value: "true"
  - name: KC_SPI_CONNECTIONS_REDIS_DEFAULT_PASSWORD
    valueFrom:
      secretKeyRef:
        name: redis-credentials
        key: password
```

### Configuration Options

| Property | Description | Default |
|----------|-------------|---------|
| `host` | Redis server hostname | `localhost` |
| `port` | Redis server port | `6379` |
| `password` | Redis password (optional) | - |
| `database` | Redis database index (0-15) | `0` |
| `ssl` | Enable SSL/TLS | `false` |
| `cluster` | Enable Redis Cluster mode | `false` |
| `timeout` | Connection timeout (ms) | `5000` |
| `poolSize` | Number of I/O threads | `8` |

## Architecture

The Redis cache module mirrors the Infinispan cache architecture:

```
┌─────────────────────────────────────────────────────┐
│ RedisUserCacheProviderFactory                        │
│   └─ creates RedisUserCacheSession                  │
│        └─ uses RedisUserCacheManager                │
│             └─ uses RedisConnectionProvider         │
│                  └─ Lettuce RedisClient             │
└─────────────────────────────────────────────────────┘
```

### Key Components

- **RedisConnectionProvider**: Manages Redis connections via Lettuce
- **RedisCacheManager**: Base class implementing revision-based optimistic locking
- **RedisUserCacheManager**: User-specific cache operations and invalidation
- **RedisUserCacheSession**: UserCache implementation for Redis
- **JacksonRedisSerializer**: JSON serialization for cache entries

### Cache Key Structure

Keys are prefixed to namespace different cache types:

```
kc:users:{userId}           - User entity cache
kc:userRevisions:{userId}   - Revision tracking
kc:users:{realmId}.username.{username} - Username lookup
kc:users:{realmId}.email.{email}       - Email lookup
```

## Building

Build the module with Maven:

```bash
cd model/redis
mvn clean install
```

## Testing

Run tests with:

```bash
mvn test
```

Integration tests use Testcontainers to spin up a Redis container:

```bash
mvn verify -Pit-tests
```

## Comparison with Infinispan

| Feature | Infinispan | Redis |
|---------|------------|-------|
| Storage | Embedded/External | External only |
| Serialization | ProtoStream | Jackson JSON |
| Cluster Support | JGroups | Redis Cluster |
| Cloud Managed | Limited | AWS ElastiCache, Azure Cache, GCP Memorystore |
| Operational Complexity | Higher | Lower (managed services) |

## Limitations

- **No Query Support**: Unlike Infinispan, Redis doesn't support complex queries. User searches always go to the database.
- **External Dependency**: Requires a running Redis instance/cluster.
- **Serialization**: Uses JSON instead of ProtoStream, which may have different performance characteristics.

## Multi-Region / Cross-DC Support

The Redis cache module supports active-active multi-region deployments with automatic cache invalidation replication across datacenters.

### Configuration

```properties
# Enable cross-DC replication
spi-user-cache-redis-cross-dc-enabled=true
spi-user-cache-redis-cross-dc-site-name=site1

# Remote sites (format: name:host:port[:password:ssl:database])
spi-user-cache-redis-cross-dc-remote-sites=site2:redis-dc2.example.com:6379,site3:redis-dc3.example.com:6379:mypassword

# Replication mode: SYNC, ASYNC, or ASYNC_WITH_RETRY (default)
spi-user-cache-redis-cross-dc-replication-mode=ASYNC_WITH_RETRY

# Circuit breaker settings
spi-user-cache-redis-cross-dc-circuit-breaker-threshold=5
spi-user-cache-redis-cross-dc-circuit-breaker-reset=30000
```

### Replication Modes

| Mode | Description | Use Case |
|------|-------------|----------|
| `SYNC` | Wait for all sites to acknowledge | Strong consistency, higher latency |
| `ASYNC` | Fire and forget | Low latency, eventual consistency |
| `ASYNC_WITH_RETRY` | Async with retry queue | Balance of consistency and performance |

### Architecture

```
┌─────────────────────┐         ┌─────────────────────┐
│   Datacenter 1      │         │   Datacenter 2      │
│                     │         │                     │
│ ┌─────────────────┐ │  Redis  │ ┌─────────────────┐ │
│ │   Keycloak      │ │ Streams │ │   Keycloak      │ │
│ │   + Redis       │◄─────────►│   + Redis       │ │
│ │   Cache         │ │         │   Cache         │ │
│ └─────────────────┘ │         │ └─────────────────┘ │
│         │           │         │         │           │
│         ▼           │         │         ▼           │
│ ┌─────────────────┐ │         │ ┌─────────────────┐ │
│ │  Local Redis    │ │         │ │  Local Redis    │ │
│ └─────────────────┘ │         │ └─────────────────┘ │
└─────────────────────┘         └─────────────────────┘
```

### Features

- **Redis Streams**: Reliable, ordered event delivery across sites
- **Circuit Breaker**: Automatic failover when remote sites are unavailable
- **Retry Queue**: Failed events are queued and retried
- **Event Deduplication**: Prevents duplicate processing
- **Monitoring**: Circuit breaker stats exposed via operational info

### Circuit Breaker States

| State | Description |
|-------|-------------|
| `CLOSED` | Normal operation, events flow to remote site |
| `OPEN` | Site considered failed, events queued for retry |
| `HALF_OPEN` | Testing if site has recovered |

## Future Work

- [ ] Realm cache provider
- [ ] Session cache providers (user sessions, auth sessions)
- [ ] Login failure provider

## Contributing

See the main Keycloak [CONTRIBUTING.md](../../CONTRIBUTING.md) for guidelines.

## License

Apache License 2.0
