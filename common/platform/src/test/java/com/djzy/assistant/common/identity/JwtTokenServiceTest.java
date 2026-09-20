package com.djzy.assistant.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.security.StaticSecretResolver;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {

    private static final String KEY_ID = "login-token";
    private static final StaticSecretResolver SECRETS =
            StaticSecretResolver.of(Map.of(KEY_ID, "unit-test-secret"));

    @Test
    void rejectsTtlLongerThanFifteenMinutes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtTokenService(SECRETS, KEY_ID, Duration.ofMinutes(16)));
    }

    @Test
    void issuesAndVerifies() {
        JwtTokenService service = new JwtTokenService(SECRETS, KEY_ID, Duration.ofMinutes(15));

        String token = service.issue("u-1", Map.of());

        assertEquals("u-1", service.verify(token).orElseThrow().userId());
        assertEquals(
                "u-1",
                service.verifyAuthorizationHeader("Bearer " + token).orElseThrow().userId());
    }

    @Test
    void missingSecretFailsClosed() {
        JwtTokenService service = new JwtTokenService(StaticSecretResolver.of(Map.of()), KEY_ID, Duration.ofMinutes(15));

        assertThrows(IllegalStateException.class, () -> service.issue("u-1", Map.of()));
        assertTrue(service.verify("any.token.value").isEmpty());
    }
}
