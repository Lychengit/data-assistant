package com.djzy.assistant.agentweb.prompt;

import com.djzy.assistant.common.time.BusinessTime;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * 主对话的系统提示词（§5.1 / ADR-30「系统提示词拼装」+ §6.6 时间口径）。
 *
 * <p>为什么必须有这一段：模型既不知道业务时区、也不知道「今天」是哪天。实测中它把「上个月」
 * 猜成了 2026-07，先查空、再连查六次，最后交出一份「你要的月份没数据」的排名——数据本身没错，
 * 但口径被模型自己改了。跨年、闰月、月末更不该由模型来算（§6.6：相对时间由程序解析）。
 *
 * <p>所以这里把相对时间**算好写死**再交给模型：它只做「词 → 口径」的映射，不做日期计算。
 *
 * <p>为什么还要把**本轮工具清单**写进来（清单本来就在工具定义里）：工具定义管「怎么调」，
 * 但它管不了「模型怎么回答自己的本领」。实测：admin 的 `doctor_list` 授权在 22:52:2x 被撤销，
 * 新建会话里问「你有哪些能力」答得是对的，而**同一个用户在 19:36 建的老会话里再问，模型还能列出
 * 「医生名单查询」**——它读的是自己前几轮的回答，不是这一轮的工具面（该轮 `agent_step` 里只有
 * `doctor_performance` 这一次调用）。权限是会被撤回的，历史对话不会跟着变，所以「这一轮有什么」
 * 必须每轮由平台写死在提示词里，并明确「以这里为准」。
 */
public final class SystemPromptComposer {

    /** 清单里每条描述最多留多长：这里只做锚点，完整说明在工具 schema 里，不重复搬运。 */
    private static final int BRIEF_LIMIT = 80;

    private SystemPromptComposer() {}

    /**
     * @param now 这一轮的时间（相对时间口径按它解析，§6.6）
     * @param catalog 这一轮真正挂给模型的工具清单（就是 {@code AgentRunRequest.tools} 那一份，必须同源）
     */
    public static String compose(Instant now, ToolCatalog catalog) {
        LocalDate today = BusinessTime.dateOf(now);
        YearMonth month = BusinessTime.monthOf(today);
        YearMonth quarterStart = BusinessTime.quarterStartOf(today);
        BusinessTime.DateRange recent = BusinessTime.recentDaysOf(today, BusinessTime.RECENT_DAYS);

        StringBuilder prompt = new StringBuilder();
        prompt.append("你是「医生数据智能助理」，面向医院管理者回答与医生数据有关的问题。\n\n");
        prompt.append("【时间口径】（业务时区 Asia/Shanghai；下面这些值已由程序算好，直接采用，不要自己推算日期）\n");
        prompt.append("- 今天：").append(today).append('\n');
        prompt.append("- 本月：").append(month).append('\n');
        prompt.append("- 上个月：").append(BusinessTime.previousMonthOf(today)).append('\n');
        prompt.append("- 本季度：").append(quarterStart).append("-01 起（左闭右开，到 ")
                .append(quarterStart.plusMonths(3)).append("-01 不含）\n");
        prompt.append("- 上季度：").append(BusinessTime.previousQuarterStartOf(today)).append("-01 起（左闭右开，到 ")
                .append(BusinessTime.quarterStartOf(today)).append("-01 不含）\n");
        prompt.append("- 最近 ").append(BusinessTime.RECENT_DAYS).append(" 天：")
                .append(recent.start()).append(" 至 ").append(today).append('\n');
        prompt.append('\n');
        appendCapabilities(prompt, catalog);
        prompt.append("【硬约束】\n");
        prompt.append("- 只用工具返回的数据作答。没有返回的数据不得编造、不得推测，也不要用「通常」「大约」补空。\n");
        prompt.append("- 用户说的相对时间就用上面已解析的值；该口径确实没有数据，就直说「该口径无数据」，不要自行换一个月份代替用户决定。\n");
        prompt.append("- 「本轮可用接口」是**这一轮唯一权威**的能力清单，每轮刷新；它可能比你前几轮看到的少（授权被撤销立即生效）。\n");
        prompt.append("- 清单里没有的能力就是没有：不要先去调一个还算沾边的接口、拿空结果当答复，直接说明当前没有这个能力。\n");
        prompt.append("- 全程用中文：开场白、步骤说明、调用工具前后的话、结论、对用户的追问，一个英文词都不要出现。\n");
        prompt.append("  实测过的错法：中文结论外面套一句英文旁白（如「I'll load the export skill…」）——那同样不合规。\n");
        prompt.append("  只有这几类可以保持原样：工具名、接口路径、字段名、指标键、文件名、代码与命令。\n");
        prompt.append("- 直接给结论，不要写「我先去查询…」这类过程旁白。\n");
        prompt.append('\n');
        prompt.append("【工具使用】（入参契约见工具 schema，§4.8 节点 A）\n");
        prompt.append("- 过滤参数（dept_code / doctor_id）只在用户明确给出时才传；用户没说就不要传，让网关按授权范围返回全部。\n");
        prompt.append("- 编码一律不许猜：不要把「心内科」自行翻成 cardiology 这类英文编码，也不要凭印象编造 id。\n");
        prompt.append("  要按科室过滤，先调 doctor_list（不传 dept_code）看返回里 dept_code 的真实取值，再用它过滤。\n");
        prompt.append("- 工具返回空结果时如实回答「该口径无数据」，并先检查是不是自己多传了过滤条件；不得编造空结果的原因。\n");
        prompt.append('\n');
        prompt.append("【能力问题的作答口径】（用户问「你有哪些能力 / 有哪些技能 / 有哪些接口 / 你能干什么」时）\n");
        prompt.append("- 先看【本轮可用接口】，再开口；只按它逐项回答，并说明这是「本轮」的能力。\n");
        prompt.append("- 历史对话里**你自己**说过的能力不算数：那是当时的清单，授权随时可能被撤销，而历史不会跟着变。\n");
        prompt.append("  实测过的那种错法：历史里答过「我能查医生名单」，本轮清单里已经没有这个接口，模型却接着说\n");
        prompt.append("  「我可以先拉取一份医生名单给您看看」——用户以为能查，实际一查就失败。\n");
        prompt.append("- 历史里提过、本轮清单里没有的：只能说明「这项能力当前不可用（授权已变更）」，不要写成「我能」，\n");
        prompt.append("  也不要承诺稍后去查、不要说「需要我现在拉取吗」这类会把用户带进死胡同的话。\n");
        prompt.append("- 清单为空就直接说「当前没有任何可用接口」，不要用历史的说法把空档填上。\n");
        prompt.append('\n');
        // 收口再写一次：指令的位置影响遵守率，**最后一条**离生成最近（实测：写在开头的语言要求
        // 会被英文旁白盖过去，见 2026-09-27 的对话记录）。
        prompt.append("【输出语言】现在就检查一遍：你接下来输出的每一个字都必须是中文。\n");
        prompt.append("过程旁白也要中文——不要出现「I'll …」「Now …」这类英文句子；工具名 / 路径 / 字段名 / 文件名除外。\n");
        return prompt.toString();
    }

