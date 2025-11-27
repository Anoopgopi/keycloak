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

package org.keycloak.connections.redis.crossdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.XAddArgs;
import io.lettuce.core.XReadArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Redis-based cross-datacenter synchronization provider.
 * <p>
 * Implements active-active cache synchronization using Redis Streams
 * for reliable message delivery across datacenters.
 * <p>
 * Features:
 * <ul>
 *   <li>Redis Streams for ordered, persistent event delivery</li>
 *   <li>Circuit breaker for each remote site</li>
 *   <li>Async replication with retry queue</li>
 *   <li>Event deduplication</li>
 *   <li>Configurable consistency modes</li>
 * </ul>
 *
 * @author Keycloak Team
 */
public class RedisCrossDCProvider implements Closeable {

    private static final Logger logger = Logger.getLogger(RedisCrossDCProvider.class);

    // Stream key for cross-DC events
    private static final String CROSS_DC_STREAM_KEY = "kc:crossdc:events";
    private static final String CONSUMER_GROUP = "keycloak-nodes";

    private final CrossDCConfig config;
    private final ObjectMapper objectMapper;
    private final Map<String, RemoteSiteConnection> remoteSites;
    private final Map<String, CircuitBreaker> circuitBreakers;
    private final LinkedBlockingQueue<CrossDCInvalidationEvent> retryQueue;
    private final Set<String> processedEventIds;

    private final ExecutorService replicationExecutor;
    private final ScheduledExecutorService retryExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Consumer<CrossDCInvalidationEvent> eventHandler;

