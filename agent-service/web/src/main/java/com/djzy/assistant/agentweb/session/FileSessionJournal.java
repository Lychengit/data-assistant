package com.djzy.assistant.agentweb.session;

import com.djzy.assistant.common.sse.SseEvent;
import com.djzy.assistant.common.sse.SseEventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 追加写回放位（§19.4 / §19.6 同一套「先落再推」纪律）。
 *
 * <p>每个实例一个 {@code sse-<instanceId>.jsonl}：进程内用按会话的内存索引热读，
 * **进程重启后**从文件重建（这就是断线续传跨重启仍然成立的原因）。写入路径只有本地顺序追加，
 * 不阻塞用户；卷不可用即拒绝启动（日志是唯一事实源）。
 */
public final class FileSessionJournal implements SessionJournal, Closeable {

    private static final Logger log = LoggerFactory.getLogger(FileSessionJournal.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final Path file;
    private final int maxRecordsPerSession;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, List<JournalRecord>> index = new ConcurrentHashMap<>();
    /**
     * 会话列表的累积位（userId → sessionId → 累积器）。
     *
     * <p>为什么不每次列列表都重扫整段日志：列表是**打开页面就要读**的路径，而日志只追加不改写。
     * 让 append 顺带把摘要维护出来，列表就是 O(会话数)；进程重启后由文件重建（{@link #seedSummaries()}）。
     */
    private final ConcurrentMap<String, ConcurrentMap<String, SessionSummary.Accumulator>> summaries =
            new ConcurrentHashMap<>();
    private final Object lock = new Object();

    private FileChannel channel;
    private int unflushed;

    public FileSessionJournal(Path rootDir, String instanceId, int maxRecordsPerSession) {
        this.maxRecordsPerSession = maxRecordsPerSession <= 0 ? 4096 : maxRecordsPerSession;
        try {
            Files.createDirectories(rootDir);
            this.file = rootDir.resolve("sse-" + instanceId + ".jsonl");
            this.channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "回放位卷不可用（" + rootDir + "），拒绝启动：断线续传依赖它（§19.4）", e);
        }
        seedSummaries();
    }

    @Override
    public void append(String userId, String sessionId, JournalRecord record) {
        byte[] line = serialize(userId, sessionId, record);
        synchronized (lock) {
            try {
                channel.position(channel.size());
                channel.write(ByteBuffer.wrap(line));
                if (++unflushed >= 50) {
                    channel.force(false);
                    unflushed = 0;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("追加回放位失败：" + file, e);
            }
        }
        remember(key(userId, sessionId), record);
        rememberSummary(userId, sessionId, record);
    }

    @Override
    public List<JournalRecord> readAfter(String userId, String sessionId, long afterSeq) {
        return loaded(userId, sessionId).stream().filter(r -> r.seq() > afterSeq).toList();
    }

    @Override
    public long lastSeq(String userId, String sessionId) {
        return loaded(userId, sessionId).stream().map(JournalRecord::seq).max(Comparator.naturalOrder()).orElse(-1L);
    }

    private List<JournalRecord> loaded(String userId, String sessionId) {
        String indexKey = key(userId, sessionId);
        List<JournalRecord> cached = index.get(indexKey);
        if (cached != null) {
            synchronized (cached) {
                return List.copyOf(cached);
            }
        }
        List<JournalRecord> fromFile = readFromFile(userId, sessionId);
        List<JournalRecord> existing = index.putIfAbsent(indexKey, fromFile);
        return existing == null ? fromFile : existing;
    }

    private void remember(String indexKey, JournalRecord record) {
        List<JournalRecord> records = index.computeIfAbsent(indexKey, key -> new ArrayList<>());
        synchronized (records) {
            records.add(record);
            while (records.size() > maxRecordsPerSession) {
                records.remove(0);
            }
        }
    }

    @Override
    public List<SessionSummary> listSessions(String userId) {
        if (userId == null) {
            return List.of();
        }
        Map<String, SessionSummary.Accumulator> mine = summaries.get(userId);
        if (mine == null) {
            return List.of();
        }
        // 倒序 + 会话号兜底：同一毫秒写下的两行也要有稳定顺序，否则每次刷新列表都在跳
        return mine.entrySet().stream()
                .map(entry -> entry.getValue().toSummary(entry.getKey()))
                .sorted(Comparator.comparingLong(SessionSummary::lastActiveMs)
                        .reversed()
                        .thenComparing(SessionSummary::sessionId))
                .toList();
    }

    private List<JournalRecord> readFromFile(String userId, String sessionId) {
        List<JournalRecord> result = new ArrayList<>();
        try {
            for (Map<String, Object> raw : readAllLines()) {
                // 归属不符一律不返回：别人的 sessionId 不该换来别人的回答（§11.3 不泄露存在性）
                if (!sessionId.equals(raw.get("sessionId")) || !userId.equals(raw.get("userId"))) {
                    continue;
                }
                result.add(toRecord(raw));
            }
        } catch (IOException e) {
            log.warn("读取回放位失败，按空回放处理：sessionId={} file={}", sessionId, file, e);
        }
        return result;
    }

    /** 全量读一遍：按会话冷读与启动播种共用同一条解析路径（少一条路径就少一处能写歪的地方）。 */
    private List<Map<String, Object>> readAllLines() throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        synchronized (lock) {
            channel.force(false);
            long size = channel.size();
            if (size == 0) {
                return result;
            }
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            channel.read(buffer, 0);
            buffer.flip();
            for (String line : StandardCharsets.UTF_8.decode(buffer).toString().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                result.add(mapper.readValue(line, MAP_TYPE));
            }
        }
        return result;
    }

