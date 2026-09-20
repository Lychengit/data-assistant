package com.djzy.assistant.management.web;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.management.service.AuthResult;
import com.djzy.assistant.management.service.AuthService;
import com.djzy.assistant.management.service.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** M1 登录与令牌（§18.4.6 / §19.4）。 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    /** 口令登录：成功返回短效访问令牌 + 一次性刷新令牌；失败统一 401（不区分原因）。 */
    @PostMapping(path = "/login", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResult> login(@RequestBody(required = false) LoginRequest request) {
        LoginRequest safe = request == null ? new LoginRequest(null, null) : request;
        return ResponseEntity.ok(authService.login(safe.username(), safe.password()));
    }

    /** 刷新：旧的刷新令牌立即作废（轮换），换一对新的。 */
    @PostMapping(path = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AuthResult> refresh(@RequestBody(required = false) RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request == null ? null : request.refreshToken()));
    }

    /** 登出：作废刷新令牌（幂等，不返回内容）。 */
    @PostMapping(path = "/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        authService.logout(request == null ? null : request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    /** 当前登录者（令牌只证明身份；角色与权限每次直查权限库，§19.4）。 */
    @GetMapping(path = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        UserIdentity identity = currentUserService.requireUser(authorization);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", identity.userId());
        body.put("roles", currentUserService.rolesOf(identity.userId()));
        return ResponseEntity.ok(body);
    }

    public record LoginRequest(String username, String password) {}

    public record RefreshRequest(String refreshToken) {}
}
