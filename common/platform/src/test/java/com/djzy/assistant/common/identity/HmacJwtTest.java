package com.djzy.assistant.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HmacJwtTest {

    private static final String SECRET = "s3cr3t-for-unit-test";

    @Test
    void issuesAndVerifiesRoundTrip() {
        String token = HmacJwt.issue(SECRET, "u-1", Duration.ofMinutes(15), Map.of("dept", "心内科"));

        Optional<UserTokenClaims> claims = HmacJwt.verify(SECRET, token);

        assertTrue(claims.isPresent());
        assertEquals("u-1", claims.get().subject());
        assertEquals("心内科", claims.get().claims().get("dept"));
        assertTrue(claims.get().expiresAt().isAfter(Instant.now().plus(Duration.ofMinutes(14))));
    }

    @Test
    void rejectsExpiredToken() {
        Instant past = Instant.now().minus(Duration.ofHours(2));
        String token = HmacJwt.issue(SECRET, "u-1", Duration.ofMinutes(15), Map.of(), past);

        assertTrue(HmacJwt.verify(SECRET, token).isEmpty());
    }

    @Test
    void rejectsWrongSecret() {
        String token = HmacJwt.issue(SECRET, "u-1", Duration.ofMinutes(15), Map.of());

        assertTrue(HmacJwt.verify("another-secret", token).isEmpty());
    }

    @Test
    void rejectsTamperedPayload() {
        String token = HmacJwt.issue(SECRET, "u-1", Duration.ofMinutes(15), Map.of());
        String[] parts = token.split("\\.");
        String forged = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8));

        assertTrue(HmacJwt.verify(SECRET, parts[0] + "." + forged + "." + parts[2]).isEmpty());
    }

    @Test
    void rejectsNoneAlgorithm() {
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"sub\":\"admin\",\"iat\":1,\"exp\":9999999999}".getBytes(StandardCharsets.UTF_8));

        assertFalse(HmacJwt.verify(SECRET, header + "." + payload + ".whatever").isPresent());
    }

    @Test
    void reservedClaimsCannotBeOverridden() {
        String token = HmacJwt.issue(SECRET, "u-1", Duration.ofMinutes(15), Map.of("sub", "admin"));

        assertEquals("u-1", HmacJwt.verify(SECRET, token).orElseThrow().subject());
    }
}
