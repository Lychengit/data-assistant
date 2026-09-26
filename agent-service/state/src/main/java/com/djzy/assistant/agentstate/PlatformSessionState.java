package com.djzy.assistant.agentstate;

import io.agentscope.core.state.State;

/**
 * 平台自己的「会话档案」在框架状态库里的样子（会话列表 / 归档用）。
 *
 * <p>**为什么框架里没有这个、我们还得自己存**：框架的 {@code AgentState} 记的是「模型看见过什么」，
 * 它不回答产品侧的问题——这个会话叫什么名字、什么时候建的、用户有没有把它归档。
 * 这几件事属于平台自己的产品语义，所以单独存一份，但它和会话状态**共用同一张表、同一套寻址**
 * （{@code (user_id, session_id, state_key)}），不额外养一套存取代码。
 *
 * <p><b>三个「列表用」的字段（标题 / 提问条数 / 最后提问时刻）为什么存在这里</b>：
 * 它们本来能从对话正文现算，原来也确实是那么算的——代价是**打开一次会话列表就要把每个会话的
 * 整段对话都读一遍**（一个用户几百个会话就是几百次大对象读）。现在改成：**提问的那一刻顺手记三个数**，
 * 列表只读这份档案，一次正文都不碰（见 {@code SessionCatalog#list}）。
 *
 * <p>那会不会「多一个真相、迟早对不上」？会差一点，所以口径要写清楚：
 * <ul>
 *   <li>**正文永远是真相**：历史回放（{@code /turns}）读的还是框架的对话状态，一个字都不来自这里；</li>
 *   <li>这三个数只是**列表的索引**，允许落后：绕过平台直接往状态库里塞对话（导入、测试）时，列表不认它；</li>
 *   <li>落后是可自愈的：用户再问一句，标题与条数就跟上了。</li>
 * </ul>
 *
 * <p>**可序列化要求**：框架用 Jackson 把 State 存成 JSON 再读回来，所以这个类必须有
 * 无参构造 + getter/setter（不要改成不可变对象）。
 */
public final class PlatformSessionState implements State {

    /** 标题上限：列表里就是一行，存储里也不该出现超长文本。 */
    public static final int TITLE_MAX = 60;

    /** 会话号；与寻址用的那一维一致，便于从库里读出来时自证身份。 */
    private String sessionId;

    /** 建档时刻（epoch 毫秒）：会话刚建、还没问过任何问题时列表就要有它。 */
    private long createdAtMs;

    /** 用户是否把这个会话归档了（只是从列表里收起来，不是删除）。 */
    private boolean archived;

    /** 归档时刻（epoch 毫秒）；未归档为 0。 */
    private long archivedAtMs;

    /** 列表标题 = 用户问的第一句话（截断后的）；还没问过就是空，由列表显示成「未命名会话」。 */
    private String title;

    /** 提问条数（≈ 轮次数；HITL 确认续跑不算新提问）。 */
    private int questions;

    /** 最后一次提问的时刻（epoch 毫秒）：列表按它倒序，最近聊过的在最上面。 */
    private long lastActiveMs;

    public PlatformSessionState() {}

    public PlatformSessionState(String sessionId, long createdAtMs) {
        this.sessionId = sessionId;
        this.createdAtMs = createdAtMs;
    }

    /**
     * 记一次「这个会话又有人问了一句」：第一句定标题，之后只加条数、推进最后提问时刻。
     *
     * <p>放在 State 上而不是散在调用方：这三个字段是一组、必须一起更新，
     * 分开写迟早出现「条数加了、时刻没动」这种半截状态。
     *
     * @param question 用户的原话（可以为空：空话不设标题，但照样算一次提问）
     * @param nowMs 本次提问时刻
     */
    public void applyQuestion(String question, long nowMs) {
        if (title == null || title.isBlank()) {
            title = truncate(question);
        }
        questions++;
        lastActiveMs = nowMs;
    }

    /** 压平空白 + 截断：标题只该是一行短文本（超长由列表自己截断显示）。 */
    public static String truncate(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String oneLine = question.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX) + "…";
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public void setCreatedAtMs(long createdAtMs) {
        this.createdAtMs = createdAtMs;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public long getArchivedAtMs() {
        return archivedAtMs;
    }

    public void setArchivedAtMs(long archivedAtMs) {
        this.archivedAtMs = archivedAtMs;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getQuestions() {
        return questions;
    }

    public void setQuestions(int questions) {
        this.questions = questions;
    }

    public long getLastActiveMs() {
        return lastActiveMs;
    }

    public void setLastActiveMs(long lastActiveMs) {
        this.lastActiveMs = lastActiveMs;
    }
}
