package com.djzy.assistant.agentweb;

import com.djzy.assistant.common.bus.AgentEventFactWriter;
import com.djzy.assistant.common.bus.EventQueue;
import com.djzy.assistant.common.persistence.JdbcAgentEventFactWriter;
import com.djzy.assistant.common.persistence.PgOutboxEventBus;
import javax.sql.DataSource;
import com.djzy.assistant.agentweb.tool.ToolPlane;
import com.djzy.assistant.spi.tool.ToolCatalog;
import com.djzy.assistant.spi.tool.ToolCategory;
import com.djzy.assistant.spi.tool.ToolInvocationResult;
import com.djzy.assistant.spi.tool.ToolSpec;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 方言替身（§19.6）：生产实现用 PG 的 {@code ?::jsonb} 与 {@code ON CONFLICT}，
 * H2 换成纯文本列 + {@code MERGE ... KEY}，列名列序完全一致。
 * 与 management-service 的 {@code H2DialectConfig} 同一套做法。
 */
@TestConfiguration
public class AgentWebH2Config {

    @Bean
    @Primary
    public EventQueue h2EventQueue(DataSource dataSource) {
        return new PgOutboxEventBus(new JdbcTemplate(dataSource), false);
    }

    @Bean
    @Primary
    public AgentEventFactWriter h2AgentEventFactWriter(DataSource dataSource) {
        return new JdbcAgentEventFactWriter(new JdbcTemplate(dataSource), false);
    }

    /**
     * 测试用工具面：一个「首次调用要求确认、确认后才执行」的工具，用来把 HITL 闭环跑通（§19.9）。
     *
     * <p>生产形态是 {@code GatewayToolPlane}（清单与调用都只由网关给，§19.7）；这里只替换**来源**，
     * 工具调用通道本身照旧走运行时的 ToolInvoker，所以被测的是平台侧的挂起 / 确认 / 续跑。
     */
    @Bean
    @Primary
    public ToolPlane h2ToolPlane() {
        String toolName = "iface_doctor_performance_write";
        return new ToolPlane() {
            @Override
            public ToolCatalog catalogFor(String bearerToken) {
                return ToolCatalog.of(List.of(ToolSpec.read(
                        toolName, "登记门诊量（写操作，需要人工确认）", Map.of("type", "object"), ToolCategory.IFACE)));
            }

            @Override
            public com.djzy.assistant.spi.tool.ToolInvoker invokerFor(String bearerToken) {
                return request -> reactor.core.publisher.Mono.fromSupplier(() -> request.confirmId() == null
                        ? ToolInvocationResult.awaitingConfirm("c-test-1", "即将登记门诊量，需人工确认")
                        : ToolInvocationResult.ok("执行完成", Map.of("rows", 1)));
            }
        };
    }
}
