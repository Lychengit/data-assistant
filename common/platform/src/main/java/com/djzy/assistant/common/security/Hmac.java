package com.djzy.assistant.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 / SHA-256 工具（§20.1.3；签名与验签只用这一个实现，业务代码不得自行拼接）。 */
public final class Hmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private Hmac() {}

    public static String sha256Hex(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(body == null ? new byte[0] : body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String sign(String sharedSecret, String canonicalString) {
        return SignatureHeaders.SIGNATURE_PREFIX
                + Base64.getEncoder().encodeToString(hmacSha256(sharedSecret, canonicalString));
    }

    /**
     * 原始 HMAC-SHA256 字节：JWT (HS256) 等需要自定义编码的场景复用同一实现，避免出现第二份 crypto。
     */
    public static byte[] hmacSha256(String sharedSecret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return mac.doFinal(data == null ? new byte[0] : data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 计算失败", e);
        }
    }

    /** 常量时间比较，防时序侧信道（§20.1.4-5）。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
