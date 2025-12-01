# Keycloak Redis Cache Provider - Technical Documentation

## Table of Contents
1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Key Components](#key-components)
4. [Configuration Flow](#configuration-flow)
5. [Data Flow](#data-flow)
6. [Redis Cache Types](#redis-cache-types)
7. [Cross-Datacenter (Cross-DC) Support](#cross-datacenter-support)
8. [Comparison with Infinispan](#comparison-with-infinispan)
9. [Advantages](#advantages)
10. [Limitations](#limitations)
11. [Production Considerations](#production-considerations)
12. [Testing](#testing)

---

## 1. Overview

The Redis cache provider is a new module (`model/redis`) that implements Keycloak's caching layer using Redis instead of the embedded Infinispan cache. This enables:

- **External cache management** - Use managed Redis services (AWS ElastiCache, Azure Cache, GCP Memorystore)
- **Cross-datacenter replication** - Active-active cache invalidation across regions
- **Simplified infrastructure** - Single cache technology for all caching needs
- **Horizontal scalability** - Redis Cluster support for large-scale deployments

### Module Location

```
keycloak/
├── model/
│   ├── infinispan/          # Existing Infinispan implementation
│   ├── jpa/                 # JPA storage
│   └── redis/               # NEW: Redis cache implementation
│       ├── src/main/java/
│       │   └── org/keycloak/
│       │       ├── connections/redis/           # Connection providers
│       │       │   ├── crossdc/                 # Cross-DC components
│       │       │   └── *.java                   # Connection interfaces
│       │       └── models/cache/redis/          # Cache implementations
│       │           └── organization/            # Organization provider
│       ├── src/test/java/                       # Tests
│       ├── docker/                              # Docker setup for testing
│       └── pom.xml
```

---

## 2. Architecture

### High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Keycloak Server                                │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌──────────────────┐    ┌───────────────────┐  │
│  │  UserProvider   │───▶│ RedisUserCache   │───▶│ RedisUserCache    │  │
│  │  (JPA/Storage)  │    │ Session          │    │ Manager           │  │
│  └─────────────────┘    └──────────────────┘    └─────────┬─────────┘  │
│                                                            │            │
│  ┌─────────────────────────────────────────────────────────┼──────────┐│
│  │                  RedisConnectionProvider                 │          ││
│  │  ┌─────────────────────────────────────────────────────┼────────┐ ││
│  │  │                    Lettuce Client                    ▼        │ ││
│  │  │  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐  │ ││
│  │  │  │ Standalone   │  │   Cluster    │  │  Cross-DC Provider │  │ ││
│  │  │  │ Connection   │  │  Connection  │  │  (Redis Streams)   │  │ ││
│  │  │  └──────┬───────┘  └──────┬───────┘  └─────────┬──────────┘  │ ││
│  │  └─────────┼─────────────────┼────────────────────┼─────────────┘ ││
│  └────────────┼─────────────────┼────────────────────┼───────────────┘│
└───────────────┼─────────────────┼────────────────────┼────────────────┘
                │                 │                    │
                ▼                 ▼                    ▼
         ┌──────────┐      ┌──────────┐         ┌──────────┐
         │  Redis   │      │  Redis   │         │  Remote  │
         │Standalone│      │ Cluster  │         │  Redis   │
         └──────────┘      └──────────┘         │ (Site 2) │
                                                └──────────┘
```

### Component Relationships

```
RedisConnectionProviderSpi
        │
        ▼
RedisConnectionProviderFactory (default)
        │
        ▼
RedisConnectionProvider ◄──────────────────┐
        │                                   │
        ├──▶ RedisCommands<String,byte[]>   │ (cache operations)
        │                                   │
        └──▶ RedisCommands<String,String>   │ (revision operations)
                                            │
RedisUserCacheProviderFactory ──────────────┤
        │                                   │
        ▼                                   │
RedisUserCacheManager ──────────────────────┘
        │
        ▼
RedisUserCacheSession (per-request)
        │
        ├──▶ Delegates to UserProvider (JPA)
        └──▶ Manages invalidations
```

---

## 3. Key Components

### 3.1 Connection Layer

#### `RedisConnectionProvider` (Interface)
```java
public interface RedisConnectionProvider extends Provider {
    // Cache name constants (mirrors Infinispan naming)
    String USER_CACHE_NAME = "users";
    String USER_REVISIONS_CACHE_NAME = "userRevisions";
    // ... more cache names
    
    // Get Redis commands for cache operations
    RedisCommands<String, byte[]> getCache(String cacheName);
    RedisCommands<String, String> getRevisionsCache(String cacheName);
    
    // Cluster support
    boolean isClusterMode();
    RedisClusterCommands<String, byte[]> getClusterCommands();
    
    // Operational
    boolean isHealthy();
    String getCacheKeyPrefix(String cacheName);
}
```

#### `DefaultRedisConnectionProviderFactory`
Manages Redis connections using Lettuce client:

| Feature | Implementation |
|---------|---------------|
| **Standalone Mode** | `RedisClient.create()` with auto-reconnect |
| **Cluster Mode** | `RedisClusterClient` with topology refresh |
| **Connection Pooling** | Configurable I/O thread pool |
| **SSL/TLS** | Optional encrypted connections |
| **Codecs** | `StringCodec` + `ByteArrayCodec` for different value types |

### 3.2 Cache Layer

#### `RedisCacheManager` (Abstract Base)
Core caching logic with revision-based optimistic locking:

```java
public abstract class RedisCacheManager {
    // Revision tracking (similar to Infinispan's UpdateCounter)
    protected final AtomicLong localCounter;
    
    // Key pattern: kc:{cacheName}:{id}
    protected final String cacheKeyPrefix;
    
    // Core operations
    public <T extends Revisioned> T get(String id, Class<T> type);
    public Object invalidateObject(String id);
    public void addRevisioned(Revisioned object, long startupRevision, long lifespanMs);
    public void clear();
}
```

**Optimistic Locking Flow:**
```
1. Transaction starts → capture startupRevision
2. Read object → check object.revision vs cache revision
3. Write object → skip if revision advanced (stale write)
4. Commit → bump revision for invalidated entries
```

#### `RedisUserCacheManager`
Extends `RedisCacheManager` for user-specific operations:

```java
public class RedisUserCacheManager extends RedisCacheManager {
    // Cross-DC support
    private RedisCrossDCProvider crossDCProvider;
    
    // User invalidation patterns
    public void userUpdatedInvalidations(String id, String username, String email, 
                                          String realmId, Set<String> invalidations);
    public void fullUserInvalidation(String id, String realmId, 
                                      String username, String email, 
                                      Set<String> invalidations);
    public void invalidateRealmUsers(String realmId, Set<String> invalidations);
}
```

#### `RedisUserCacheSession`
Per-request cache session implementing `UserCache`:

```java
public class RedisUserCacheSession implements UserCache {
    protected RedisUserCacheManager cache;
    protected UserProvider delegate;  // JPA provider
    protected Set<String> invalidations = new HashSet<>();
    protected Map<String, UserModel> managedUsers = new HashMap<>();
    
    // Transaction integration
    session.getTransactionManager().enlistAfterCompletion(getTransaction());
}
```

**Cache Key Patterns:**
| Key Pattern | Purpose |
|-------------|---------|
| `kc:users:{userId}` | User entity by ID |
| `kc:users:{realmId}.username.{username}` | Username lookup |
| `kc:users:{realmId}.email.{email}` | Email lookup |
| `kc:users:{realmId}.idp.{provider}.{socialId}` | Federated identity lookup |
| `kc:userRevisions:{id}` | Revision counter for each entry |

### 3.3 Serialization

#### `RedisSerializer` (Interface)
```java
public interface RedisSerializer {
    byte[] serialize(Object object) throws RedisSerializationException;
    <T> T deserialize(byte[] data, Class<T> type) throws RedisSerializationException;
}
```

#### `JacksonRedisSerializer`
JSON-based serialization using Jackson:
- Handles polymorphic types with `@JsonTypeInfo`
- Configurable for different object types
- Human-readable cache values (debugging friendly)

### 3.4 Cross-DC Components

#### `CrossDCConfig`
Configuration for multi-region deployments:

```java
public class CrossDCConfig {
    private boolean enabled;
    private String localSiteName;
    private List<RemoteSiteConfig> remoteSites;
    private ReplicationMode replicationMode;  // SYNC, ASYNC, ASYNC_WITH_RETRY
    private int circuitBreakerThreshold;
    private long circuitBreakerResetMs;
}
```

#### `RedisCrossDCProvider`
Handles cross-DC cache invalidation:

```java
public class RedisCrossDCProvider {
    // Uses Redis Streams for reliable delivery
    private static final String CROSS_DC_STREAM_KEY = "kc:crossdc:events";
    
    // One connection per remote site
    private Map<String, RemoteSiteConnection> remoteSites;
    
    // Circuit breaker per site
    private Map<String, CircuitBreaker> circuitBreakers;
    
    // Retry queue for failed events
    private LinkedBlockingQueue<CrossDCInvalidationEvent> retryQueue;
}
```

#### `CircuitBreaker`
Prevents cascading failures:

```
State Machine:
    CLOSED ──(failures >= threshold)──▶ OPEN
       ▲                                   │
       │                                   │
       │                              (timeout)
       │                                   │
       │                                   ▼
       └───(3 successes)─────────── HALF_OPEN
                                           │
                                    (failure)
                                           │
                                           ▼
                                        OPEN
```

---

## 4. Configuration Flow

### SPI Registration

```
META-INF/services/org.keycloak.provider.Spi
└── org.keycloak.connections.redis.RedisConnectionProviderSpi

META-INF/services/org.keycloak.connections.redis.RedisConnectionProviderFactory
└── org.keycloak.connections.redis.DefaultRedisConnectionProviderFactory

META-INF/services/org.keycloak.models.cache.UserCacheProviderFactory
└── org.keycloak.models.cache.redis.RedisUserCacheProviderFactory

META-INF/services/org.keycloak.organization.OrganizationProviderFactory
└── org.keycloak.models.cache.redis.organization.RedisOrganizationProviderFactory
```

### Configuration Options

**Basic Redis Connection:**
```bash
# Standalone Redis
--spi-connections-redis-default-host=localhost
--spi-connections-redis-default-port=6379
--spi-connections-redis-default-password=secret
--spi-connections-redis-default-database=0
--spi-connections-redis-default-ssl=false
--spi-connections-redis-default-timeout=5000

# Redis Cluster
--spi-connections-redis-default-cluster=true
```

**Enable Redis User Cache:**
```bash
--spi-user-cache-provider=redis
```

**Cross-DC Configuration:**
```bash
--spi-user-cache-redis-cross-dc-enabled=true
--spi-user-cache-redis-cross-dc-site-name=site1
--spi-user-cache-redis-cross-dc-remote-sites=site2:redis-site2.example.com:6379
--spi-user-cache-redis-cross-dc-replication-mode=ASYNC_WITH_RETRY
--spi-user-cache-redis-cross-dc-circuit-breaker-threshold=5
--spi-user-cache-redis-cross-dc-circuit-breaker-reset=30000
```

---

## 5. Data Flow

### User Lookup Flow

```
┌─────────┐     ┌──────────────────┐     ┌─────────────────┐     ┌─────────┐
│ Request │────▶│ RedisUserCache   │────▶│ RedisCacheManager│────▶│  Redis  │
│         │     │ Session          │     │ .get()           │     │         │
└─────────┘     └──────────────────┘     └─────────────────┘     └────┬────┘
                         │                                             │
                         │ (cache miss)                          (cache hit)
                         ▼                                             │
                ┌──────────────────┐                                   │
                │ UserProvider     │                                   │
                │ (JPA Delegate)   │                                   │
                └────────┬─────────┘                                   │
                         │                                             │
                         ▼                                             │
                ┌──────────────────┐                                   │
                │   Database       │                                   │
                └────────┬─────────┘                                   │
                         │                                             │
                         ▼                                             ▼
                ┌──────────────────────────────────────────────────────────┐
                │                     Return UserModel                     │
                └──────────────────────────────────────────────────────────┘
```

### Invalidation Flow

```
┌─────────────┐
│ User Update │
└──────┬──────┘
       │
       ▼
┌──────────────────┐
│ RedisUserCache   │
│ Session.evict()  │
└────────┬─────────┘
         │
         ├──▶ Add to local invalidations set
         │
         ▼
┌──────────────────┐
│ Transaction      │
│ Commit           │
└────────┬─────────┘
         │
         ├──▶ runInvalidations()
         │    ├── Delete from Redis
         │    ├── Bump revision counter
         │    └── Send cluster event (ClusterProvider)
         │
         ▼
┌──────────────────┐
│ Cross-DC         │ (if enabled)
│ Provider         │
└────────┬─────────┘
         │
         ├──▶ Send to Remote Site 1
         │    └── Redis Streams: XADD kc:crossdc:events
         │
         └──▶ Send to Remote Site 2
              └── Redis Streams: XADD kc:crossdc:events
```

### Cross-DC Event Flow

```
Site 1 (us-east)                          Site 2 (us-west)
┌─────────────┐                           ┌─────────────┐
│ User Update │                           │             │
└──────┬──────┘                           │             │
       │                                  │             │
       ▼                                  │             │
┌──────────────┐                          │             │
│ Invalidate   │                          │             │
│ Local Cache  │                          │             │
└──────┬───────┘                          │             │
       │                                  │             │
       ▼                                  │             │
┌──────────────┐    Redis Streams         │             │
│ CrossDC      │─────────────────────────▶│ CrossDC     │
│ Provider     │    XADD event            │ Listener    │
└──────────────┘                          └──────┬──────┘
                                                 │
                                                 ▼
                                          ┌──────────────┐
                                          │ Invalidate   │
                                          │ Local Cache  │
                                          └──────────────┘
```

---

## 6. Redis Cache Types

### Cache Key Naming Convention

All keys use prefix: `kc:{cacheName}:{specificKey}`

| Cache | Key Pattern | TTL | Purpose |
|-------|-------------|-----|---------|
| **users** | `kc:users:{id}` | Configurable | Cached user entities |
| **userRevisions** | `kc:userRevisions:{id}` | Infinite | Revision counters |
| **realms** | `kc:realms:{id}` | Configurable | Cached realm entities |
| **realmRevisions** | `kc:realmRevisions:{id}` | Infinite | Realm revision counters |
| **sessions** | `kc:sessions:{id}` | Session timeout | User sessions |
| **authenticationSessions** | `kc:authenticationSessions:{id}` | Auth flow timeout | Auth sessions |
| **crossdc:events** | `kc:crossdc:events` (Stream) | Auto-trimmed | Cross-DC invalidation events |

### Redis Data Structures Used

| Structure | Usage |
|-----------|-------|
| **STRING** | Serialized entity storage (SET/GET) |
| **STREAM** | Cross-DC event queue (XADD/XREAD) |
| **Key TTL** | PSETEX for expiring entries |

---

## 7. Cross-Datacenter Support

### Replication Modes

| Mode | Behavior | Use Case |
|------|----------|----------|
| **SYNC** | Wait for all sites to ACK | Strong consistency, higher latency |
| **ASYNC** | Fire and forget | Low latency, eventual consistency |
| **ASYNC_WITH_RETRY** | Async with retry queue | Balanced (recommended) |

### Event Types

```java
public enum EventType {
    USER_UPDATED,       // User entity changed
    USER_REMOVED,       // User deleted
    REALM_UPDATED,      // Realm configuration changed
    REALM_REMOVED,      // Realm deleted
    CACHE_CLEAR,        // Full cache clear
    CUSTOM              // Extension point
}
```

### Redis Streams Message Format

```json
{
  "eventId": "uuid-v4",
  "sourceSite": "site1",
  "eventType": "USER_UPDATED",
  "cacheName": "users",
  "invalidationKeys": ["user-123", "realm1.username.john"],
  "realmId": "realm1",
  "timestamp": 1701234567890
}
```

---

## 8. Comparison with Infinispan

| Aspect | Infinispan (Current) | Redis (New) |
|--------|---------------------|-------------|
| **Deployment** | Embedded or external | External only |
| **Protocol** | Hot Rod / JGroups | RESP (Redis protocol) |
| **Clustering** | JGroups multicast | Redis Cluster / Sentinel |
| **Cross-DC** | Infinispan XSite | Redis Streams |
| **Managed Services** | Limited | AWS, Azure, GCP native |
| **Session Storage** | Built-in | Future implementation |
| **Serialization** | ProtoStream | JSON (Jackson) |

### Code Differences

**Infinispan Cache Access:**
```java
Cache<String, Object> cache = cacheManager.getCache("users");
cache.put(key, value);
Object result = cache.get(key);
```

**Redis Cache Access:**
```java
RedisCommands<String, byte[]> commands = provider.getCache("users");
commands.set(key, serializer.serialize(value));
byte[] data = commands.get(key);
Object result = serializer.deserialize(data, type);
```

---

## 9. Advantages

### Operational Benefits

1. **Managed Service Compatibility**
   - AWS ElastiCache
   - Azure Cache for Redis
   - GCP Memorystore
   - No Infinispan expertise required

2. **Simplified Multi-Region**
   - Native Redis replication
   - Redis Streams for reliable messaging
   - Circuit breakers prevent cascading failures

3. **Observability**
   - Standard Redis monitoring tools
   - MONITOR command for debugging
   - Prometheus/Grafana integration

### Technical Benefits

1. **Horizontal Scalability**
   - Redis Cluster for sharding
   - Read replicas for scaling reads
   - Automatic failover

2. **Memory Efficiency**
   - Redis memory optimization
   - Configurable eviction policies
   - No JVM heap pressure

3. **Standardization**
   - RESP protocol is widely supported
   - Language-agnostic protocol
   - Extensive tooling ecosystem

---

## 10. Limitations

### Current Implementation

1. **User Cache Only** - Realm, client, and session caches not yet implemented
2. **Simplified Caching** - Full `CachedUser` serialization pending (delegates to DB)
3. **No Session Storage** - User/auth sessions still use Infinispan
4. **Single Cluster** - Multi-cluster Redis support not implemented

### Architectural Constraints

1. **Network Latency** - External cache adds network hop vs embedded
2. **Serialization Overhead** - JSON larger than binary formats
3. **No Transactions** - Redis transactions differ from Infinispan's
4. **Consistency Model** - Eventually consistent in cross-DC mode

---

## 11. Production Considerations

### Redis Deployment

```yaml
# Recommended Production Setup
redis:
  mode: cluster  # or sentinel for HA
  nodes: 6       # 3 masters + 3 replicas
  maxmemory: 4gb
  maxmemory-policy: volatile-lru
  
  # Persistence (optional for cache)
  appendonly: no
  save: ""
  
  # Security
  requirepass: strong-password
  tls: enabled
```

### Keycloak Configuration

```bash
# Production settings
--spi-connections-redis-default-cluster=true
--spi-connections-redis-default-host=redis-cluster.internal
--spi-connections-redis-default-port=6379
--spi-connections-redis-default-password=${REDIS_PASSWORD}
--spi-connections-redis-default-ssl=true
--spi-connections-redis-default-timeout=5000
--spi-connections-redis-default-pool-size=16

--spi-user-cache-provider=redis
--spi-user-cache-redis-cross-dc-enabled=true
--spi-user-cache-redis-cross-dc-site-name=${SITE_NAME}
--spi-user-cache-redis-cross-dc-remote-sites=${REMOTE_SITES}
```

### Monitoring

**Key Metrics to Monitor:**
| Metric | Alert Threshold | Description |
|--------|-----------------|-------------|
| `redis_connected_clients` | > 1000 | Connection count |
| `redis_memory_used_bytes` | > 80% of max | Memory pressure |
| `redis_keyspace_hits_ratio` | < 90% | Cache effectiveness |
| `crossdc_circuit_breaker_state` | OPEN | Site unreachable |
| `crossdc_retry_queue_size` | > 1000 | Replication backlog |

### Failure Handling

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Failure Scenario Matrix                          │
├─────────────────────┬───────────────────────────────────────────────┤
│ Failure             │ Behavior                                      │
├─────────────────────┼───────────────────────────────────────────────┤
│ Local Redis down    │ Fallback to DB (increased latency)           │
│ Remote site down    │ Circuit breaker opens, events queued         │
│ Network partition   │ Sites operate independently, reconcile later │
│ Redis Cluster split │ Automatic failover to available nodes        │
└─────────────────────┴───────────────────────────────────────────────┘
```

---

## 12. Testing

### Local Development Setup

```bash
# Start Redis containers
cd model/redis/docker
docker-compose up -d

# Verify Redis
redis-cli -p 6379 PING
redis-cli -p 6380 PING

# Build and test
./mvnw -f model/redis/pom.xml clean test
```

### Integration Tests

```java
// RedisCacheManagerIntegrationTest.java
@Test
public void testCacheOperations() {
    // Test put/get with revision tracking
    cacheManager.addRevisioned(cachedUser, startupRevision);
    CachedUser retrieved = cacheManager.get(userId, CachedUser.class);
    assertNotNull(retrieved);
}

@Test
public void testInvalidation() {
    cacheManager.invalidateObject(userId);
    CachedUser retrieved = cacheManager.get(userId, CachedUser.class);
    assertNull(retrieved);
}
```

### Cross-DC Testing

```bash
# Start Site 1
KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
./bin/kc.sh start-dev --http-port=8080 \
  --spi-connections-redis-default-port=6379 \
  --spi-user-cache-provider=redis \
  --spi-user-cache-redis-cross-dc-enabled=true \
  --spi-user-cache-redis-cross-dc-site-name=site1 \
  --spi-user-cache-redis-cross-dc-remote-sites=site2:localhost:6380

# Start Site 2 (separate terminal)
KC_BOOTSTRAP_ADMIN_USERNAME=admin KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
./bin/kc.sh start-dev --http-port=8180 \
  --spi-connections-redis-default-port=6380 \
  --spi-user-cache-provider=redis \
  --spi-user-cache-redis-cross-dc-enabled=true \
  --spi-user-cache-redis-cross-dc-site-name=site2 \
  --spi-user-cache-redis-cross-dc-remote-sites=site1:localhost:6379

# Monitor cross-DC events
redis-cli -p 6379 XRANGE kc:crossdc:events - +
redis-cli -p 6380 XRANGE kc:crossdc:events - +
```

---

## Appendix: File Reference

| File | Purpose |
|------|---------|
| `RedisConnectionProvider.java` | SPI interface for Redis connections |
| `DefaultRedisConnectionProviderFactory.java` | Lettuce-based connection factory |
| `RedisCacheManager.java` | Abstract cache manager with revision logic |
| `RedisUserCacheManager.java` | User-specific cache operations |
| `RedisUserCacheSession.java` | Per-request cache session |
| `RedisUserCacheProviderFactory.java` | Factory with cluster listener registration |
| `RedisCrossDCProvider.java` | Cross-DC event propagation |
| `CrossDCConfig.java` | Cross-DC configuration model |
| `CircuitBreaker.java` | Failure handling for remote sites |
| `CrossDCInvalidationEvent.java` | Event model for cross-DC |
| `JacksonRedisSerializer.java` | JSON serialization |
| `RedisOrganizationProvider.java` | Organization provider (avoids Infinispan conflicts) |

---

*Last Updated: December 2024*  
*Module Version: 999.0.0-SNAPSHOT*
