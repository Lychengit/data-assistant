package com.djzy.assistant.common.eventlog;

import com.djzy.assistant.spi.AgentEvent;
import com.djzy.assistant.spi.AgentEventType;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地追加写日志（§19.6 / ADR-28）：**唯一事实源**。
 *
 * <p>路径：{@code /data/eventlog/agent-{instanceId}.jsonl}，按批 fsync（50 条或 100ms，谁先到算谁）。
 * 启动时校验卷可用，不可用则拒绝启动。Redis / 消费者故障只允许造成 PG 滞后，不允许造成日志缺条。
 *
 * <p>写入路径**不阻塞用户**：这里是纯本地顺序写 + 批量 fsync。
 *
 * <h2>文件不会无限长：两件事，都在 {@link #maintenance()}</h2>
 *
 * <ul>
 *   <li><b>裁剪</b>（{@link #trimAcked()}）：删掉**开头**那一段「已经投递确认、而且早于保留窗口」的记录。
 *       只裁前缀——裁中间会让字节位点失去意义。**未确认投递的记录一条都不删**（删了就是丢事件，
 *       那正是 ADR-28 要防的事）。</li>
 *   <li><b>滚动</b>（{@link #rollIfNeeded()}）：活动文件超过上限时，把「还没确认投递的尾巴」搬到新文件、
 *       旧文件按时间戳改名留档。于是活动文件只装「还没投递出去的那点东西」，而读位点始终只在一个文件里，
 *       {@link #pendingRedelivery()} 这类按位点扫描的能力一点不用改。</li>
 * </ul>
 *
 * <p><b>留档文件为什么可以整份删掉</b>：滚动时是把未确认的尾巴**复制**进新文件，所以那些未确认的记录
 * 在新文件里还各有一份；删掉留档永远不会丢「还没送出去」的事件——重启后的追赶投递读的是活动文件。
 * 代价是那几条未确认记录在磁盘上会短暂地存在两份，靠消费端按 {@code event_id} 幂等兜住。
 */
public final class AppendOnlyEventLog implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AppendOnlyEventLog.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String LINE_ENDING = "\n";

    private final EventLogConfig config;
    private final EventLogCursorStore cursorStore;
    private final EventLogMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    private FileChannel channel;
    private long position;
    private int unflushed;
    private long lastFlushAtMs;

    /**
     * 已确认投递的字节位点（活动文件内的偏移）：它之前的记录都已经进过队列。
     *
     * <p>为什么在内存里再记一份：写事件是热路径，每写一条都去读一次位点文件太亏；
     * 而且位点**只许前进**（见 {@link #ack(long)}），内存里的这份就是权威的那一份。
     */
    private long ackedOffset;

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
            this.ackedOffset = normalizedAckedOffset(cursorStore.load(), position);
            this.lastFlushAtMs = System.currentTimeMillis();
        } catch (IOException e) {
            throw new EventLogUnavailableException(
                    "会话日志卷不可用（" + root + "），拒绝启动：日志是唯一事实源，不得静默退化", e);
        }
    }

    /**
     * 位点比文件还大说明对不上（文件被换过 / 被截断过）。
     *
     * <p>这时**收敛到位点等于文件末尾**而不是报错：那样等于「所有事件都已确认」，
     * 后果只是漏一次追赶投递；而直接启动失败会把一个可以自愈的小问题放大成起不来。
     */
    private long normalizedAckedOffset(long stored, long fileSize) {
        if (stored <= fileSize) {
            return Math.max(0L, stored);
        }
        log.warn("日志位点（{}）比文件大小（{}）还大，按文件末尾收敛：{}", stored, fileSize, config.logFile());
        return fileSize;
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
            return (json + LINE_ENDING).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("序列化事件失败：" + event.eventId(), e);
        }
    }

    private void maybeFlush() throws IOException {
        long now = System.currentTimeMillis();
        if (unflushed >= config.fsyncBatchSize() || now - lastFlushAtMs >= config.fsyncIntervalMs()) {
            flushLocked();
        }
    }

    /** 把缓冲里的内容落到盘上（批量 fsync 的出口，也供维护任务与停机调用）。 */
    public void flush() {
        synchronized (lock) {
            try {
                flushLocked();
            } catch (IOException e) {
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
                for (String line : content.split(LINE_ENDING)) {
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
        synchronized (lock) {
            List<AgentEvent> pending = scanFrom(ackedOffset);
            if (!pending.isEmpty()) {
                metrics.unconfirmedBacklog(pending.size(), pending.get(0).timestampEpochMs());
            }
            return pending;
        }
    }

    /**
     * 投递确认：记下「到这个字节为止都已投递」。
     *
     * <p>**位点只许前进**：多条事件并发投递时，先发的可能后确认完，把位点写小就等于
     * 「本来已确认的又被当成没确认」——重启后会重复投递（有条目号幂等，不会重复入库），
     * 但裁剪会跟着算错位置。所以小的那个一律忽略。
     */
    public void ack(long offset) {
        synchronized (lock) {
            if (offset <= ackedOffset) {
                return;
            }
            ackedOffset = offset;
            cursorStore.save(offset);
        }
    }

    public long ackedOffset() {
        synchronized (lock) {
            return ackedOffset;
        }
    }

    /**
     * 裁剪：删掉开头那段「已确认投递 **且** 早于保留窗口」的记录。
     *
     * <p>三条规矩：
     * <ol>
     *   <li><b>只裁前缀</b>：遇到第一条「不能删」的就停。裁剪之后文件里的字节位点整体前移，
     *       所以位点、写入位置要一起减掉同样多（见 {@link #rebase(long)}）；</li>
     *   <li><b>未确认投递的一律不裁</b>：那正是「重启要重新入队」的部分，删了就真丢事件；</li>
     *   <li>没有可裁的就<strong>不重写文件</strong>：白写一遍大文件既慢又没意义。</li>
     * </ol>
     *
     * @return 是否真的裁掉了东西
     */
    public boolean trimAcked() {
        synchronized (lock) {
            try {
                flushLocked();
                long cut = trimmablePrefixEnd();
                if (cut <= 0) {
                    return false;
                }
                // 先把 cut 之后的记录留下，再把位点整体前移 cut 字节。
                // 前移的量必须是「删掉的前缀长度」，不是「留下来的长度」——这两个数不一样，
                // 用错了位点就会指向文件中间，重启后按错的位点追赶投递。
                rewriteKeepingFrom(cut);
                rebase(cut);
                log.info("会话日志裁剪完成：删掉前缀 {} 字节（保留窗口 {}），file={}", cut, config.retention(), config.logFile());
                return true;
            } catch (IOException e) {
                log.warn("会话日志裁剪失败，跳过本轮", e);
                return false;
            }
        }
    }

    /**
     * 算出「可以整段删掉的前缀」到哪个字节结束（0 = 一个都没得删）。
     *
     * <p>逐行读、逐行判；读的是**文件本身**而不是某段缓存，所以判据就是磁盘上的事实。
     */
    private long trimmablePrefixEnd() throws IOException {
        long cutoff = System.currentTimeMillis() - config.retention().toMillis();
        long cursor = 0;
        long cut = 0;
        try (BufferedReader reader = Files.newBufferedReader(config.logFile(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                long lineEnd = cursor + lineBytes(line);
                // 未确认投递：从这里往后都不许删（前缀裁剪到此为止）
                if (lineEnd > ackedOffset) {
                    break;
                }
                if (line.isBlank()) {
                    cut = lineEnd;
                    cursor = lineEnd;
                    continue;
                }
                AgentEvent event = deserialize(line);
                // 还在保留窗口内：它后面那些只会更新，同样停在这里
                if (event.timestampEpochMs() >= cutoff) {
                    break;
                }
                cut = lineEnd;
                cursor = lineEnd;
            }
        }
        return cut;
    }

    /**
     * 把 {@code skipBefore} 之后的记录抄到临时文件，再用它替换原文件。
     *
     * <p>逐行搬运而不是把整个文件读进内存：单文件上限是 256 MB，读到内存里会给维护任务
     * 制造一次几百兆的堆尖峰，而它本来只是清理动作。
     *
     * <p>写临时文件再改名替换（而不是原地截断重写）：万一这台机器在抄写中途断电，
     * 磁盘上要么是完整的旧文件、要么是完整的新文件，不会留下一个抄了一半的活动日志。
     */
    private void rewriteKeepingFrom(long skipBefore) throws IOException {
        Path tmp = config.rootDir().resolve(config.logFile().getFileName() + ".tmp");
        long cursor = 0;
        try (BufferedReader reader = Files.newBufferedReader(config.logFile(), StandardCharsets.UTF_8);
                BufferedWriter writer = Files.newBufferedWriter(
                        tmp,
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE)) {
            String line;
            while ((line = reader.readLine()) != null) {
                long lineBytes = lineBytes(line);
                if (cursor >= skipBefore) {
                    writer.write(line);
                    writer.write(LINE_ENDING);
                }
                cursor += lineBytes;
            }
        }
        // Windows 上「替换一个正被打开的文件」会失败，所以先关掉自己的句柄再改名。
        channel.close();
        Files.move(tmp, config.logFile(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(
                config.logFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
    }

    /**
     * 裁剪之后把「位点」整体前移：删掉的是前缀，所以位点与写入位置都要减掉同样多。
     *
     * <p>减完立刻落盘：位点和文件内容必须是同一个瞬间的状态，否则下次启动会拿旧位点去读新文件。
     */
    private void rebase(long removedBytes) throws IOException {
        ackedOffset = Math.max(0L, ackedOffset - removedBytes);
        cursorStore.save(ackedOffset);
        position = channel.size();
    }

    /**
     * 活动文件超过上限就滚动：未确认的尾巴搬到新文件，旧文件按时间戳改名留档。
     *
     * <p>为什么这么搬：位点是「活动文件内的字节偏移」，所以新文件里只应该有**还没确认投递**的记录
     * （它们本来就要重投），搬完之后位点归零正好对上这个语义。
     *
     * @return 是否发生了滚动
     */
    public boolean rollIfNeeded() {
        synchronized (lock) {
            try {
                flushLocked();
                long size = channel.size();
                if (size < config.maxFileBytes()) {
                    return false;
                }
                // 一条都还没确认投递时别滚：这时文件里没有「已确认的旧内容」可封存，
                // 搬过去的就是文件本身——白造一份同样大的留档，而且下一分钟还会再造一份。
                // 宁可让活动文件暂时超标（消费端修好之后，下一次维护自然就把它收拢了）。
                if (ackedOffset <= 0) {
                    return false;
                }
                roll(size);
                return true;
            } catch (IOException e) {
                log.warn("会话日志滚动失败，本轮继续写同一个文件", e);
                return false;
            }
        }
    }

    private void roll(long size) throws IOException {
        byte[] tail = readBytes(ackedOffset, size);
        Path rotated = config.rotatedFile(System.currentTimeMillis());
        channel.close();
        Files.move(config.logFile(), rotated, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(
                config.logFile(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ);
        if (tail.length > 0) {
            channel.position(0);
            channel.write(ByteBuffer.wrap(tail));
        }
        position = tail.length;
        ackedOffset = 0;
        cursorStore.save(0);
        log.info(
                "会话日志已滚动：旧文件留档为 {}（{} 字节），未确认的 {} 条记录搬进新文件",
                rotated.getFileName(), size, countLines(tail));
    }

    private byte[] readBytes(long from, long to) throws IOException {
        if (to <= from) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate((int) (to - from));
        long offset = from;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return Arrays.copyOf(buffer.array(), buffer.position());
    }

    private static int countLines(byte[] bytes) {
        int lines = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    /**
     * 删掉过期的留档文件。
     *
     * <p>它们装的都是已确认投递的记录，所以到点可以整份删；用名字里的时间戳判年龄
     * （文件被拷贝过时 mtime 不可信），名字读不出来才退回看 mtime。
     */
    public int pruneRotated() {
        long cutoff = System.currentTimeMillis() - config.retention().toMillis();
        int removed = 0;
        removed += pruneAbandonedTmp(cutoff);
        try (Stream<Path> files = Files.list(config.rootDir())) {
            List<Path> expired = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(config.rotatedNamePrefix()))
                    .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .filter(path -> ageOf(path) < cutoff)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : expired) {
                Files.deleteIfExists(path);
                removed++;
            }
        } catch (IOException e) {
            log.warn("清理过期日志留档失败，跳过本轮", e);
            return removed;
        }
        if (removed > 0) {
            log.info("清理过期日志留档 {} 个（保留窗口 {}）", removed, config.retention());
        }
        return removed;
    }

    /**
     * 收走「裁剪到一半崩了」留下的临时文件（{@code agent-{instanceId}.jsonl.tmp}）。
     *
     * <p>正常路径上它写完就被改名替换掉了，所以留在这里的一定是崩溃残留，占着最多一个单文件上限那么大。
     * 用和留档同一条时间线判年龄（必须够老才删），这样万一它其实正在被写，也绝不会被误删。
     */
    private int pruneAbandonedTmp(long cutoff) {
        Path tmp = config.rootDir().resolve(config.logFile().getFileName() + ".tmp");
        try {
            if (Files.isRegularFile(tmp) && ageOf(tmp) < cutoff) {
                Files.deleteIfExists(tmp);
                log.info("清理崩溃残留的日志临时文件：{}", tmp.getFileName());
                return 1;
            }
        } catch (IOException e) {
            log.warn("清理日志临时文件失败，跳过：{}", tmp, e);
        }
        return 0;
    }

    private static long ageOf(Path rotated) {
        long stamped = EventLogConfig.rotatedAtMs(rotated.getFileName().toString());
        if (stamped > 0) {
            return stamped;
        }
        try {
            return Files.getLastModifiedTime(rotated).toMillis();
        } catch (IOException e) {
            return System.currentTimeMillis();
        }
    }

    /**
     * 维护任务一次做完：落盘 → 裁剪 → 滚动 → 清理留档。
     *
     * <p>顺序有讲究：先裁剪（可能一下小很多，就不用滚了），再看要不要滚，最后打扫留档。
     * 由调度器按固定间隔调用（见 agent-service 的 {@code EventLogMaintenanceScheduler}）；
     * 它只碰**本实例自己的文件**，所以不需要分布式锁。
     */
    public void maintenance() {
        flush();
        trimAcked();
        rollIfNeeded();
        pruneRotated();
    }

    /** 一行记录在磁盘上占多少字节（我们写的时候就是「JSON + 换行」，所以这样能算回来）。 */
    private static long lineBytes(String line) {
        return line.getBytes(StandardCharsets.UTF_8).length + LINE_ENDING.getBytes(StandardCharsets.UTF_8).length;
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
