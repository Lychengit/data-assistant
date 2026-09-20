package com.djzy.assistant.common.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiKind;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcApiRegistryTest {

    private JdbcApiRegistry registry;

    @BeforeEach
    void setUp() {
        DataSource dataSource = PersistenceTestSupport.dataSource();
        JdbcTemplate jdbc = PersistenceTestSupport.template(dataSource);
        jdbc.update("INSERT INTO sys_api (id, name, service, http_method, http_path, kind, resource, param_schema, scenario, result_schema, enabled) VALUES "
                + "(1,'医生绩效','interface-doctor','POST','/doctor/performance','read','doctor','{\"month\":\"string\"}','查某月门诊量时用它','{\"metric_value\":{\"type\":\"number\",\"description\":\"指标值\"}}',TRUE),"
                + "(2,'导出报表','interface-doctor','post','/report/export','write','report',NULL,NULL,NULL,FALSE)");
        registry = new JdbcApiRegistry(jdbc);
    }

    @Test
    void resolvesByRouteTriple() {
        ApiDescriptor descriptor = registry.findByRoute("interface-doctor", "POST", "/doctor/performance").orElseThrow();

        assertEquals(1L, descriptor.id());
        assertEquals("interface-doctor", descriptor.service());
        assertEquals("POST", descriptor.httpMethod());
        assertEquals("/doctor/performance", descriptor.httpPath());
        assertEquals(ApiKind.READ, descriptor.kind());
        assertEquals("string", descriptor.paramSchema().get("month"));
        assertTrue(descriptor.enabled());
        assertEquals("查某月门诊量时用它", descriptor.scenario());
        assertEquals("指标值", ((java.util.Map<?, ?>) descriptor.resultSchema().get("metric_value")).get("description"));
    }

    @Test
    void methodNameIsNormalizedSoLowercaseRowsStillResolve() {
        // 库里写 post、调用方说 POST，不该被当成两个接口（那样网关会静默 404）
        assertEquals(ApiKind.WRITE, registry.findByRoute("interface-doctor", "POST", "/report/export").orElseThrow().kind());
        assertFalse(registry.findByRoute("interface-doctor", "POST", "/report/export").orElseThrow().enabled());
    }

    @Test
    void unknownRouteIsEmpty() {
        assertTrue(registry.findByRoute("interface-doctor", "POST", "/nope").isEmpty());
        assertTrue(registry.findByRoute("interface-doctor", "GET", "/doctor/performance").isEmpty());
        assertTrue(registry.findByRoute("other-service", "POST", "/doctor/performance").isEmpty());
        assertTrue(registry.findByRoute(null, "POST", "/doctor/performance").isEmpty());
    }

    @Test
    void missingPathIsEmptyRatherThanFailingOpen() {
        assertTrue(registry.findByRoute("interface-doctor", "POST", "/../etc/passwd").isEmpty());
        assertTrue(registry.findByRoute("interface-doctor", "POST", "/doctor//performance").isEmpty());
    }

    @Test
    void findByIdsKeepsOnlyKnownRows() {
        assertEquals(1, registry.findByIds(java.util.List.of(1L, 999L)).size());
        assertTrue(registry.findByIds(java.util.List.of()).isEmpty());
        assertTrue(registry.findByIds(null).isEmpty());
    }
}