    /**
     * 贴在**本轮提问之后**的能力快照（一两行）。
     *
     * <p>和 {@link #compose} 里那份清单内容同源、位置不同：那份在消息列表最前面，这份紧挨着提问。
     * 为什么要贴两次：老会话的历史里存着模型自己上一轮的能力描述，它离提问更近，模型会照着抄
     * （实测：撤销 `doctor_list` 后在同一会话再问「你有哪些能力」，模型把上一轮列的名单能力原样又答了一遍，
     * 而那一轮的工具面里只有 performance）。规则写在开头会被忽略，写在问题旁边才拦得住。
     *
     * <p>刻意写成「本轮快照、旧的可能过期」：这段会随消息历史累积，标明快照语义之后，
     * 模型可以把更早的快照当历史看，而不是把几条互相冲突的清单摆在一起选。
     */
    public static String turnReminder(ToolCatalog catalog) {
        List<ToolSpec> tools = catalog == null ? List.of() : catalog.all();
        StringBuilder text = new StringBuilder();
        text.append("(本轮能力快照：");
        if (tools.isEmpty()) {
            text.append("本轮没有挂任何数据接口");
        } else {
            text.append("本轮可用接口 = ");
            text.append(tools.stream().map(ToolSpec::name).collect(java.util.stream.Collectors.joining("、")));
        }
        text.append("。历史轮次里的能力快照都可能已过期（授权随时会被撤销），作答一律以最新这一条为准；");
        text.append("用户问能力时只按它回答，不要沿用历史里出现过的接口。");
        // 顺带钉一句语言要求：它贴在**提问旁边**，比写在系统提示词开头更拦得住英文旁白。
        text.append("作答与过程旁白一律用中文，不要输出英文句子。)");
        return text.toString();
    }

    /**
     * 这一轮挂给模型的接口清单。
     *
     * <p>空清单要**写出「空」**而不是留一段空白：骨架形态（没接数据面）与「权限被全部撤销」都落在这里，
     * 模型看到一段明确的话才会老实说「当前没有可用接口」，否则它会顺着历史对话编。
     */
    private static void appendCapabilities(StringBuilder prompt, ToolCatalog catalog) {
        List<ToolSpec> tools = catalog == null ? List.of() : catalog.all();
        prompt.append("【本轮可用接口】\n");
        if (tools.isEmpty()) {
            prompt.append("- 空：这一轮没有挂任何数据接口（未授权或授权已被撤销）。用户要数据时直接说明当前没有可用接口。\n");
        } else {
            for (ToolSpec tool : tools) {
                prompt.append("- ").append(tool.name());
                String brief = briefOf(tool.description());
                if (!brief.isEmpty()) {
                    prompt.append('：').append(brief);
                }
                prompt.append('\n');
            }
        }
        prompt.append('\n');
    }

    /** 只取描述首行并限长：完整的两三百字说明已经在工具 schema 里，这里不搬运第二份。 */
    private static String briefOf(String description) {
        if (description == null || description.isBlank()) {
            return "";
        }
        int lineEnd = description.indexOf('\n');
        String line = (lineEnd < 0 ? description : description.substring(0, lineEnd)).trim();
        return line.length() <= BRIEF_LIMIT ? line : line.substring(0, BRIEF_LIMIT) + "…";
    }
}