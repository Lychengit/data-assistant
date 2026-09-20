package com.djzy.assistant.management.service;

import com.djzy.assistant.common.identity.UserIdentity;
import com.djzy.assistant.common.identity.UserStatusPort;
import com.djzy.assistant.common.identity.UserTokenVerifier;
import com.djzy.assistant.common.permission.AuthorizationService;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 当前用户解析与入口权限（§19.4 / §20.4）。
 *
 * <p>两条硬规则：
 * <ul>
 *   <li>**令牌只证明身份**：角色一律直查权限库（零缓存），所以「停用立即失效、撤权最多等旧 token 过期」；
 *   <li>管理入口**只有 admin 角色**可进；判定走 {@link AuthorizationService} 唯一实现，不重写一套。
 * </ul>
 */
@Service
public class CurrentUserService {

    /** 骨架期管理入口角色（§20.4：功能级权限后置，先只放 admin）。 */
    public static final String ADMIN_ROLE = "admin";

    private final UserTokenVerifier tokenVerifier;
    private final UserStatusPort userStatusPort;
    private final AuthorizationService authorizationService;

    public CurrentUserService(
            UserTokenVerifier tokenVerifier,
            UserStatusPort userStatusPort,
            AuthorizationService authorizationService) {
        this.tokenVerifier = tokenVerifier;
        this.userStatusPort = userStatusPort;
        this.authorizationService = authorizationService;
    }

    /** @throws UnauthorizedException 无令牌 / 令牌无效 / 账号已停用 */
    public UserIdentity requireUser(String authorizationHeader) {
        String userId = tokenVerifier
                .verifyAuthorizationHeader(authorizationHeader)
                .map(UserIdentity::userId)
                .orElse(null);
        if (userId == null || !userStatusPort.isActive(userId)) {
            throw new UnauthorizedException();
        }
        return UserIdentity.of(userId);
    }

    /** @throws ForbiddenException 已登录但不是 admin */
    public UserIdentity requireAdmin(String authorizationHeader) {
        UserIdentity identity = requireUser(authorizationHeader);
        if (!isAdmin(identity.userId())) {
            throw new ForbiddenException();
        }
        return identity;
    }

    /** 角色判定唯一实现的薄封装（§18.4.6：不重写第二份判定）。 */
    public boolean isAdmin(String userId) {
        return userId != null && authorizationService.rolesOf(userId).contains(ADMIN_ROLE);
    }

    public List<String> rolesOf(String userId) {
        return authorizationService.rolesOf(userId);
    }
}
