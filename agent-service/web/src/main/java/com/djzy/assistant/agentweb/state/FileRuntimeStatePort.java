package com.djzy.assistant.agentweb.state;

import com.djzy.assistant.spi.RuntimeStatePort;
import com.djzy.assistant.spi.Snapshot;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * 运行时状态的骨架期落点（§19.5）：{@code (userId, sessionId, key)} → 中立快照 JSON 文件。
 *
 * <p>**这不是终态**：§19.5 要求落 PG 以支持多副本共享。骨架期先用本地卷跑通「挂起 → 恢复」的语义，
 * 换实现时只替换这个类（端口不变）。文件名做了 URL-safe 转义，避免 {@code userId} 里出现路径分隔符。
 */
public final class FileRuntimeStatePort implements RuntimeStatePort {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileRuntimeStatePort(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new IllegalStateException("运行时状态目录不可用：" + root, e);
        }
    }

    @Override
    public Optional<Snapshot> load(String userId, String sessionId, String key) {
        Path file = fileOf(userId, sessionId, key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Map<String, Object> raw = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), MAP_TYPE);
            return Optional.of(new Snapshot(
                    str(raw.get("runtimeId")),
                    str(raw.get("runtimeVersion")),
                    str(raw.get("sessionId")),
                    raw.get("createdAtEpochMs") instanceof Number n ? n.longValue() : System.currentTimeMillis(),
                    payloadOf(raw.get("payload"))));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(String userId, String sessionId, String key, Snapshot snapshot) {
        Path file = fileOf(userId, sessionId, key);
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        raw.put("runtimeId", snapshot.runtimeId());
        raw.put("runtimeVersion", snapshot.runtimeVersion());
        raw.put("sessionId", snapshot.sessionId());
        raw.put("createdAtEpochMs", snapshot.createdAtEpochMs());
        raw.put("payload", snapshot.payload());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writeValueAsString(raw), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入运行时状态失败：" + file, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        try {
            Files.deleteIfExists(fileOf(userId, sessionId, key));
        } catch (IOException e) {
            throw new IllegalStateException("删除运行时状态失败：" + fileOf(userId, sessionId, key), e);
        }
    }

    private Path fileOf(String userId, String sessionId, String key) {
        return root.resolve(escape(userId)).resolve(escape(sessionId)).resolve(escape(key) + ".json");
    }

    private static String escape(String value) {
        return java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> payloadOf(Object value) {
        return value instanceof Map<?, ?> map ? new java.util.LinkedHashMap<>((Map<String, Object>) map) : Map.of();
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