    /**
     * 启动时按文件重建会话列表（进程重启后列表不丢）。
     *
     * <p>读失败**拒绝启动**而不是「先空着」：空列表会让人以为会话全没了，
     * 而日志是唯一事实源（ADR-28）——把它当不可用，比给出一个骗人的空列表诚实。
     */
    private void seedSummaries() {
        try {
            for (Map<String, Object> raw : readAllLines()) {
                rememberSummary(String.valueOf(raw.get("userId")), String.valueOf(raw.get("sessionId")), toRecord(raw));
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("回放位文件无法重建会话列表（" + file + "），拒绝启动（ADR-28）", e);
        }
    }

    private void rememberSummary(String userId, String sessionId, JournalRecord record) {
        summaries
                .computeIfAbsent(userId, key -> new ConcurrentHashMap<>())
                .computeIfAbsent(sessionId, key -> new SessionSummary.Accumulator())
                .accept(record);
    }

    @SuppressWarnings("unchecked")
    private JournalRecord toRecord(Map<String, Object> raw) {
        SseEventType type = SseEventType.valueOf(String.valueOf(raw.get("type")));
        Map<String, Object> payload = raw.get("payload") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : Map.of();
        return new JournalRecord(
                ((Number) raw.get("seq")).longValue(), SseEvent.of(type, payload), ((Number) raw.get("ts")).longValue());
    }

    private byte[] serialize(String userId, String sessionId, JournalRecord record) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("userId", userId);
        line.put("sessionId", sessionId);
        line.put("seq", record.seq());
        line.put("ts", record.timestampMs());
        line.put("type", record.event().type().name());
        line.put("payload", record.event().payload());
        try {
            return (mapper.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                channel.force(false);
                channel.close();
            } catch (IOException e) {
                log.warn("关闭回放位失败：{}", file, e);
            }
        }
    }

    // 摘要折叠只有一份实现（SessionSummary.Accumulator），两个日志实现共用它：
    // 分开写迟早会漂移，同一份日志换个实现就会得出不同的标题 / 归档位——这种不一致最难查。

    /** 观测用。 */
    public Path file() {
        return file;
    }

    private static String key(String userId, String sessionId) {
        return userId + "\u0000" + sessionId;
    }
}
