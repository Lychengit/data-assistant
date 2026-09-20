package com.djzy.assistant.common.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 口令哈希（M1）：随机盐、常量时间校验、格式非法一律失败（fail-closed）。 */
class PasswordHasherTest {

    @Test
    void samePasswordProducesDifferentHashes() {
        String first = PasswordHasher.hash("admin123");
        String second = PasswordHasher.hash("admin123");

        assertNotEquals(first, second);
        assertTrue(PasswordHasher.verify("admin123", first));
        assertTrue(PasswordHasher.verify("admin123", second));
    }

    @Test
    void wrongPasswordFails() {
        assertFalse(PasswordHasher.verify("admin124", PasswordHasher.hash("admin123")));
        assertFalse(PasswordHasher.verify("", PasswordHasher.hash("admin123")));
    }

    @Test
    void fixedSaltHashIsReproducible() {
        byte[] salt = "demo-seed-salt-1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = PasswordHasher.hash("admin123", 210_000, salt);

        assertEquals(hash, PasswordHasher.hash("admin123", 210_000, salt));
        assertTrue(PasswordHasher.verify("admin123", hash));
    }

    @Test
    void malformedOrWeakEncodingsAreRejected() {
        assertFalse(PasswordHasher.verify("admin123", null));
        assertFalse(PasswordHasher.verify("admin123", ""));
        assertFalse(PasswordHasher.verify("admin123", "plaintext"));
        assertFalse(PasswordHasher.verify("admin123", "bcrypt$1$2$3"));
        assertFalse(PasswordHasher.verify("admin123", "pbkdf2$1000$c2FsdA$aGFzaA"));
        assertFalse(PasswordHasher.verify("admin123", "pbkdf2$210000$!!!$!!!"));
    }
}
