package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ResponseTruncatorTest {

    private final ResponseTruncator truncator = new ResponseTruncator(2048);

    @Test
    void keepsSmallBodyUntouched() {
        String body = "{\"rows\":[]}";
        assertEquals(body, truncator.truncate(body));
        assertFalse(truncator.isTruncated(body));
    }

    @Test
    void replacesOversizedBodyWithValidEnvelope() {
        String body = "{\"rows\":[\"" + "x".repeat(5000) + "\"]}";

        String truncated = truncator.truncate(body);

        assertTrue(truncated.contains("\"truncated\":true"));
        assertTrue(truncated.contains("originalBytes"));
        assertTrue(truncator.isTruncated(body));
    }
}
