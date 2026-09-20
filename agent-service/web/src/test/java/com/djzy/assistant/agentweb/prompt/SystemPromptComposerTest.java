package com.djzy.assistant.agentweb.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 系统提示词里的时间口径（§6.6）与本轮接口清单。
 *
 * <p>断言的是「模型拿到的是算好的日期」：提示词里必须出现按业务时区解析出的今天与上个月，
 * 否则模型又得自己猜——那正是它把「上个月」猜错的那个失败。
 */
class SystemPromptComposerTest {

    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");

    private static ToolSpec tool(String name, String description) {
        return ToolSpec.read(name, description, Map.of("type", "object"), ToolCategory.IFACE);
    }

    @Test
    void 提示词带上业务时区解析后的相对时间() {
        String prompt = SystemPromptComposer.compose(Instant.parse("2026-08-31T17:00:00Z"), ToolCatalog.empty());

        assertThat(prompt).contains("今天：2026-09-01");
        assertThat(prompt).contains("上个月：2026-08");
        assertThat(prompt).contains("最近 7 天：2026-08-26 至 2026-09-01");
    }

    @Test
    void 提示词重申数据范围与不编造这两条硬约束() {
        String prompt = SystemPromptComposer.compose(NOW, ToolCatalog.empty());

        assertThat(prompt).contains("数据范围").contains("由网关按登录人的角色推导");
        assertThat(prompt).contains("不得编造");
        assertThat(prompt).contains("全程用中文");
    }

    @Test
    void 提示词禁止模型猜过滤参数与英文编码() {
        String prompt = SystemPromptComposer.compose(NOW, ToolCatalog.empty());

        // 实测失败：用户问「上个月心内科各医生的门诊量排名」，模型自作主张传 dept_code=cardiology，
        // 接口服务按这个不存在的编码过滤后返回空 → 模型交出一份「无数据」结论。dept_code 的真实取值是中文。
        assertThat(prompt).contains("不许猜").contains("cardiology");
        assertThat(prompt).contains("只在用户明确给出时才传");
    }

    /**
     * 清单里必须有这一轮真正挂上的工具，且只截首行（完整说明在工具 schema 里，不搬运第二份）。
     */
    @Test
    void 提示词列出本轮挂上的接口并只取描述首行() {
        ToolCatalog catalog = ToolCatalog.of(List.of(
                tool("iface_doctor_performance", "调用医生数据接口 iface_doctor_performance（医生绩效明细）。\n适用场景：按月份查明细"),
                tool("iface_doctor_list", "调用医生数据接口 iface_doctor_list（医生列表）。")));

        String prompt = SystemPromptComposer.compose(NOW, catalog);

        assertThat(prompt).contains("【本轮可用接口】");
        assertThat(prompt).contains("- iface_doctor_performance：调用医生数据接口 iface_doctor_performance（医生绩效明细）。");
        assertThat(prompt).contains("- iface_doctor_list：调用医生数据接口 iface_doctor_list（医生列表）。");
        // 首行之后的正文不进提示词：它是工具 schema 的活，重复一遍只会让提示词随接口数线性膨胀
        assertThat(prompt).doesNotContain("适用场景：按月份查明细");
    }

    /**
     * 权限被撤销后的那个实测失败：老会话的历史里还留着「我能查医生名单」，模型据此照答。
     *
     * <p>这里断言的是平台给的锚点：本轮清单里没有 `doctor_list`，提示词就要同时给出
     * ① 权威口径（以本轮清单为准）② 空清单的明确表达，别让模型拿历史把空档填满。
     */
    @Test
    void 撤销授权后的会话里提示词不再出现该接口且要求以本轮清单为准() {
        ToolCatalog afterRevoke = ToolCatalog.of(
                List.of(tool("iface_doctor_performance", "调用医生数据接口 iface_doctor_performance（医生绩效明细）。")));

        String prompt = SystemPromptComposer.compose(NOW, afterRevoke);

        assertThat(prompt).contains("iface_doctor_performance");
        assertThat(prompt).doesNotContain("iface_doctor_list");
        assertThat(prompt).contains("这一轮唯一权威");
        assertThat(prompt).contains("【能力问题的作答口径】");
        assertThat(prompt).contains("历史对话里**你自己**说过的能力不算数");
        // 这条守的是实测过的错法：历史里答过「我能查医生名单」，撤销后模型接着说「我可以先拉取一份名单给您看看」
        assertThat(prompt).contains("我可以先拉取一份医生名单给您看看");
    }

    /** 一个接口都没有时也要**写出「空」**：留空白的话，模型会顺着历史对话把它自己填满。 */
    @Test
    void 没有可用接口时提示词明确写出空清单() {
        String prompt = SystemPromptComposer.compose(NOW, ToolCatalog.empty());

        assertThat(prompt).contains("- 空：这一轮没有挂任何数据接口");
        assertThat(prompt).contains("直接说明当前没有可用接口");
    }

    /**
     * 贴到提问之后的那份快照：只列工具名，并且必须写明「以最新一条为准」。
     *
     * <p>这段会随消息历史累积，所以措辞里得有快照语义；否则几轮之后，模型会同时看到好几份
     * 互相冲突的清单，只能自己挑一份信——那正是这条机制要避免的。
     */
    @Test
    void 本轮能力快照只列名字且声明以最新为准() {
        String reminder = SystemPromptComposer.turnReminder(ToolCatalog.of(List.of(
                tool("iface_doctor_performance", "调用医生数据接口 iface_doctor_performance（医生绩效明细）。"),
                tool("iface_doctor_list", "调用医生数据接口 iface_doctor_list（医生列表）。"))));

        assertThat(reminder).contains("本轮可用接口 = iface_doctor_performance、iface_doctor_list");
        assertThat(reminder).contains("以最新这一条为准");
        assertThat(reminder).contains("不要沿用历史里出现过的接口");
    }

    /** 全撤销时快照也要写清楚「没有」，不能是一句空话。 */
    @Test
    void 没有工具时快照写明本轮没有任何接口() {
        String reminder = SystemPromptComposer.turnReminder(ToolCatalog.empty());

        assertThat(reminder).contains("本轮没有挂任何数据接口");
    }

    /** 空描述不能拼出「- iface_x：」这种带个空冒号的半截行。 */
    @Test
    void 描述为空时清单只写名字() {
        String prompt = SystemPromptComposer.compose(NOW, ToolCatalog.of(List.of(tool("py_demo", ""))));

        assertThat(prompt).contains("- py_demo\n");
        assertThat(prompt).doesNotContain("py_demo：");
    }
}