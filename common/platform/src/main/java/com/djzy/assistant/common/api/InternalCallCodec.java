package com.djzy.assistant.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内部信封 JSON 编解码唯一实现：各服务不得自行拼装 / 解析（§18.4.7-①）。
 *
 * <p>只负责"写"：解析那头交给 Spring 的消息转换器（请求体已经是 {@code ApiEnvelope<T>}），
 * 所以这里不再有 {@code parseDataCall}——各接口手写解析正是这一版要去掉的东西。
 */
public final class InternalCallCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InternalCallCodec() {}

    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("内部信封序列化失败", e);
        }
    }
}