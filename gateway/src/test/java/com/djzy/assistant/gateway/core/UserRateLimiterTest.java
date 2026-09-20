package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UserRateLimiterTest {

    private final UserRateLimiter limiter = new UserRateLimiter(2);

    @Test
    void allowsUpToPermitsPerSecond() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1100));
        assertFalse(limiter.tryAcquireAt("alice", 1200));
    }

    @Test
    void resetsInNextWindow() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1100));
        assertTrue(limiter.tryAcquireAt("alice", 2001));
    }

    @Test
    void limitsPerKey() {
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("alice", 1000));
        assertTrue(limiter.tryAcquireAt("bob", 1000));
    }

    @Test
    void rejectsBlankKey() {
        assertFalse(limiter.tryAcquireAt(" ", 1000));
        assertFalse(limiter.tryAcquireAt(null, 1000));
    }
}
