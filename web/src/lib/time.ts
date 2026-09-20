/**
 * 审计查询的时间窗（§20.5 / §6.6）。
 *
 * 三条服务端硬约束，前端提前拦一道，免得用户点一下才发现：
 *  ① `from`/`to` **必填**；② 左闭右开 `[from, to)`；③ 单次窗口 ≤31 天。
 * 传输一律用 **ISO-8601 UTC 时刻**（`2026-09-01T00:00:00Z`），本地时区只在输入框里出现。
 */

export const MAX_SPAN_DAYS = 31;
export const MAX_LIMIT = 500;

export interface AuditWindow {
  /** ISO-8601 UTC，含 */
  from: string;
  /** ISO-8601 UTC，不含 */
  to: string;
  limit: number;
}

/** 默认近 24 小时。 */
export function defaultWindow(hours = 24): AuditWindow {
  const to = new Date();
  const from = new Date(to.getTime() - hours * 3600_000);
  return { from: utc(from), to: utc(to), limit: 200 };
}

/** 把 `datetime-local` 的值（本地时间）转成 UTC 时刻。 */
export function localToUtc(local: string): string {
  return utc(new Date(local));
}

/** 把 `datetime-local` 的值（本地时间）规范成 UTC 时刻；非法输入原样返回，交给服务端判 400。 */
export function normalizeLocal(local: string): string {
  const parsed = new Date(local);
  return Number.isNaN(parsed.getTime()) ? local : utc(parsed);
}

export function spanDays(from: string, to: string): number {
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (Number.isNaN(start) || Number.isNaN(end)) {
    return Number.NaN;
  }
  return (end - start) / 86_400_000;
}

/** @returns 本地化的错误说明；`null` 表示这个窗口服务端会接受 */
export function validateWindow(window: AuditWindow): string | null {
  if (!window.from || !window.to) {
    return "审计查询必须带时间范围（§20.5）";
  }
  const days = spanDays(window.from, window.to);
  if (Number.isNaN(days)) {
    return "时间参数必须是 ISO-8601 UTC 时刻（如 2026-09-01T00:00:00Z）";
  }
  if (days <= 0) {
    return "时间范围必须满足 from < to（左闭右开，§6.6）";
  }
  if (days > MAX_SPAN_DAYS) {
    return `单次查询时间范围不得超过 ${MAX_SPAN_DAYS} 天（§20.5）`;
  }
  return null;
}

export function toQuery(window: AuditWindow, extra: Record<string, string | undefined> = {}): string {
  const query = new URLSearchParams({ from: window.from, to: window.to, limit: String(window.limit) });
  Object.entries(extra).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== "") {
      query.set(key, value);
    }
  });
  return query.toString();
}

export function utc(date: Date): string {
  // 秒级精度就够（审计表按 created_at 过滤），也更好读
  return `${date.toISOString().slice(0, 19)}Z`;
}

/** 展示用：本地时间，精确到秒。 */
export function display(value: string | null | undefined): string {
  if (!value) {
    return "-";
  }
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleString();
}
