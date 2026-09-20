package com.djzy.assistant.common.llm;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM 实现（§20.1.6）：每次加密**独立随机 IV**，密文自带完整性校验。
 *
 * <p>选 GCM 而不是 CBC：密钥这类短小、高价值的明文，最怕的不是被读到，是**被改掉**。
 * GCM 的认证标签让「密文被篡改」在解密时直接失败，而不是解出一段貌似合理的垃圾。
 *
 * <p>根密钥（KEK）通过部署环境注入，**不写进代码、不进 git、不进镜像**（§20.1.5 同一条底线）。
 * 落库格式：{@code Base64(IV ‖ 密文‖标签)}，IV 固定 12 字节。
 */
public final class AesGcmApiKeyCipher implements ApiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEK_LENGTH = 32;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    private AesGcmApiKeyCipher(byte[] kek) {
        this.key = new SecretKeySpec(kek, "AES");
    }

    /**
     * @param kekBase64 32 字节 KEK 的 Base64；缺失或长度不对**直接抛**——
     *     宁可让调用方在启动/保存时就失败，也不退化成明文存储。
     */
    public static AesGcmApiKeyCipher fromBase64(String kekBase64) {
        if (kekBase64 == null || kekBase64.isBlank()) {
            throw new IllegalStateException("缺少模型密钥加密密钥（KEK）：拒绝在无加密能力时处理模型 API Key（§20.1.6）");
        }
        byte[] kek;
        try {
            kek = Base64.getDecoder().decode(kekBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("模型密钥加密密钥（KEK）不是合法的 Base64", e);
        }
        if (kek.length != KEK_LENGTH) {
            throw new IllegalStateException(
                    "模型密钥加密密钥（KEK）必须是 " + KEK_LENGTH + " 字节（AES-256），实际 " + kek.length + " 字节");
        }
        return new AesGcmApiKeyCipher(kek);
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("待加密的 API Key 不能为 null");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("API Key 加密失败", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("待解密的 API Key 密文为空");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("API Key 密文不是合法的 Base64", e);
        }
        if (raw.length <= IV_LENGTH) {
            throw new IllegalStateException("API Key 密文长度不足，缺少 IV 或认证标签");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // 认证失败（密文被改 / KEK 不是当初那把）都在这里，统一成一句话，不泄露细节
            throw new IllegalStateException("API Key 解密失败：密文被篡改，或 KEK 与写入时不一致", e);
        }
    }
}
