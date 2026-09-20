package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CircuitBreakerRegistryTest {

    @Test
    void opensAfterThresholdFailuresAndRecoversAfterWindow() throws Exception {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(2, Duration.ofMillis(80));
        CircuitBreakerRegistry.Breaker breaker = registry.forKey("doctor_performance");

        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertTrue(breaker.allowRequest());
        breaker.recordFailure();
        assertFalse(breaker.allowRequest());
        assertTrue(breaker.isOpen());

        Thread.sleep(120);
        assertTrue(breaker.allowRequest());
    }

    @Test
    void successResetsFailureStreak() {
        CircuitBreakerRegistry registry = new CircuitBreakerRegistry(2, Duration.ofSeconds(30));
        CircuitBreakerRegistry.Breaker breaker = registry.forKey("api");

        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();

        assertTrue(breaker.allowRequest());
    }
}
