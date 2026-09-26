package com.djzy.assistant.agentweb.session.bus;

/**
 * 总线条目的编号规则：**定宽十进制、单调递增**，所以「按字符串比大小」就是「按先后比大小」。
 *
 * <p>这条规则单独收在这里，是因为有两个地方要用它，而且必须用同一套：
 * <ul>
 *   <li>写的人（{@code logAppend}）按它发号；</li>
 *   <li>读的人（{@code logRead}）按它判断「这条是不是比我的游标新」。</li>
 * </ul>
 *
 * <p>{@code %020d} 的宽度不是随意的：它要比「long 的最大值」（19 位）还宽，
 * 这样定宽才不会被更长的数字破坏；两边各写一遍格式串是最容易改歪的一种重复
 * ——改歪的表现是「游标永远追不上」，也就是跨实例实时续看静默失效。
 */
final class BusEntryId {

    private BusEntryId() {}

    /** 把自增序号格式化成可比较的条目号。 */
    static String format(long value) {
        return String.format("%020d", value);
    }

    /**
     * 这条是不是比游标新。
     *
     * @param since 上一次读到的条目号；空 / null 表示「从头读」
     */
    static boolean isAfter(String entryId, String since) {
        return since == null || since.isBlank() || entryId.compareTo(since) > 0;
    }
}
