package com.djzy.assistant.common.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class InMemoryNonceStoreTest {

    private final InMemoryNonceStore store = new InMemoryNonceStore();

    @Test
    void consumesNonceOnlyOnce() {
        assertTrue(store.tryConsume("agent-service", "abc", Duration.ofSeconds(600)));
        assertFalse(store.tryConsume("agent-service", "abc", Duration.ofSeconds(600)));
    }

    @Test
    void nonceIsScopedByKeyId() {
        assertTrue(store.tryConsume("agent-service", "abc", Duration.ofSeconds(600)));
        assertTrue(store.tryConsume("management-service", "abc", Duration.ofSeconds(600)));
    }

    @Test
    void expiredNonceCanBeConsumedAgain() throws Exception {
        assertTrue(store.tryConsume("agent-service", "abc", Duration.ofMillis(1)));
        Thread.sleep(20);
        assertTrue(store.tryConsume("agent-service", "abc", Duration.ofMillis(1)));
    }

    @Test
    void blankInputsAreRejected() {
        assertFalse(store.tryConsume(null, "abc", Duration.ofSeconds(1)));
        assertFalse(store.tryConsume("agent-service", "  ", Duration.ofSeconds(1)));
    }
}
