package com.djzy.assistant.common.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** §20.1.3 / §20.1.4：防伪、防篡改、防重放，失败一律统一 401。 */
class ServiceSignerVerifierTest {

    private static final ServiceCredential CREDENTIAL = new ServiceCredential("gw-01", "s3cr3t-shared-key");

    @Test
    void validSignaturePasses() {
        ServiceVerifier verifier = verifier();
        long now = 1_700_000_000L;
        Map<String, String> headers = ServiceSigner.sign(
                CREDENTIAL, "POST", "/v1/query", Map.of("page", List.of("1")), "{\"userId\":\"u1\"}", now, "n1");

        VerificationResult result =
                verifier.verify(headers, "POST", "/v1/query", Map.of("page", List.of("1")), "{\"userId\":\"u1\"}", now);

        assertTrue(result.verified());
        assertEquals("gw-01", result.keyId());
    }

    @Test
    void tamperedScopeInBodyIsDetected() {
        ServiceVerifier verifier = verifier();
        long now = 1_700_000_000L;
        Map<String, String> headers =
                ServiceSigner.sign(CREDENTIAL, "POST", "/v1/query", Map.of(), "{\"scope\":{\"depts\":[\"心内科\"]}}", now, "n2");

        VerificationResult result = verifier.verify(
                headers, "POST", "/v1/query", Map.of(), "{\"scope\":{\"depts\":[\"全部\"]}}", now);

        assertFalse(result.verified());
        assertEquals(SecurityFailure.SIGNATURE_MISMATCH, result.failure());
    }

    @Test
    void staleTimestampIsRejected() {
        ServiceVerifier verifier = verifier();
        long signedAt = 1_700_000_000L;
        Map<String, String> headers =
                ServiceSigner.sign(CREDENTIAL, "GET", "/v1/capabilities", Map.of(), "", signedAt, "n3");

        VerificationResult result =
                verifier.verify(headers, "GET", "/v1/capabilities", Map.of(), "", signedAt + 301);

        assertEquals(SecurityFailure.TIMESTAMP_OUT_OF_WINDOW, result.failure());
    }

    @Test
    void replayedNonceIsRejected() {
        ServiceVerifier verifier = verifier();
        long now = 1_700_000_000L;
        Map<String, String> headers =
                ServiceSigner.sign(CREDENTIAL, "GET", "/v1/capabilities", Map.of(), "", now, "n4");

        assertTrue(verifier.verify(headers, "GET", "/v1/capabilities", Map.of(), "", now).verified());
        assertEquals(
                SecurityFailure.NONCE_REPLAYED,
                verifier.verify(headers, "GET", "/v1/capabilities", Map.of(), "", now).failure());
    }

    @Test
    void unknownKeyAndMissingHeadersAreRejected() {
        ServiceVerifier verifier = verifier();
        Map<String, String> unknown = ServiceSigner.sign(
                new ServiceCredential("gw-99", "other"), "GET", "/v1/x", Map.of(), "", 1_700_000_000L, "n5");
        assertEquals(
                SecurityFailure.UNKNOWN_KEY,
                verifier.verify(unknown, "GET", "/v1/x", Map.of(), "", 1_700_000_000L).failure());
        assertEquals(
                SecurityFailure.MISSING_HEADERS,
                verifier.verify(Map.of(), "GET", "/v1/x", Map.of(), "", 1_700_000_000L).failure());
    }

    @Test
    void canonicalStringFollowsSpecLayout() {
        CanonicalRequest request =
                new CanonicalRequest("post", "/v1/query", Map.of("b", List.of("2"), "a", List.of("1")), 1_700_000_000L, "nonce", "{}");
        String canonical = request.canonicalString();
        String[] lines = canonical.split("\n");
        assertEquals("v1", lines[0]);
        assertEquals("POST", lines[1]);
        assertEquals("/v1/query", lines[2]);
        assertEquals("a=1&b=2", lines[3]);
        assertEquals("1700000000", lines[4]);
        assertEquals("nonce", lines[5]);
        assertEquals(Hmac.sha256Hex("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)), lines[6]);
    }

    private static ServiceVerifier verifier() {
        return new ServiceVerifier(new TestSecretResolver(), new TestNonceStore());
    }

    static final class TestSecretResolver implements SecretResolver {
        @Override
        public Optional<String> secretFor(String keyId) {
            return "gw-01".equals(keyId) ? Optional.of("s3cr3t-shared-key") : Optional.empty();
        }
    }

    static final class TestNonceStore implements NonceStore {
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        private final Map<String, Duration> ttls = new HashMap<>();

        @Override
        public boolean tryConsume(String keyId, String nonce, Duration ttl) {
            ttls.put(keyId + ":" + nonce, ttl);
            return seen.add(keyId + ":" + nonce);
        }
    }
}
