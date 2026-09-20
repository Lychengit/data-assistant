/**
 * SSE 接入（§12.2 / §19.4）。
 *
 * 为什么不用 `EventSource` 的原生自动重连：入场券是**一次性**的，浏览器重连会把同一张
 * 已核销的券再送一遍（必然 401），然后无限重试。所以这里主动接管重连：
 * 断开后先调 `urlFactory` 换一张**新券**，再带着最后收到的 seq 重连——服务端只补后面那段。
 *
 * 事件名与载荷以 §12.2 的事件表为准，`data` 是该事件的 JSON。
 */

export const SSE_EVENT_NAMES = [
  "session",
  // 用户自己的提问（平台侧发出）：历史回放要能还原「问了什么」，实时流里它也标出这一轮的起点
  "user",
  // 会话被归档 / 取消归档：会话级状态，不属于任何一轮，界面只拿它刷新左侧列表
  "archived",
  "intent",
  "permission",
  "step",
  "skill_start",
  "skill_step",
  "tool",
  "sandbox_job",
  "artifact",
  "confirm",
  "clarify",
  "token",
  "final",
  "error",
  "done"
] as const;

export interface StreamEvent {
  /** 会话内单调序号（服务端分配）；重连时把它带回去当起点 */
  seq: number | null;
  name: string;
  data: Record<string, any>;
}

export type StreamStatus = "connecting" | "open" | "reconnecting" | "closed" | "failed";

export interface ChatStreamOptions {
  /** 每次（重）连都要一张新券：首发用发起轮返回的券，之后走换券接口 */
  urlFactory: (afterSeq: number | null) => Promise<string>;
  onEvent: (event: StreamEvent) => void;
  onStatus?: (status: StreamStatus, detail?: string) => void;
  /** 断线重连上限（超过就报「连接已断开」，不无限重试） */
  maxReconnects?: number;
}

const BACKOFF_MS = [500, 1000, 2000, 4000];

export class ChatStream {
  private source: EventSource | null = null;
  private lastSeq: number | null = null;
  private attempts = 0;
  private stopped = false;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(private readonly options: ChatStreamOptions) {}

  /**
   * @param afterSeq 起始游标（会话内 seq，null = 从头回放）。
   *
   *   **一轮结束后的下一轮必须带上它**：会话的 SSE 是「从 afterSeq 之后回放 + 实时」，
   *   不带上就会把上一轮的事件重播一遍——重播的 done 还会把这一轮的连接提前收掉（§19.4）。
   */
  async start(afterSeq: number | null = null): Promise<void> {
    this.stopped = false;
    this.attempts = 0;
    this.lastSeq = afterSeq;
    await this.open("connecting");
  }

  /** 主动收流：收到 `done`、离开页面、或用户点了「停止」都走这里。 */
  close(): void {
    this.stopped = true;
    if (this.timer) {
      clearTimeout(this.timer);
      this.timer = null;
    }
    this.detach();
    this.options.onStatus?.("closed");
  }

  private async open(status: StreamStatus): Promise<void> {
    if (this.stopped) {
      return;
    }
    this.options.onStatus?.(status);
    let url: string;
    try {
      url = await this.options.urlFactory(this.lastSeq);
    } catch (error) {
      this.options.onStatus?.("failed", describe(error));
      return;
    }
    if (this.stopped) {
      return;
    }
    const source = new EventSource(url);
    this.source = source;
    source.onopen = () => {
      this.attempts = 0;
      this.options.onStatus?.("open");
    };
    const handle = (name: string) => (message: MessageEvent<string>) => {
      this.dispatch(name, message);
    };
    for (const name of SSE_EVENT_NAMES) {
      source.addEventListener(name, handle(name) as EventListener);
    }
    // 没有任何 event: 行的心跳/未知事件不该把流打断，但也不当成业务事件（§12.2 的心跳是注释）
    source.onmessage = (message) => this.dispatch("message", message);
    source.onerror = () => {
      // 连接被服务端正常收流后浏览器也会进这里，所以先看我们是否已经收过 done
      this.detach();
      if (this.stopped) {
        return;
      }
      this.scheduleReconnect();
    };
  }

  private dispatch(name: string, message: MessageEvent<string>): void {
    const seq = message.lastEventId ? Number.parseInt(message.lastEventId, 10) : Number.NaN;
    if (!Number.isNaN(seq)) {
      this.lastSeq = seq;
    }
    if (name === "message" && !message.data) {
      return;
    }
    let data: Record<string, any> = {};
    if (message.data) {
      try {
        data = JSON.parse(message.data) as Record<string, any>;
      } catch {
        data = { raw: message.data };
      }
    }
    this.options.onEvent({ seq: Number.isNaN(seq) ? null : seq, name, data });
  }

  private scheduleReconnect(): void {
    const max = this.options.maxReconnects ?? 4;
    if (this.attempts >= max) {
      this.options.onStatus?.("failed", "连接已断开，请刷新页面后重试");
      return;
    }
    const delay = BACKOFF_MS[Math.min(this.attempts, BACKOFF_MS.length - 1)];
    this.attempts += 1;
    this.options.onStatus?.("reconnecting");
    this.timer = setTimeout(() => {
      this.timer = null;
      void this.open("reconnecting");
    }, delay);
  }

  private detach(): void {
    if (this.source) {
      this.source.close();
      this.source = null;
    }
  }
}

function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
