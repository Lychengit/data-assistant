import { request } from "@/api/http";

/**
 * 对话入口（§12.1 / §19.4）：**两步走**。
 *
 * 1. `POST /v1/agent/sessions/{id}/turns` 发消息 → 立刻拿到一张**一次性入场券**（消息在 POST 体里）；
 * 2. `GET /v1/agent/chat/stream?ticket=...` 用券换 SSE 连接。
 *
 * 令牌永远走头，只有**券**允许出现在 URL 里——这样代练 / 代理 / 日志都留不下可用的凭证。
 * 断开 / 刷新页面后要接着看，用 `resumeTicket()` 换一张新券（旧券已核销，浏览器原生重连必然 401）。
 */

export interface TicketBody {
  sessionId: string;
  turnId: string;
  ticket: string;
  expiresAt: string;
  streamUrl: string;
}

export async function createSession(): Promise<string> {
  const body = await request<{ sessionId: string }>("/v1/agent/sessions", { method: "POST" });
  return body.sessionId;
}

export function startTurn(sessionId: string, text: string): Promise<TicketBody> {
  return request<TicketBody>(`/v1/agent/sessions/${encodeURIComponent(sessionId)}/turns`, {
    method: "POST",
    body: { text }
  });
}

/** 换券续看：**不发起新轮次**（§19.4 断线续传）。 */
export function resumeTicket(sessionId: string): Promise<TicketBody> {
  return request<TicketBody>(`/v1/agent/sessions/${encodeURIComponent(sessionId)}/tickets`, {
    method: "POST"
  });
}

/** HITL 确认（§19.9）：确认后服务端续跑，并给一张新券让我们接着看。 */
export function confirm(sessionId: string, confirmId: string, approved: boolean): Promise<TicketBody> {
  return request<TicketBody>(`/v1/agent/sessions/${encodeURIComponent(sessionId)}/confirm`, {
    method: "POST",
    body: { confirmId, approved }
  });
}

/**
 * SSE 连接地址。
 *
 * @param afterSeq 已经收到的最后一条事件 seq——重连时带上，服务端只补后面那段（不重不漏）。
 *                 seq 是位置不是凭证，进 query 无妨（§19.4 只禁令牌）。
 */
export function streamUrl(ticket: string, afterSeq: number | null): string {
  const query = new URLSearchParams({ ticket });
  if (afterSeq !== null) {
    query.set("afterSeq", String(afterSeq));
  }
  return `/v1/agent/chat/stream?${query.toString()}`;
}

/**
 * 历史会话（§19.4）：列表与回放。
 *
 * 服务端回的是**原始事件**（按轮次分好组），不是「界面字段」——前端用与实时流同一个
 * `applyEvent` 渲染，历史会话才和刚答完的那一轮长得一模一样（两边各写一套映射必然漂移）。
 */

export interface SessionSummary {
  sessionId: string;
  /** 首条提问截断后的标题；没问过就是「未命名会话」 */
  title: string;
  /** 提问条数（≈ 轮次数；HITL 确认续跑不重复计数） */
  questions: number;
  createdAtMs: number;
  /** 末条记录时刻：列表按它倒序 */
  lastActiveMs: number;
  /** 是否已归档：最后一次归档 / 取消归档说了算 */
  archived: boolean;
  /** 归档时刻；未归档为 0 */
  archivedAtMs: number;
}

/** 一条回放事件，形状与 SSE 一致，可直接喂给 `applyEvent`。 */
export interface HistoryEvent {
  seq: number;
  name: string;
  data: Record<string, any>;
}

export interface HistoryTurn {
  /** 服务端轮次号；老会话（没有用户提问记录）会回退成 `turn-<seq>` */
  turnId: string;
  events: HistoryEvent[];
}

export async function listSessions(): Promise<SessionSummary[]> {
  const body = await request<{ sessions: SessionSummary[] }>("/v1/agent/sessions");
  return body.sessions ?? [];
}

/**
 * 归档 / 取消归档（§19.4）。
 *
 * **逻辑标记，不是删除**：归档后会话仍在服务端，历史照样能回放，取消归档就能接着聊
 * （规格把归档与召回列为后置能力，记录长期保留，§8.3 / §20.5）。
 * 不是自己的会话服务端回 404（不泄露存在性，§11.3）。
 */
export async function archiveSession(sessionId: string, archived: boolean): Promise<SessionSummary> {
  const body = await request<{ session: SessionSummary }>(
    `/v1/agent/sessions/${encodeURIComponent(sessionId)}/archive`,
    { method: "POST", body: { archived } }
  );
  return body.session;
}

/** 切换历史会话：不是自己的会话服务端回 404（不泄露存在性，§11.3）。 */
export async function sessionHistory(sessionId: string): Promise<HistoryTurn[]> {
  const body = await request<{ sessionId: string; turns: HistoryTurn[] }>(
    `/v1/agent/sessions/${encodeURIComponent(sessionId)}/turns`
  );
  return body.turns ?? [];
}