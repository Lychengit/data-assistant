package com.djzy.assistant.management.skill;

/**
 * 一条自动检查结论（§18.4.6 M3）。
 *
 * @param code 检查项编码（稳定标识，便于统计与回归）
 * @param severity {@code blocking}（不过就不许发布）/ {@code warning}（只提示）
 * @param passed 是否通过
 * @param detail 说明（给评审人看的，不是给前端拼措辞用的）
 */
public record SkillCheckResult(String code, String severity, boolean passed, String detail) {

    public static final String BLOCKING = "blocking";
    public static final String WARNING = "warning";

    public static SkillCheckResult blocking(String code, boolean passed, String detail) {
        return new SkillCheckResult(code, BLOCKING, passed, detail);
    }

    public static SkillCheckResult warning(String code, boolean passed, String detail) {
        return new SkillCheckResult(code, WARNING, passed, detail);
    }

    public boolean blockingFailure() {
        return BLOCKING.equals(severity) && !passed;
    }
}
