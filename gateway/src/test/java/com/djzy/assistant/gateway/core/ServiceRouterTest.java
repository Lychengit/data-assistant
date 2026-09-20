package com.djzy.assistant.gateway.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** G2 路由（§20.3）：URL 一律由**注册行**拼出，绝不使用请求体里的字符串。 */
class ServiceRouterTest {

    private final ServiceRouter router = new ServiceRouter("http://{service}:8080");

    @Test
    void routesByRegisteredServiceNameAndRegisteredPath() {
        assertEquals(
                "http://interface-doctor:8080/doctor/performance",
                router.uriFor(descriptor("interface-doctor", "POST", "/doctor/performance")).toString());
    }

    @Test
    void rejectsUnsafeServiceName() {
        // 服务名会被填进主机名，比路径更危险（SSRF 防线的最后一道）
        assertThrows(IllegalArgumentException.class, () -> router.uriFor(descriptor("bad/service", "POST", "/doctor/performance")));
        assertThrows(
                IllegalArgumentException.class,
                () -> router.uriFor(descriptor("interface-doctor:8080", "POST", "/doctor/performance")));
        assertThrows(IllegalArgumentException.class, () -> router.uriFor(descriptor("", "POST", "/doctor/performance")));
    }

    @Test
    void rejectsUnsafePathAtDescriptorConstruction() {
        // 路径穿越在**注册行构造时**就被拒（ApiRoute 的白名单正则），不会留到拼 URL 才发作
        assertThrows(IllegalArgumentException.class, () -> descriptor("interface-doctor", "POST", "/../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> descriptor("interface-doctor", "POST", "/doctor//performance"));
        assertThrows(IllegalArgumentException.class, () -> descriptor("interface-doctor", "POST", "/doctor?x=1"));
    }

    private static ApiDescriptor descriptor(String service, String httpMethod, String httpPath) {
        return new ApiDescriptor(
                1L, "医生绩效", service, httpMethod, httpPath, ApiKind.READ, "doctor", Map.of(), true, null, Map.of());
    }
}