    public RedisCrossDCProvider(CrossDCConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.remoteSites = new ConcurrentHashMap<>();
        this.circuitBreakers = new ConcurrentHashMap<>();
        this.retryQueue = new LinkedBlockingQueue<>(10000);
        this.processedEventIds = ConcurrentHashMap.newKeySet();

        this.replicationExecutor = Executors.newFixedThreadPool(
                Math.max(2, config.getRemoteSites().size()),
                r -> {
                    Thread t = new Thread(r, "crossdc-replication");
                    t.setDaemon(true);
                    return t;
                }
        );

        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "crossdc-retry");
            t.setDaemon(true);
            return t;
        });

        initializeRemoteSites();
    }

    private void initializeRemoteSites() {
        for (CrossDCConfig.RemoteSiteConfig siteConfig : config.getRemoteSites()) {
            try {
                RemoteSiteConnection connection = new RemoteSiteConnection(siteConfig);
                remoteSites.put(siteConfig.getSiteName(), connection);

                CircuitBreaker circuitBreaker = new CircuitBreaker(
                        siteConfig.getSiteName(),
                        config.getCircuitBreakerThreshold(),
                        config.getCircuitBreakerResetMs()
                );
                circuitBreakers.put(siteConfig.getSiteName(), circuitBreaker);

                logger.infof("Initialized remote site connection: %s -> %s:%d",
                        siteConfig.getSiteName(), siteConfig.getHost(), siteConfig.getPort());
            } catch (Exception e) {
                logger.errorf(e, "Failed to initialize remote site: %s", siteConfig.getSiteName());
            }
        }
    }

    /**
     * Start the cross-DC provider.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Start retry processor
            if (config.getReplicationMode() == CrossDCConfig.ReplicationMode.ASYNC_WITH_RETRY) {
                retryExecutor.scheduleWithFixedDelay(
                        this::processRetryQueue,
                        config.getRetryDelayMs(),
                        config.getRetryDelayMs(),
                        TimeUnit.MILLISECONDS
                );
            }

            // Cleanup old processed event IDs periodically
            retryExecutor.scheduleAtFixedRate(
                    () -> {
                        if (processedEventIds.size() > 100000) {
                            processedEventIds.clear();
                        }
                    },
                    1, 1, TimeUnit.HOURS
            );

            logger.info("Cross-DC provider started");
        }
    }

    /**
     * Register a handler for incoming cross-DC events.
     */
    public void setEventHandler(Consumer<CrossDCInvalidationEvent> handler) {
        this.eventHandler = handler;
    }

    /**
     * Send an invalidation event to all remote sites.
     *
     * @param event the invalidation event
     */
    public void sendInvalidation(CrossDCInvalidationEvent event) {
        if (!config.isEnabled() || remoteSites.isEmpty()) {
            return;
        }

        switch (config.getReplicationMode()) {
            case SYNC:
                sendSync(event);
                break;
            case ASYNC:
                sendAsync(event);
                break;
            case ASYNC_WITH_RETRY:
                sendAsyncWithRetry(event);
                break;
        }
    }

    private void sendSync(CrossDCInvalidationEvent event) {
        for (Map.Entry<String, RemoteSiteConnection> entry : remoteSites.entrySet()) {
            String siteName = entry.getKey();
            CircuitBreaker cb = circuitBreakers.get(siteName);

            cb.execute(
                    () -> {
                        sendToSite(entry.getValue(), event);
                        return null;
                    },
                    () -> {
                        logger.warnf("Sync replication to site '%s' failed, event queued", siteName);
                        retryQueue.offer(event);
                        return null;
                    }
            );
        }
    }

    private void sendAsync(CrossDCInvalidationEvent event) {
        for (Map.Entry<String, RemoteSiteConnection> entry : remoteSites.entrySet()) {
            String siteName = entry.getKey();
            CircuitBreaker cb = circuitBreakers.get(siteName);

            replicationExecutor.submit(() -> {
                cb.execute(
                        () -> {
                            sendToSite(entry.getValue(), event);
                        },
                        () -> {
                            logger.debugf("Async replication to site '%s' skipped (circuit open)", siteName);
                        }
                );
            });
        }
    }

    private void sendAsyncWithRetry(CrossDCInvalidationEvent event) {
        for (Map.Entry<String, RemoteSiteConnection> entry : remoteSites.entrySet()) {
            String siteName = entry.getKey();
            CircuitBreaker cb = circuitBreakers.get(siteName);

            replicationExecutor.submit(() -> {
                cb.execute(
                        () -> {
                            sendToSite(entry.getValue(), event);
                        },
                        () -> {
                            // Queue for retry
                            if (!retryQueue.offer(event)) {
                                logger.warnf("Retry queue full, dropping event: %s", event.getEventId());
                            }
                        }
                );
            });
        }
    }

    private void sendToSite(RemoteSiteConnection site, CrossDCInvalidationEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);

            // Use Redis Streams for reliable delivery
            RedisCommands<String, String> commands = site.getCommands();
            commands.xadd(
                    CROSS_DC_STREAM_KEY,
                    XAddArgs.Builder.maxlen(100000).approximateTrimming(),
                    Map.of(
                            "event", json,
                            "source", config.getLocalSiteName(),
                            "timestamp", String.valueOf(event.getTimestamp())
                    )
            );

            if (logger.isTraceEnabled()) {
                logger.tracef("Sent event to site '%s': %s", site.getSiteName(), event.getEventId());
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event", e);
        }
    }

    private void processRetryQueue() {
        if (retryQueue.isEmpty()) {
            return;
        }

        int processed = 0;
        int maxBatch = 100;

        while (!retryQueue.isEmpty() && processed < maxBatch) {
            CrossDCInvalidationEvent event = retryQueue.poll();
            if (event == null) {
                break;
            }

            // Skip expired events
            if (event.isExpired(60000)) {
                logger.debugf("Dropping expired retry event: %s", event.getEventId());
                continue;
            }

            // Retry sending to all sites
            for (Map.Entry<String, RemoteSiteConnection> entry : remoteSites.entrySet()) {
                CircuitBreaker cb = circuitBreakers.get(entry.getKey());
                if (cb.getState() == CircuitBreaker.State.CLOSED) {
                    try {
                        sendToSite(entry.getValue(), event);
                    } catch (Exception e) {
                        cb.recordFailure();
                        // Re-queue if still failing
                        retryQueue.offer(event);
                    }
                }
            }
            processed++;
        }

        if (processed > 0) {
            logger.debugf("Processed %d events from retry queue", processed);
        }
    }

    /**
     * Handle an incoming event from a remote site.
     */
    public void handleIncomingEvent(CrossDCInvalidationEvent event) {
        // Skip events from this site
        if (event.isFromSite(config.getLocalSiteName())) {
            return;
        }

        // Deduplication
        if (!processedEventIds.add(event.getEventId())) {
            logger.tracef("Skipping duplicate event: %s", event.getEventId());
            return;
        }

        // Skip expired events
        if (event.isExpired(config.getSyncTimeoutMs() * 2)) {
            logger.debugf("Skipping expired event: %s", event.getEventId());
            return;
        }

        if (eventHandler != null) {
            try {
                eventHandler.accept(event);
                logger.debugf("Processed cross-DC event: %s from site %s",
                        event.getEventId(), event.getSourceSite());
            } catch (Exception e) {
                logger.errorf(e, "Failed to process cross-DC event: %s", event.getEventId());
            }
        }
    }

    /**
     * Send a user cache invalidation across sites.
     */
    public void sendUserInvalidation(String realmId, Set<String> invalidationKeys) {
        CrossDCInvalidationEvent event = CrossDCInvalidationEvent.create(
                config.getLocalSiteName(),
                CrossDCInvalidationEvent.EventType.USER_UPDATED,
                "users",
                invalidationKeys,
                realmId
        );
        sendInvalidation(event);
    }

    /**
     * Send a cache clear event across sites.
     */
    public void sendCacheClear(String cacheName) {
        CrossDCInvalidationEvent event = CrossDCInvalidationEvent.createClearEvent(
                config.getLocalSiteName(),
                cacheName
        );
        sendInvalidation(event);
    }

    /**
     * Send a realm invalidation across sites.
     */
    public void sendRealmInvalidation(String cacheName, String realmId) {
        CrossDCInvalidationEvent event = CrossDCInvalidationEvent.createRealmEvent(
                config.getLocalSiteName(),
                cacheName,
                realmId
        );
        sendInvalidation(event);
    }

    /**
     * Get circuit breaker stats for all remote sites.
     */
    public Map<String, CircuitBreaker.CircuitBreakerStats> getCircuitBreakerStats() {
        Map<String, CircuitBreaker.CircuitBreakerStats> stats = new ConcurrentHashMap<>();
        for (Map.Entry<String, CircuitBreaker> entry : circuitBreakers.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getStats());
        }
        return stats;
    }

    /**
     * Get the retry queue size.
     */
    public int getRetryQueueSize() {
        return retryQueue.size();
    }

    /**
     * Check if cross-DC is enabled and has remote sites.
     */
    public boolean isActive() {
        return config.isEnabled() && !remoteSites.isEmpty();
    }

    @Override
    public void close() {
        running.set(false);

        replicationExecutor.shutdown();
        retryExecutor.shutdown();

        try {
            replicationExecutor.awaitTermination(5, TimeUnit.SECONDS);
            retryExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (RemoteSiteConnection connection : remoteSites.values()) {
            connection.close();
        }

        remoteSites.clear();
        circuitBreakers.clear();

        logger.info("Cross-DC provider closed");
    }

    /**
     * Connection wrapper for a remote site.
     */
    private static class RemoteSiteConnection implements Closeable {
        private final CrossDCConfig.RemoteSiteConfig config;
        private final RedisClient client;
        private final StatefulRedisConnection<String, String> connection;

        RemoteSiteConnection(CrossDCConfig.RemoteSiteConfig config) {
            this.config = config;

            RedisURI.Builder uriBuilder = RedisURI.builder()
                    .withHost(config.getHost())
                    .withPort(config.getPort())
                    .withDatabase(config.getDatabase())
                    .withTimeout(Duration.ofSeconds(5));

            if (config.getPassword() != null && !config.getPassword().isEmpty()) {
                uriBuilder.withPassword(config.getPassword().toCharArray());
            }
            if (config.isSsl()) {
                uriBuilder.withSsl(true);
            }

            this.client = RedisClient.create(uriBuilder.build());
            this.connection = client.connect();
        }

        String getSiteName() {
            return config.getSiteName();
        }

        RedisCommands<String, String> getCommands() {
            return connection.sync();
        }

        @Override
        public void close() {
            try {
                connection.close();
                client.shutdown();
            } catch (Exception e) {
                logger.warnf(e, "Error closing connection to site: %s", config.getSiteName());
            }
        }
    }
}
