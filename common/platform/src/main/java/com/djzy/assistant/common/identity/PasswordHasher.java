package com.djzy.assistant.common.identity;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 口令哈希唯一实现（M1 登录，§18.4.6 / §19.4）：PBKDF2-HMAC-SHA256 + 随机盐，**只存哈希不存明文**。
 *
 * <p>存储格式 {@code pbkdf2$<迭代次数>$<盐 base64>$<哈希 base64>}：自描述，便于将来提升迭代次数后
 * 按前缀识别旧格式（旧的仍可校验）。校验用常量时间比较；格式不合法 / 算法不认识一律判**失败**
 * （fail-closed，绝不放行）。
 */
public final class PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";

    /** 默认迭代次数：本地骨架期取值，接入真实账号前按部署硬件复核（§20.1.2 密钥与口令同级别对待）。 */
    public static final int DEFAULT_ITERATIONS = 210_000;

    private static final int MIN_ITERATIONS = 10_000;
    private static final int MAX_ITERATIONS = 5_000_000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getDecoder();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return hash(password, DEFAULT_ITERATIONS, salt);
    }

    /** 固定盐 + 固定迭代次数的哈希（仅用于可复现的部署种子，如本地演示账号）。 */
    public static String hash(String password, int iterations, byte[] salt) {
        requireUsable(password, iterations, salt);
        byte[] key = pbkdf2(password, salt, iterations);
        return PREFIX + "$" + iterations + "$" + ENCODER.encodeToString(salt) + "$" + ENCODER.encodeToString(key);
    }

    /** @return 口令匹配返回 {@code true}；不匹配 / 格式非法一律 {@code false}。 */
    public static boolean verify(String password, String encoded) {
        if (password == null || encoded == null) {
            return false;
        }
        String[] parts = encoded.split("\\$");
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
                return false;
            }
            byte[] salt = DECODER.decode(parts[2]);
            byte[] expected = DECODER.decode(parts[3]);
            byte[] actual = pbkdf2(password, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 不可用：" + ALGORITHM, e);
        }
    }

    private static void requireUsable(String password, int iterations, byte[] salt) {
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("口令不能为空");
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) {
            throw new IllegalArgumentException("迭代次数超出允许范围：" + iterations);
        }
        if (salt == null || salt.length < 8) {
            throw new IllegalArgumentException("盐至少 8 字节");
        }
    }
}
