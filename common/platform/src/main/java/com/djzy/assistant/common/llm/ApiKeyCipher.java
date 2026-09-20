package com.djzy.assistant.common.llm;

/**
 * 模型 API Key 的落库加密（§20.1.6）。
 *
 * <p>把「怎么加密」收敛成一个端口，是为了让「解密所需的根密钥来自部署环境」这件事
 * 只有一处实现——库里的密文与运行环境里的 KEK 分家，单拿到库 dump 解不出 Key。
 */
public interface ApiKeyCipher {

    /** 明文 → 可入库的密文串。 */
    String encrypt(String plaintext);

    /** 密文串 → 明文；密文被篡改时抛异常（AES-GCM 自带完整性校验），不返回半截结果。 */
    String decrypt(String ciphertext);

    /**
     * 界面回显提示：保留可辨认的前缀与尾部 4 位，其余一律抹掉。
     *
     * <p>够回答「我现在配的是哪把 Key」，但不足以还原它。
     */
    static String hint(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return "";
        }
        String trimmed = plaintext.trim();
        int keep = Math.min(4, trimmed.length());
        String tail = trimmed.substring(trimmed.length() - keep);
        int dash = trimmed.indexOf('-');
        // 前缀只在短且像供应商前缀时保留（sk- / dsk-），避免把密钥本体当成前缀回显
        String prefix = dash > 0 && dash <= 8 ? trimmed.substring(0, dash + 1) : "";
        return prefix + "****" + tail;
    }

    /**
     * 按部署配置构造：配了 KEK 就用真加密；没配则返回一个「一用就报错」的实现。
     *
     * <p>为什么不在缺失时直接让服务起不来：模型配置是**可选**能力——平台在没有模型的
     * noop 运行时下照样能跑。把它变成启动硬依赖是凭空造出来的耦合。但也不能悄悄退化：
     * 真要用到加密时立刻失败，并把该配哪个变量写进异常消息。
     *
     * @param kekBase64 部署环境注入的 KEK（Base64 的 32 字节）
     * @param propertyName 配置项名，用于拼出可操作的报错信息
     */
    static ApiKeyCipher fromBase64OrUnconfigured(String kekBase64, String propertyName) {
        if (kekBase64 == null || kekBase64.isBlank()) {
            return new ApiKeyCipher() {
                @Override
                public String encrypt(String plaintext) {
                    throw unconfigured();
                }

                @Override
                public String decrypt(String ciphertext) {
                    throw unconfigured();
                }

                private IllegalStateException unconfigured() {
                    return new IllegalStateException(
                            "未配置 " + propertyName + "（Base64 编码的 32 字节），无法加解密模型 API Key");
                }
            };
        }
        return AesGcmApiKeyCipher.fromBase64(kekBase64);
    }
}
