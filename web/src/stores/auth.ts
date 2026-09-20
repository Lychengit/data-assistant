import { computed, reactive } from "vue";
import { ApiError, configureAuth, request, setAccessToken } from "@/api/http";

/**
 * 登录态（§19.4）：无状态 JWT + 一次性刷新令牌。
 *
 *  - 访问令牌 ≤15 分钟，刷新令牌**用完即换**（服务端轮换），所以这里必须保存新的一对；
 *  - 存 `sessionStorage` 而不是 `localStorage`：关掉标签页就没了，减少共享电脑上的暴露面；
 *  - 角色只用于**显示**（是否展示管理入口），判定一律以服务端为准（§19.4）。
 */

export interface AuthState {
  token: string | null;
  refreshToken: string | null;
  userId: string | null;
  displayName: string | null;
  roles: string[];
  /** 访问令牌到期的本地时刻（毫秒）；只用于提前刷新，不作为授权依据 */
  expiresAt: number;
  refreshing: Promise<boolean> | null;
}

const STORAGE_KEY = "doctor-assistant.auth";

export const auth = reactive<AuthState>({
  token: null,
  refreshToken: null,
  userId: null,
  displayName: null,
  roles: [],
  expiresAt: 0,
  refreshing: null
});

export const isLoggedIn = computed(() => auth.token !== null);
export const isAdmin = computed(() => auth.roles.includes("admin"));

interface AuthResult {
  token: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
  refreshExpiresIn: number;
  userId: string;
  displayName: string;
  roles: string[];
}

export async function login(username: string, password: string): Promise<void> {
  const result = await request<AuthResult>("/v1/auth/login", {
    method: "POST",
    body: { username, password },
    retryOnUnauthorized: false
  });
  apply(result);
}

export async function logout(): Promise<void> {
  const token = auth.refreshToken;
  clear();
  if (token) {
    try {
      await request<void>("/v1/auth/logout", {
        method: "POST",
        body: { refreshToken: token },
        retryOnUnauthorized: false
      });
    } catch {
      // 登出是幂等的：网络失败不影响本地已经清干净
    }
  }
}

/** 刷新令牌轮换：**旧的立即作废**，服务端换回一对新的（§19.4）。 */
async function refresh(): Promise<boolean> {
  if (!auth.refreshToken) {
    return false;
  }
  if (auth.refreshing) {
    return auth.refreshing;
  }
  auth.refreshing = (async () => {
    try {
      const result = await request<AuthResult>("/v1/auth/refresh", {
        method: "POST",
        body: { refreshToken: auth.refreshToken },
        retryOnUnauthorized: false
      });
      apply(result);
      return true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        clear();
      }
      return false;
    } finally {
      auth.refreshing = null;
    }
  })();
  return auth.refreshing;
}

/** 进对话页前先确保手里有一张还没过期的令牌（省掉一次必然 401 的往返）。 */
export async function ensureFreshToken(): Promise<boolean> {
  if (!auth.token) {
    return false;
  }
  if (auth.refreshToken && Date.now() > auth.expiresAt - 30_000) {
    return refresh();
  }
  return true;
}

function apply(result: AuthResult): void {
  auth.token = result.token;
  auth.refreshToken = result.refreshToken;
  auth.userId = result.userId;
  auth.displayName = result.displayName;
  auth.roles = result.roles ?? [];
  auth.expiresAt = Date.now() + result.expiresIn * 1000;
  setAccessToken(auth.token);
  persist();
}

function clear(): void {
  auth.token = null;
  auth.refreshToken = null;
  auth.userId = null;
  auth.displayName = null;
  auth.roles = [];
  auth.expiresAt = 0;
  setAccessToken(null);
  sessionStorage.removeItem(STORAGE_KEY);
}

function persist(): void {
  sessionStorage.setItem(
    STORAGE_KEY,
    JSON.stringify({
      token: auth.token,
      refreshToken: auth.refreshToken,
      userId: auth.userId,
      displayName: auth.displayName,
      roles: auth.roles,
      expiresAt: auth.expiresAt
    })
  );
}

/** 启动时恢复会话：刷新页面不该被踢回登录页（§19.4 断线续传）。 */
export function restore(): void {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return;
  }
  try {
    const saved = JSON.parse(raw) as Omit<AuthState, "refreshing">;
    auth.token = saved.token;
    auth.refreshToken = saved.refreshToken;
    auth.userId = saved.userId;
    auth.displayName = saved.displayName;
    auth.roles = saved.roles ?? [];
    auth.expiresAt = saved.expiresAt ?? 0;
    setAccessToken(auth.token);
  } catch {
    sessionStorage.removeItem(STORAGE_KEY);
  }
}

configureAuth({ getToken: () => auth.token, refresh });
