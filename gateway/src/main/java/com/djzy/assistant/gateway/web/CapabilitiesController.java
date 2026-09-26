package com.djzy.assistant.gateway.web;

import com.djzy.assistant.common.api.ApiDescriptor;
import com.djzy.assistant.common.api.ApiRegistry;
import com.djzy.assistant.common.permission.ApiCapability;
import com.djzy.assistant.common.permission.AuthorizationService;
import com.djzy.assistant.common.permission.Capabilities;
import com.djzy.assistant.common.web.auth.CurrentUser;
import com.djzy.assistant.common.web.auth.UserContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * §19.8 capabilities 查询（由网关提供，不放在 management-service）。
 *
 * <p>它是每轮对话的热路径，与 G1 身份机制同源，避免 agent 侧同时持有两套凭证。
 * 同样不接受调用方传 userId：身份从用户凭证推导（§20.1.6-5）。
 * 零缓存（直查 PG）——权限变更即时生效，不存在缓存漂移（§0.3-4）。
 */
@RestController
public class CapabilitiesController {

    private final AuthorizationService authorizationService;
    private final ApiRegistry apiRegistry;

    public CapabilitiesController(AuthorizationService authorizationService, ApiRegistry apiRegistry) {
        this.authorizationService = authorizationService;
        this.apiRegistry = apiRegistry;
    }

    @GetMapping(path = "/v1/permission/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> capabilities(@CurrentUser UserContext userContext) {
        // 身份与账号状态由 UserContextInterceptor 统一验过（没验过根本进不来），这里只做业务
        String userId = userContext.userId();
        Capabilities capabilities = new Capabilities(
                userId,
                List.copyOf(authorizationService.viewableSkills(userId)),
                contractsOf(authorizationService.apiSet(userId)),
                Instant.now());
        return ResponseEntity.ok(capabilities);
    }

    /**
     * 把「授权到的注册行 id」补成「带路由与契约的接口」。
     *
     * <p>补的四样：{@code service/httpMethod/httpPath}（agent 调用时要回传的身份）、
     * {@code paramSchema}（怎么填）、{@code scenario}（什么时候用）、{@code resultSchema}
     * （能拿到什么字段）。后两样只能进工具描述——OpenAI 兼容的工具定义里没有 outputSchema 的位置，
     * 而模型恰恰最需要它来避免编字段名。
     *
     * <p>为什么必须补路由：agent-service 拿到工具名后要把三元组回传给网关（网关按三元组查注册行）。
     * 只给工具名的话，agent 得自己维护"名字 → 路由"的映射，多一份会漂移的清单。
     *
     * <p>已停用的接口直接不进契约明细：授权里有、但当前停用，属于配置不一致，
     * 不该让模型看见一个注定调不通的工具（调用会走 404）。
     */
    private List<ApiCapability> contractsOf(Collection<Long> apiIds) {
        List<ApiCapability> contracts = new ArrayList<>();
        for (ApiDescriptor descriptor : apiRegistry.findByIds(apiIds)) {
            if (!descriptor.enabled()) {
                continue;
            }
            contracts.add(new ApiCapability(
                    descriptor.service(),
                    descriptor.httpMethod(),
                    descriptor.httpPath(),
                    descriptor.name(),
                    descriptor.kind().name().toLowerCase(Locale.ROOT),
                    descriptor.resource(),
                    descriptor.paramSchema(),
                    descriptor.scenario(),
                    descriptor.resultSchema()));
        }
        return List.copyOf(contracts);
    }
}