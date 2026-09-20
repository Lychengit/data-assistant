package com.djzy.assistant.common.eventlog;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地追加写日志（§19.6 / ADR-28）：**唯一事实源**。
 *
 * <p>路径：{@code /data/eventlog/agent-{instanceId}.jsonl}，按批 fsync（50 条或 100ms，谁先到算谁）。
 * 启动时校验卷可用，不可用则拒绝启动。Redis / 消费者故障只允许造成 PG 滞后，不允许造成日志缺条。
 *
 * <p>写入路径**不阻塞用户**：这里是纯本地顺序写 + 批量 fsync。
 */
public final class AppendOnlyEventLog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AppendOnlyEventLog.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final EventLogConfig config;
    private final EventLogCursorStore cursorStore;
    private final EventLogMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    private FileChannel channel;
    private long position;
    private int unflushed;
    private long lastFlushAtMs;

    public AppendOnlyEventLog(EventLogConfig config, EventLogCursorStore cursorStore, EventLogMetrics metrics) {
        this.config = config;
        this.cursorStore = cursorStore;
        this.metrics = metrics == null ? EventLogMetrics.noop() : metrics;
        open();
    }

    public AppendOnlyEventLog(EventLogConfig config, EventLogCursorStore cursorStore) {
        this(config, cursorStore, EventLogMetrics.noop());
    }

    private void open() {
        Path root = config.rootDir();
        try {
            Files.createDirectories(root);
            verifyWritable(root);
            Path file = config.logFile();
            this.channel = FileChannel.open(
                    file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
            this.position = channel.size();
            this.lastFlushAtMs = System.currentTimeMillis();
        } catch (IOException e) {
            throw new EventLogUnavailableException(
                    "会话日志卷不可用（" + root + "），拒绝启动：日志是唯一事实源，不得静默退化", e);
        }
    }

    private static void verifyWritable(Path root) throws IOException {
        Path probe = root.resolve(".write-probe-" + UUID.randomUUID());
        try (FileChannel probeChannel = FileChannel.open(
                probe, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            probeChannel.write(ByteBuffer.wrap("ok".getBytes(StandardCharsets.UTF_8)));
            probeChannel.force(true);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    /** 追加一条事件，返回位点；按批 fsync 后返回（用户路径不等待队列）。 */
    public AppendResult append(AgentEvent event) {
        byte[] line = serialize(event);
        synchronized (lock) {
            try {
                long start = position;
                channel.position(position);
                channel.write(ByteBuffer.wrap(line));
                position += line.length;
                unflushed++;
                maybeFlush();
                return new AppendResult(start, position);
            } catch (IOException e) {
                metrics.writeFailed(e);
                throw new UncheckedIOException("追加会话日志失败：" + config.logFile(), e);
            }
        }
    }

    private byte[] serialize(AgentEvent event) {
        try {
            String json = mapper.writeValueAsString(event.toMap());
            return (json + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void maybeFlush() throws IOException {
        long now = System.currentTimeMillis();
        if (unflushed >= config.fsyncBatchSize() || (unflushed > 0 && now - lastFlushAtMs >= config.fsyncIntervalMs())) {
            flushLocked();
        }
    }

    /** 显式 fsync（定时器或关闭前调用）。 */
    public void flush() {
        synchronized (lock) {
            try {
                flushLocked();
            } catch (IOException e) {
                metrics.writeFailed(e);
                throw new UncheckedIOException(e);
            }
        }
    }

    private void flushLocked() throws IOException {
        if (unflushed == 0) {
            return;
        }
        channel.force(false);
        unflushed = 0;
        lastFlushAtMs = System.currentTimeMillis();
    }

    /** 从指定字节位点扫描记录（历史 / 回放 / 断线续传 / 重启追赶都从这里派生）。 */
    public List<AgentEvent> scanFrom(long offset) {
        synchronized (lock) {
            try {
                flushLocked();
                long size = channel.size();
                if (offset >= size) {
                    return List.of();
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) (size - offset));
                channel.read(buffer, offset);
                buffer.flip();
                String content = StandardCharsets.UTF_8.decode(buffer).toString();
                List<AgentEvent> events = new ArrayList<>();
                for (String line : content.split("\n")) {
                    if (!line.isBlank()) {
                        events.add(deserialize(line));
                    }
                }
                return events;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private AgentEvent deserialize(String line) {
        try {
            Map<String, Object> map = mapper.readValue(line, MAP_TYPE);
            Map<String, Object> payload = map.get("payload") instanceof Map
                    ? (Map<String, Object>) map.get("payload")
                    : new LinkedHashMap<>();
            return new AgentEvent(
                    String.valueOf(map.get("eventId")),
                    AgentEventType.valueOf(String.valueOf(map.get("type"))),
                    map.get("sessionId") == null ? null : String.valueOf(map.get("sessionId")),
                    String.valueOf(map.get("turnId")),
                    String.valueOf(map.get("traceId")),
                    ((Number) map.getOrDefault("seq", 0)).longValue(),
                    ((Number) map.getOrDefault("timestamp", 0L)).longValue(),
                    map.get("source") == null ? "main" : String.valueOf(map.get("source")),
                    payload);
        } catch (IOException e) {
            throw new UncheckedIOException("会话日志记录损坏：" + line, e);
        }
    }

    /** 进程重启时把「投递未确认」的事件重新入队（event_id 幂等，重放安全）。 */
    public List<AgentEvent> pendingRedelivery() {
        long acked = cursorStore.load();
        List<AgentEvent> pending = scanFrom(acked);
        if (!pending.isEmpty()) {
            metrics.unconfirmedBacklog(pending.size(), pending.get(0).timestampEpochMs());
        }
        return pending;
    }

    /** 投递确认：记录已确认位点（XACK 之后调用）。 */
    public void ack(long offset) {
        cursorStore.save(offset);
    }

    public long ackedOffset() {
        return cursorStore.load();
    }

    /**
     * 裁剪：丢弃「已确认投递 **且** 超出保留窗口」的前缀，避免日志无限增长（ADR-28）。
     *
     * @return 是否发生裁剪
     */
    public boolean trimAcked() {
        synchronized (lock) {
            try {
                flushLocked();
                long acked = cursorStore.load();
                if (acked <= 0) {
                    return false;
                }
                long size = channel.size();
                if (acked >= size) {
                    return false;
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) (size - acked));
                channel.read(buffer, acked);
                buffer.flip();
                String remainder = StandardCharsets.UTF_8.decode(buffer).toString();
                Duration retention = config.retention();
                long cutoff = System.currentTimeMillis() - retention.toMillis();
                StringBuilder kept = new StringBuilder();
                long newAcked = acked;
                long cursor = acked;
                for (String line : remainder.split("\n", -1)) {
                    if (line.isBlank()) {
                        cursor += line.getBytes(StandardCharsets.UTF_8).length + 1;
                        continue;
                    }
                    AgentEvent event = deserialize(line);
                    long lineBytes = line.getBytes(StandardCharsets.UTF_8).length + 1;
                    if (event.timestampEpochMs() < cutoff && cursor < acked) {
                        newAcked = cursor + lineBytes;
                    } else {
                        kept.append(line).append('\n');
                    }
                    cursor += lineBytes;
                }
                if (kept.isEmpty()) {
                    return false;
                }
                Path tmp = config.rootDir().resolve(config.logFile().getFileName() + ".tmp");
                Files.writeString(tmp, kept.toString(), StandardCharsets.UTF_8);
                channel.close();
                Files.move(tmp, config.logFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                channel = FileChannel.open(
                        config.logFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
                position = channel.size();
                cursorStore.save(newAcked);
                return true;
            } catch (IOException e) {
                log.warn("会话日志裁剪失败，跳过本轮", e);
                return false;
            }
        }
    }

    public EventLogConfig config() {
        return config;
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                flushLocked();
                channel.close();
            } catch (IOException e) {
                log.warn("关闭会话日志失败", e);
            }
        }
    }
}
