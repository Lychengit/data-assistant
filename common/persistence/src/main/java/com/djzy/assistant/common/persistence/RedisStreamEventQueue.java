package com.djzy.assistant.common.persistence;

import com.djzy.assistant.common.bus.AgentEventCodec;
import com.djzy.assistant.common.bus.EventQueue;
import com.djzy.assistant.common.bus.QueueRecord;
import com.djzy.assistant.common.bus.QueueStats;
import com.djzy.assistant.spi.AgentEvent;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.RedisStreamCommands.XPendingOptions;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Redis Stream 队列（§19.6 / ADR-10 / ADR-28）：{@code XADD} 投递 + {@code XREADGROUP} 消费 +
 * {@code XPENDING} 退避重投 + 死信表。
 *
 * <p>它与 {@link PgOutboxEventBus} 是**同一个端口的两套实现**，所以语义必须逐条对齐；下面这五条
 * 是「换实现时最容易悄悄走样」的地方，写在这里免得后来人靠猜：
 *
 * <ul>
 *   <li><b>队列位点是 Redis 消息 id</b>（{@code 1699999999999-0}），不再是数字：{@code QueueRecord.id}
 *       本来就声明为「对外不透明的字符串」，确认与死信都按它定位；
 *   <li><b>attempts = 投递计数 - 1</b>。Redis 自己维护每个待确认条目的投递计数（{@code XPENDING} 的
 *       {@code timesDelivered}），次次投递都记一笔；PG 实现用 {@code event_outbox.attempts} 列。
 *       两边的 {@link QueueRecord#attempts()} 都表示**已经失败过几次**，所以这里要减 1；
 *   <li><b>退避靠「不确认 + 重置空闲计时」</b>，不靠应用侧的时间戳：失败时 {@code nack} 用
 *       {@code XCLAIM} 把条目重新认领给自己（空闲计时归零、投递计数 +1），之后只有
 *       「空闲时长 ≥ 退避窗口」的条目才会被再次投递。好处是**多副本共用同一个判据**——
 *       消费者崩在半路时，另一个副本看到的是同一条待确认条目和同一段空闲时间，退避窗口不会两边各算一套；
 *   <li><b>死信仍写 PG 的 {@code event_dead_letter}</b>：死信是「必须有人看」的记录，管理端 / 运维按它
 *       排查；换队列实现不换死信落点（§19.6）。写法与 PG 实现同序：**先写死信再出队**，
 *       中间崩溃只会留下重复死信（可见），不会丢事件；
 *   <li><b>重复投递在 Redis 侧看得见</b>：PG 用 {@code event_id} 唯一键把重放吸收成「零影响」，
 *       Redis 流没有唯一键，重启追赶重放会在流里留下第二条位点。这不是缺陷，是明示的 at-least-once——
 *       落库由事实表的 {@code event_id} 唯一键 + {@code ON CONFLICT DO NOTHING} 吸收（§19.6）。
 * </ul>
 *
 * <p>Redis 不可用时**抛异常**（不吞）：{@code LogFirstEventPublisher} 会记「已写本地日志、等待追赶投递」
 * 并计数告警——本地 append-only 日志才是唯一事实源，队列挂了只允许造成 PG 滞后（ADR-28）。
 *
 * <p>Stream 按 {@code MAXLEN ~ 100 万} 滚动清理（§19.6），只做近似裁剪：精确裁剪要遍历，而这里
 * 唯一的硬要求是「已确认的事件不要长期占内存」，不是「流里恰好留 100 万条」。
 */
public final class RedisStreamEventQueue implements EventQueue {

    public static final String TYPE = "redis-stream";

    /** 默认流名；多环境共用一个 Redis 时靠它区分（§19.6「独立实例 / 独立库」的兜底）。 */
    public static final String DEFAULT_STREAM_KEY = "doctor:events";

    /** 消费组固定一个：§19.6 的 event-persist 只有一个消费语义，分组本身不承载业务含义。 */
    public static final String DEFAULT_CONSUMER_GROUP = "event-persist";

    /** 退避上限：重试间隔按 2^n 秒增长，但不超过 30s（与 PG 实现同一把尺子）。 */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    /** 待确认扫描倍数：XPENDING 只按「最老的 N 条」翻页，N = 倍数 × 本次批量。 */
    private static final int PENDING_SCAN_FACTOR = 4;

    private static final String FIELD_PAYLOAD = "payload";

    static final String PG_DEAD_LETTER = """
            INSERT INTO event_dead_letter (event_id, payload, error, retry_count, created_at)
            VALUES (?, ?::jsonb, ?, ?, ?)
            """;

    static final String H2_DEAD_LETTER = """
            INSERT INTO event_dead_letter (event_id, payload, error, retry_count, created_at)
            VALUES (?, ?, ?, ?, ?)
            """;

    private static final String DEAD_LETTER_COUNT = "SELECT count(*) FROM event_dead_letter";

    private static final Logger log = LoggerFactory.getLogger(RedisStreamEventQueue.class);

    private final StringRedisTemplate template;
    private final JdbcTemplate jdbc;
    private final String deadLetterSql;
    private final String streamKey;
    private final String group;
    private final String consumerName;
    private final long maxlen;
    private final byte[] streamKeyBytes;
    private final byte[] payloadFieldBytes;

    /** 消费组只建一次；建失败（Redis 挂）时留在 false，下一次投递再试，不缓存「不可用」这个结论。 */
    private volatile boolean groupReady;

    public RedisStreamEventQueue(StringRedisTemplate template, DataSource dataSource) {
        this(template, dataSource, DEFAULT_STREAM_KEY, defaultConsumerName());
    }

    public RedisStreamEventQueue(
            StringRedisTemplate template, DataSource dataSource, String streamKey, String consumerName) {
        this(template, dataSource, streamKey, consumerName, 1_000_000L, true);
    }

    /** @param postgres 死信 SQL 方言：真库走 {@code ?::jsonb}，H2 测试传 {@code false}（与 PG 实现同一套做法） */
    RedisStreamEventQueue(
            StringRedisTemplate template,
            DataSource dataSource,
            String streamKey,
            String consumerName,
            long maxlen,
            boolean postgres) {
        this.template = template;
        this.jdbc = new JdbcTemplate(dataSource);
        this.deadLetterSql = postgres ? PG_DEAD_LETTER : H2_DEAD_LETTER;
        this.streamKey = streamKey == null || streamKey.isBlank() ? DEFAULT_STREAM_KEY : streamKey;
        this.group = DEFAULT_CONSUMER_GROUP;
        this.consumerName = consumerName == null || consumerName.isBlank() ? defaultConsumerName() : consumerName;
        this.maxlen = maxlen <= 0 ? 1_000_000L : maxlen;
        this.streamKeyBytes = this.streamKey.getBytes(StandardCharsets.UTF_8);
        this.payloadFieldBytes = FIELD_PAYLOAD.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String type() {
        return TYPE;
    }

    /**
     * 投递：{@code XADD stream MAXLEN ~ 100 万 * payload <事件 JSON>}。
     *
     * <p>**不阻塞用户**（§19.6）：一次往返，失败就抛给上层记「等待追赶投递」，绝不在这里重试或等待。
     */
    @Override
    public void publish(AgentEvent event) {
        ensureGroup();
        Map<byte[], byte[]> fields = new LinkedHashMap<>();
        fields.put(payloadFieldBytes, AgentEventCodec.encode(event).getBytes(StandardCharsets.UTF_8));
        MapRecord<byte[], byte[], byte[]> record = MapRecord.create(streamKeyBytes, fields);
        withCommands(commands -> commands.xAdd(
                record, XAddOptions.maxlen(maxlen).approximateTrimming(true)));
    }

    /**
     * 取一批待落库事件：**先取新消息，再补退避到期的未确认条目**，同一批内按位点去重。
     *
     * <p>顺序刻意这样定：新消息代表「用户刚产生的数据」，让它排在一堆重试后面只会让事实表更旧。
     * 未确认条目只翻最老的 {@code 4×max} 条（{@code XPENDING} 的翻页方式）：同一批失败的事件几乎同时
     * 到期，所以扫最老的一段就够；万一看漏，下一轮还会被扫到——**迟到，但不丢**。
     */
    @Override
    public List<QueueRecord> poll(int max) {
        if (max <= 0) {
            return List.of();
        }
        ensureGroup();
        Map<String, QueueRecord> batch = new LinkedHashMap<>();
        for (ByteRecord record : readNew(max)) {
            QueueRecord queueRecord = toRecord(record, 0);
            if (queueRecord != null) {
                batch.put(queueRecord.id(), queueRecord);
            }
        }
        if (batch.size() < max) {
            for (QueueRecord record : readDueRetries(max - batch.size())) {
                batch.putIfAbsent(record.id(), record);
            }
        }
        return List.copyOf(batch.values());
    }

    /** 确认已落库：{@code XACK}，从待确认集合里摘掉（条目本身留在流里，等 MAXLEN 裁）。 */
    @Override
    public void ack(List<QueueRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        RecordId[] ids = records.stream().map(record -> RecordId.of(record.id())).toArray(RecordId[]::new);
        withCommands(commands -> commands.xAck(streamKeyBytes, group, ids));
    }

    /**
     * 本次投递失败：**不确认**，交给队列退避重试。
     *
     * <p>具体做法是把自己再认领一次（{@code XCLAIM}）：空闲计时归零（退避从此刻起算），
     * 投递计数 +1（下一轮 {@code poll} 读到的 {@code attempts} 就多 1）。PG 实现做的是同一件事，
     * 只是写成 {@code attempts + 1, next_attempt_at = now + backoff}。
     */
    @Override
    public void nack(List<QueueRecord> records, String reason) {
        if (records == null || records.isEmpty()) {
            return;
        }
        // attempts 是「已经失败过几次」，投递计数是它 + 1；这次失败之后要推进到 attempts + 2。
        Map<Long, List<String>> byRetryCount = new LinkedHashMap<>();
        for (QueueRecord record : records) {
            byRetryCount
                    .computeIfAbsent((long) record.attempts() + 2, key -> new ArrayList<>())
                    .add(record.id());
        }
        for (Map.Entry<Long, List<String>> entry : byRetryCount.entrySet()) {
            String[] ids = entry.getValue().toArray(String[]::new);
            withCommands(commands -> commands.xClaim(
                    streamKeyBytes,
                    group,
                    consumerName,
                    XClaimOptions.minIdle(Duration.ZERO).ids(ids).retryCount(entry.getKey())));
        }
        // 失败原因在这里只进日志：流里的条目不可改，PG 实现能把 last_error 写在行上，Redis 不能。
        // 告警与排障靠 EventQueueMetrics.deadLettered 的 reason（§10.2），不靠流内容。
        log.warn("事件投递失败（不确认，等待退避重投）：条数={}，原因={}", records.size(), truncate(reason));
    }

    /**
     * 超过重试上限：**先写死信表，再出队**。
     *
     * <p>与 PG 实现同一个崩溃窗口：两步之间挂掉只会留下重复死信（可见、可人工去重），不会丢事件。
     */
    @Override
    public void deadLetter(QueueRecord record, String reason) {
        jdbc.update(
                deadLetterSql,
                record.eventId(),
                AgentEventCodec.encode(record.event()),
                truncate(reason),
                record.attempts() + 1,
                Timestamp.from(Instant.now()));
        withCommands(commands -> commands.xAck(streamKeyBytes, group, RecordId.of(record.id())));
        log.error("事件进入死信表（不无限重投，必须有人看）：eventId={}，原因={}", record.eventId(), truncate(reason));
    }

    /**
     * 水位（§10.2）：{@code XPENDING} 汇总给待确认条数与最老一条的入队时间，死信数直查 PG。
     *
     * <p>{@code oldestPendingEpochMs} 取的是**消息 id 里的时间戳**（Redis 自动生成的 id 就是入队时刻），
     * 所以「落后多久」在多副本下也是同一个数，不依赖任何本地时钟。
     */
    @Override
    public QueueStats stats() {
        Long dead = jdbc.queryForObject(DEAD_LETTER_COUNT, Long.class);
        long deadLettered = dead == null ? 0L : dead;
        PendingMessagesSummary pending;
        try {
            ensureGroup();
            pending = withCommands(commands -> commands.xPending(streamKeyBytes, group));
        } catch (RuntimeException e) {
            // 观测路径不该把服务打挂：Redis 挂了这里给 0，投递路径的失败另有告警（§10.2）。
            log.warn("读取 Redis Stream 水位失败（按零积压处理，投递侧会另外告警）", e);
            return new QueueStats(0L, deadLettered, 0L);
        }
        if (pending == null) {
            return new QueueStats(0L, deadLettered, 0L);
        }
        long pendingCount = pending.getTotalPendingMessages();
        // 只在真有积压时才去读最老一条：Spring 的 minRecordId() 在空集合上直接 get()，
        // 积压归零时抛 NoSuchElementException——常态是 0，不能拿异常当「没有积压」的表达方式。
        long oldestEpochMs = 0L;
        if (pendingCount > 0L) {
            RecordId oldest = pending.minRecordId();
            Long timestamp = oldest == null ? null : oldest.getTimestamp();
            oldestEpochMs = timestamp == null ? 0L : timestamp;
        }
        return new QueueStats(pendingCount, deadLettered, oldestEpochMs);
    }

    /** 消费组名（观测与测试用）。 */
    public String consumerGroup() {
        return group;
    }

    /** 流名（观测与测试用）。 */
    public String streamKey() {
        return streamKey;
    }

    /** 本副本的消费者名（多副本时必须互不相同，否则待确认条目会被认成「自己的」）。 */
    public String consumerName() {
        return consumerName;
    }

    /**
     * 取新消息（{@code XREADGROUP ... >}）：只拿到**从未投递给本消费组**的条目。
     *
     * <p>没有 {@code BLOCK}：{@link EventQueue#poll(int)} 的契约是「空队列立刻返回空表，不阻塞」，
     * 驱动节奏由 {@code EventPersistScheduler} 决定。
     */
    private List<ByteRecord> readNew(int max) {
        List<ByteRecord> records = withCommands(commands -> commands.xReadGroup(
                Consumer.from(group, consumerName),
                StreamReadOptions.empty().count(max),
                StreamOffset.create(streamKeyBytes, ReadOffset.lastConsumed())));
        return records == null ? List.of() : records;
    }

    /**
     * 取「退避到期」的未确认条目：{@code XPENDING} 给出每条的投递计数与空闲时长，
     * 满足「空闲 ≥ 退避窗口」的才用 {@code XCLAIM} 认领回来（认领会把空闲计时归零）。
     *
     * <p>认领时显式写回 {@code RETRYCOUNT}：只重置空闲时间，不动投递计数——
     * 计数只在 {@link #nack} 里 +1，这样 {@code attempts} 严格等于「失败次数」，不会因为多认领一次就跳两格。
     */
    private List<QueueRecord> readDueRetries(int max) {
        long scan = (long) Math.max(max, 1) * PENDING_SCAN_FACTOR;
        PendingMessages pending =
                withCommands(commands -> commands.xPending(streamKeyBytes, group, XPendingOptions.unbounded(scan)));
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<String> dueIds = new ArrayList<>();
        Map<String, Integer> attemptsById = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (PendingMessage message : pending) {
            int attempts = (int) Math.max(0L, message.getTotalDeliveryCount() - 1);
            if (due(message, attempts, now)) {
                String id = message.getId().getValue();
                dueIds.add(id);
                attemptsById.put(id, attempts);
            }
        }
        if (dueIds.isEmpty()) {
            return List.of();
        }
        String[] ids = dueIds.toArray(String[]::new);
        List<ByteRecord> claimed = withCommands(commands -> commands.xClaim(
                streamKeyBytes,
                group,
                consumerName,
                XClaimOptions.minIdle(Duration.ZERO).ids(ids)));
        if (claimed == null) {
            return List.of();
        }
        List<QueueRecord> records = new ArrayList<>(claimed.size());
        for (ByteRecord record : claimed) {
            QueueRecord queueRecord = toRecord(record, attemptsById.getOrDefault(record.getId().getValue(), 0));
            if (queueRecord != null) {
                records.add(queueRecord);
            }
        }
        return records;
    }

    /** 退避判据：空闲时长 ≥ 下一次重试该等的窗口（同一批失败的事件因此几乎同时到期）。 */
    private static boolean due(PendingMessage message, int attempts, long now) {
        Duration idle = message.getElapsedTimeSinceLastDelivery();
        if (idle == null) {
            // 拿不到空闲时长就不猜：让它等满最长退避再重投，宁可迟到，不可忙等重投。
            return false;
        }
        return idle.compareTo(backoffFor(attempts + 1)) >= 0;
    }

    /**
     * 按消费组起始位点建组（{@code MKSTREAM} 顺带把流建出来）。
     *
     * <p>起始位点给 {@code 0} 而不是 {@code $}：{@code 0} 的效果与 PG 实现的
     * 「{@code delivered_at IS NULL} 全部投递」等价——流里已有的、从未投递给消费组的条目也会被投递。
     * {@code $} 会让「消费组成立之前入队的条目」永远不落库，而它们在本地日志里也许早已被裁掉。
     * 重复落库由 {@code event_id} 幂等吸收（§19.6）。
     *
     * <p>组已存在时 Redis 回 {@code BUSYGROUP}：这是**成功**的一种（组在，不用建），别当成故障。
     */
    private void ensureGroup() {
        if (groupReady) {
            return;
        }
        try {
            withCommands(commands -> commands.xGroupCreate(streamKeyBytes, group, ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
        groupReady = true;
    }

    private static boolean isBusyGroup(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按字节找载荷字段。
     *
     * <p>不能写成 {@code record.getValue().get(payloadFieldBytes)}：{@code byte[]} 的 {@code equals} 是
     * **引用相等**，而 Map 里的 key 是反序列化时新建的数组，永远不是同一个实例——查出来永远是 null，
     * 表现成「每条事件都缺 payload」。所以这里逐字节比。
     */
    private byte[] payloadOf(ByteRecord record) {
        for (Map.Entry<byte[], byte[]> field : record.getValue().entrySet()) {
            if (Arrays.equals(field.getKey(), payloadFieldBytes)) {
                return field.getValue();
            }
        }
        return null;
    }

    /** 流条目 → 队列记录；载荷不是合法事件时**抛异常**（不静默丢弃，交人工看，与编解码器同一口径）。 */
    private QueueRecord toRecord(ByteRecord record, int attempts) {
        byte[] payload = payloadOf(record);
        if (payload == null) {
            throw new IllegalStateException(
                    "Redis Stream 条目缺少 " + FIELD_PAYLOAD + " 字段（换队列实现不兼容的载荷）：id=" + record.getId());
        }
        AgentEvent event = AgentEventCodec.decode(new String(payload, StandardCharsets.UTF_8));
        return new QueueRecord(record.getId().getValue(), event, attempts);
    }

    private <T> T withCommands(Function<RedisStreamCommands, T> action) {
        return template.execute((RedisCallback<T>) connection -> action.apply(connection.streamCommands()));
    }

    private static Duration backoffFor(int attempt) {
        long seconds = (long) Math.min(MAX_BACKOFF.toSeconds(), Math.pow(2, Math.max(0, attempt - 1)));
        return Duration.ofSeconds(Math.max(1L, seconds));
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /** 消费者名默认取「主机名-pid」：多副本同名会把别人的待确认条目认成自己的（§2.3）。 */
    static String defaultConsumerName() {
        String host = "local";
        try {
            host = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            // 取不到主机名不影响唯一性：pid 仍然区分实例
        }
        return host + "-" + ProcessHandle.current().pid();
    }
}