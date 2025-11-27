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

import org.jboss.logging.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Circuit breaker implementation for cross-DC communication.
 * <p>
 * Prevents cascading failures when a remote site is unavailable by
 * temporarily stopping requests to the failed site.
 * <p>
 * States:
 * <ul>
 *   <li>CLOSED - Normal operation, requests flow through</li>
 *   <li>OPEN - Site is considered failed, requests are rejected</li>
 *   <li>HALF_OPEN - Testing if site has recovered</li>
 * </ul>
 *
 * @author Keycloak Team
 */
public class CircuitBreaker {

    private static final Logger logger = Logger.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String siteName;
    private final int failureThreshold;
    private final long resetTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    // Stats
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    public CircuitBreaker(String siteName, int failureThreshold, long resetTimeoutMs) {
        this.siteName = siteName;
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    /**
     * Execute an operation through the circuit breaker.
     *
     * @param operation the operation to execute
     * @param fallback  the fallback to use if circuit is open
     * @param <T>       the return type
     * @return the result of the operation or fallback
     */
    public <T> T execute(Supplier<T> operation, Supplier<T> fallback) {
        totalRequests.incrementAndGet();

        if (!allowRequest()) {
            rejectedRequests.incrementAndGet();
            logger.debugf("Circuit breaker OPEN for site '%s', using fallback", siteName);
            return fallback.get();
        }

        try {
            T result = operation.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            logger.warnf(e, "Operation failed for site '%s', failure count: %d/%d",
                    siteName, failureCount.get(), failureThreshold);
            return fallback.get();
        }
    }

    /**
     * Execute a void operation through the circuit breaker.
     *
     * @param operation the operation to execute
     * @param fallback  the fallback to run if circuit is open
     */
    public void execute(Runnable operation, Runnable fallback) {
        execute(() -> {
            operation.run();
            return null;
        }, () -> {
            fallback.run();
            return null;
        });
    }

    /**
     * Check if a request should be allowed through.
     */
    public boolean allowRequest() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if we should transition to half-open
                if (System.currentTimeMillis() - openedAt.get() >= resetTimeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        logger.infof("Circuit breaker transitioning to HALF_OPEN for site '%s'", siteName);
                        successCount.set(0);
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                // Allow limited requests to test recovery
                return true;

            default:
                return false;
        }
    }

    /**
     * Record a successful operation.
     */
    public void recordSuccess() {
        successfulRequests.incrementAndGet();
        failureCount.set(0);

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            int successes = successCount.incrementAndGet();
            // Require multiple successes before closing
            if (successes >= 3) {
                if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                    logger.infof("Circuit breaker CLOSED for site '%s' after recovery", siteName);
                }
            }
        }
    }

    /**
     * Record a failed operation.
     */
    public void recordFailure() {
        failedRequests.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            // Immediate transition back to open on failure in half-open state
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                logger.warnf("Circuit breaker re-OPENED for site '%s' after failure in half-open state", siteName);
            }
        } else if (currentState == State.CLOSED) {
            int failures = failureCount.incrementAndGet();
            if (failures >= failureThreshold) {
                if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                    openedAt.set(System.currentTimeMillis());
                    logger.warnf("Circuit breaker OPENED for site '%s' after %d failures", siteName, failures);
                }
            }
        }
    }

    /**
     * Force the circuit to open.
     */
    public void forceOpen() {
        state.set(State.OPEN);
        openedAt.set(System.currentTimeMillis());
        logger.infof("Circuit breaker force-OPENED for site '%s'", siteName);
    }

    /**
     * Force the circuit to close.
     */
    public void forceClose() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        logger.infof("Circuit breaker force-CLOSED for site '%s'", siteName);
    }

    /**
     * Reset the circuit breaker to initial state.
     */
    public void reset() {
        state.set(State.CLOSED);
        failureCount.set(0);
        successCount.set(0);
        totalRequests.set(0);
        rejectedRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
    }

    // Getters for monitoring

    public String getSiteName() {
        return siteName;
    }

    public State getState() {
        return state.get();
    }

    public int getFailureCount() {
        return failureCount.get();
    }

    public long getLastFailureTime() {
        return lastFailureTime.get();
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    public long getRejectedRequests() {
        return rejectedRequests.get();
    }

    public long getSuccessfulRequests() {
        return successfulRequests.get();
    }

    public long getFailedRequests() {
        return failedRequests.get();
    }

    public CircuitBreakerStats getStats() {
        return new CircuitBreakerStats(
                siteName,
                state.get(),
                failureCount.get(),
                totalRequests.get(),
                rejectedRequests.get(),
                successfulRequests.get(),
                failedRequests.get()
        );
    }

    /**
     * Statistics snapshot for the circuit breaker.
     */
    public static class CircuitBreakerStats {
        public final String siteName;
        public final State state;
        public final int failureCount;
        public final long totalRequests;
        public final long rejectedRequests;
        public final long successfulRequests;
        public final long failedRequests;

        public CircuitBreakerStats(String siteName, State state, int failureCount,
                                   long totalRequests, long rejectedRequests,
                                   long successfulRequests, long failedRequests) {
            this.siteName = siteName;
            this.state = state;
            this.failureCount = failureCount;
            this.totalRequests = totalRequests;
            this.rejectedRequests = rejectedRequests;
            this.successfulRequests = successfulRequests;
            this.failedRequests = failedRequests;
        }

        @Override
        public String toString() {
            return String.format("CircuitBreaker[site=%s, state=%s, failures=%d, " +
                            "total=%d, rejected=%d, success=%d, failed=%d]",
                    siteName, state, failureCount, totalRequests,
                    rejectedRequests, successfulRequests, failedRequests);
        }
    }
}
