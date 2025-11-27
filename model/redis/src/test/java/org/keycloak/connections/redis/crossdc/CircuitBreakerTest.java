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

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Unit tests for CircuitBreaker.
 */
public class CircuitBreakerTest {

    private CircuitBreaker circuitBreaker;

    @Before
    public void setUp() {
        // Threshold of 3 failures, 100ms reset timeout for faster tests
        circuitBreaker = new CircuitBreaker("test-site", 3, 100);
    }

    @Test
    public void testInitialStateClosed() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @Test
    public void testSuccessKeepsCircuitClosed() {
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
    }

    @Test
    public void testFailuresOpenCircuit() {
        // Record failures up to threshold
        circuitBreaker.recordFailure();
        assertEquals(1, circuitBreaker.getFailureCount());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        assertEquals(2, circuitBreaker.getFailureCount());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());

        circuitBreaker.recordFailure();
        // Should now be open
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    public void testSuccessResetsFailureCount() {
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        assertEquals(2, circuitBreaker.getFailureCount());

        circuitBreaker.recordSuccess();
        assertEquals(0, circuitBreaker.getFailureCount());
    }

    @Test
    public void testOpenCircuitRejectsRequests() {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    public void testHalfOpenAfterTimeout() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        // Wait for reset timeout
        Thread.sleep(150);

        // Should transition to half-open on next request
        assertTrue(circuitBreaker.allowRequest());
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    public void testHalfOpenToClosedOnSuccesses() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        // Wait and transition to half-open
        Thread.sleep(150);
        circuitBreaker.allowRequest();
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Multiple successes should close the circuit
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();
        circuitBreaker.recordSuccess();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testHalfOpenToOpenOnFailure() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        // Wait and transition to half-open
        Thread.sleep(150);
        circuitBreaker.allowRequest();
        assertEquals(CircuitBreaker.State.HALF_OPEN, circuitBreaker.getState());

        // Failure should immediately reopen
        circuitBreaker.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }

    @Test
    public void testExecuteWithSuccess() {
        AtomicInteger callCount = new AtomicInteger(0);

        String result = circuitBreaker.execute(
                () -> {
                    callCount.incrementAndGet();
                    return "success";
                },
                () -> "fallback"
        );

        assertEquals("success", result);
        assertEquals(1, callCount.get());
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    public void testExecuteWithFailure() {
        AtomicInteger operationCalls = new AtomicInteger(0);
        AtomicInteger fallbackCalls = new AtomicInteger(0);

        String result = circuitBreaker.execute(
                () -> {
                    operationCalls.incrementAndGet();
                    throw new RuntimeException("Simulated failure");
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback";
                }
        );

        assertEquals("fallback", result);
        assertEquals(1, operationCalls.get());
        assertEquals(1, fallbackCalls.get());
        assertEquals(1, circuitBreaker.getFailureCount());
    }

    @Test
    public void testExecuteWithOpenCircuit() {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        AtomicInteger operationCalls = new AtomicInteger(0);
        AtomicInteger fallbackCalls = new AtomicInteger(0);

        String result = circuitBreaker.execute(
                () -> {
                    operationCalls.incrementAndGet();
                    return "success";
                },
                () -> {
                    fallbackCalls.incrementAndGet();
                    return "fallback";
                }
        );

        assertEquals("fallback", result);
        assertEquals(0, operationCalls.get()); // Operation not called
        assertEquals(1, fallbackCalls.get());
    }

    @Test
    public void testForceOpen() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        
        circuitBreaker.forceOpen();
        
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    public void testForceClose() {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());

        circuitBreaker.forceClose();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
        assertEquals(0, circuitBreaker.getFailureCount());
    }

    @Test
    public void testReset() {
        // Record some activity
        circuitBreaker.recordSuccess();
        circuitBreaker.recordFailure();
        circuitBreaker.execute(() -> "test", () -> "fallback");

        circuitBreaker.reset();

        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
        assertEquals(0, circuitBreaker.getTotalRequests());
    }

    @Test
    public void testStats() {
        circuitBreaker.execute(() -> "success", () -> "fallback");
        circuitBreaker.execute(() -> { throw new RuntimeException(); }, () -> "fallback");

        CircuitBreaker.CircuitBreakerStats stats = circuitBreaker.getStats();

        assertEquals("test-site", stats.siteName);
        assertEquals(CircuitBreaker.State.CLOSED, stats.state);
        assertEquals(2, stats.totalRequests);
        assertEquals(1, stats.successfulRequests);
        assertEquals(1, stats.failedRequests);
    }
}
