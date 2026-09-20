package com.djzy.assistant.management.service;

import com.djzy.assistant.common.identity.PasswordHasher;
import com.djzy.assistant.common.identity.RefreshTokenStore;
import com.djzy.assistant.common.identity.UserAccount;
import com.djzy.assistant.common.identity.UserAccountPort;
import com.djzy.assistant.common.identity.UserTokenIssuer;
import com.djzy.assistant.common.security.Hmac;
import com.djzy.assistant.management.config.ManagementProperties;
import com.djzy.assistant.common.permission.AuthorizationService;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * M1 登录与令牌（§18.4.6 / §19.4）。
 *
 * <p>要点：
 * <ul>
 *   <li>访问令牌 = JWT，≤15 分钟（上限由 {@code JwtTokenService} 硬卡）；
 *   <li>刷新令牌 = 随机串，**库里只存 SHA-256**，一次性消费（轮换防重放）；
 *   <li>登录失败一律统一 401 措辞（不区分「用户不存在 / 口令不对 / 已停用」，防探测）；
 *   <li>账号状态在每次刷新时**重查**（停用立即失效，不用等 token 过期）。
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserAccountPort accountPort;
    private final UserTokenIssuer tokenIssuer;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthorizationService authorizationService;
    private final Duration tokenTtl;
    private final Duration refreshTtl;

    public AuthService(
            UserAccountPort accountPort,
            UserTokenIssuer tokenIssuer,
            RefreshTokenStore refreshTokenStore,
            AuthorizationService authorizationService,
            ManagementProperties properties) {
        this.accountPort = accountPort;
        this.tokenIssuer = tokenIssuer;
        this.refreshTokenStore = refreshTokenStore;
        this.authorizationService = authorizationService;
        this.tokenTtl = properties.getTokenTtl();
        this.refreshTtl = properties.getRefreshTtl();
    }

    /** @throws UnauthorizedException 账号不存在 / 口令不对 / 账号停用 */
    public AuthResult login(String username, String password) {
        UserAccount account = username == null || username.isBlank() || password == null || password.isEmpty()
                ? null
                : accountPort.findByUsername(username).orElse(null);
        if (account == null || !account.active() || !PasswordHasher.verify(password, account.passwordHash())) {
            log.warn("登录失败：username={}（原因不对外区分，§4.5）", username);
            throw new UnauthorizedException();
        }
        return issue(account);
    }

    /** 刷新令牌轮换：旧的立即作废，换一对新的（§19.4）。 */
    @Transactional
    public AuthResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new UnauthorizedException();
        }
        String userId = refreshTokenStore
                .consume(hash(refreshToken), Instant.now())
                .orElseThrow(UnauthorizedException::new);
        UserAccount account = accountPort
                .findById(userId)
                .filter(UserAccount::active)
                .orElseThrow(UnauthorizedException::new);
        return issue(account);
    }

    /** 登出：作废刷新令牌（幂等；访问令牌是短效的，不引入黑名单）。 */
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenStore.revoke(hash(refreshToken), Instant.now());
        }
    }

    private AuthResult issue(UserAccount account) {
        List<String> roles = authorizationService.rolesOf(account.userId());
        String accessToken = tokenIssuer.issue(
                account.userId(), Map.of("name", account.displayName(), "roles", roles));
        String refreshToken = newRefreshToken();
        refreshTokenStore.save(hash(refreshToken), account.userId(), Instant.now().plus(refreshTtl));
        return new AuthResult(
                accessToken,
                "Bearer",
                tokenTtl.toSeconds(),
                refreshToken,
                refreshTtl.toSeconds(),
                account.userId(),
                account.displayName(),
                roles);
    }

    private static String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 刷新令牌等同口令：**只存哈希**。 */
    private static String hash(String refreshToken) {
        return Hmac.sha256Hex(refreshToken.getBytes(StandardCharsets.UTF_8));
    }
}
