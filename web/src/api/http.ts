/**
 * 统一的 HTTP 出口（§11.3 / §19.4）。
 *
 * 三条口径：
 *  - 令牌**只**放 `Authorization` 头，绝不进 URL / query / 日志（§19.4）；
 *  - 访问令牌是一次性的短效凭证，401 时用刷新令牌**换一次**再重试，换不回来就登出；
 *  - 服务端只回**统一措辞**（`{error, code}`），前端不猜原因、不拼措辞。
 */

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;

  constructor(status: number, message: string, code?: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
  }
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  headers?: Record<string, string>;
  /** 刷新令牌自身要传 false：否则 401 会递归调刷新接口 */
  retryOnUnauthorized?: boolean;
  signal?: AbortSignal;
}

let accessToken: string | null = null;
let refresher: (() => Promise<boolean>) | null = null;
const authListeners = new Set<(token: string | null) => void>();

/** 由 auth store 注入：怎么刷新、刷不到怎么登出，都由它决定。 */
export function configureAuth(options: {
  getToken: () => string | null;
  refresh: () => Promise<boolean>;
}): void {
  accessToken = options.getToken();
  refresher = options.refresh;
}

/** 令牌换了要同步给 HTTP 层（登录、刷新、登出都会走这里）。 */
export function setAccessToken(token: string | null): void {
  accessToken = token;
  authListeners.forEach((listener) => listener(token));
}

export function onAccessTokenChanged(listener: (token: string | null) => void): () => void {
  authListeners.add(listener);
  return () => authListeners.delete(listener);
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const response = await send(path, options);
  if (response.status === 401 && options.retryOnUnauthorized !== false && refresher) {
    if (await refresher()) {
      const retried = await send(path, { ...options, retryOnUnauthorized: false });
      return parse<T>(retried);
    }
  }
  return parse<T>(response);
}

async function send(path: string, options: RequestOptions): Promise<Response> {
  const headers: Record<string, string> = { Accept: "application/json", ...options.headers };
  if (options.body !== undefined && !(options.body instanceof FormData)) {
    headers["Content-Type"] = "application/json; charset=utf-8";
  }
  if (accessToken) {
    headers["Authorization"] = `Bearer ${accessToken}`;
  }
  return fetch(path, {
    method: options.method ?? "GET",
    headers,
    body:
      options.body === undefined || options.body instanceof FormData
        ? (options.body as BodyInit | undefined)
        : JSON.stringify(options.body),
    signal: options.signal,
    // 刷新令牌是 httpOnly 之外的凭证，跨站请求一律不带上（服务端也没开 CORS 凭据）
    credentials: "same-origin"
  });
}

async function parse<T>(response: Response): Promise<T> {
  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  let payload: unknown = undefined;
  if (text) {
    try {
      payload = JSON.parse(text);
    } catch {
      payload = undefined;
    }
  }
  if (!response.ok) {
    const body = (payload ?? {}) as { error?: string; code?: string };
    throw new ApiError(response.status, body.error ?? `请求失败（HTTP ${response.status}）`, body.code);
  }
  return payload as T;
}
