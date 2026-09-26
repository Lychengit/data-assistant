import type { StreamEvent } from "@/lib/sse";

/**
 * 一轮对话的界面状态 + §12.2 事件表 → 界面的归约。
 *
 * 事件名与载荷按 §12.2；未知事件**不丢**：落成一张提示卡，免得后端加了事件前端静默吞掉。
 */

export interface Card {
  key: string;
  title: string;
  detail: string;
  seq: number | null;
  payload: Record<string, any>;
}

export interface ConfirmPrompt {
  confirmId: string;
  action?: string;
  summary?: string;
}

export interface ClarifyPrompt {
  missingSlots?: string[];
  candidates?: any[];
}

/** `stopped`：用户点了「停止生成」——这一轮被取消，已产出的内容保留（不是出错） */
export type TurnStatus = "running" | "awaiting" | "done" | "error" | "blocked" | "stopped";

export interface Turn {
  /** 服务端轮次 id；续跑（HITL 确认）会换一个新的，但界面上仍是同一条回答 */
  id: string;
  question: string;
  cards: Card[];
  /** 思考原文（§20.6：用户可选展示；默认只显示步骤卡） */
  reasoning: string[];
  answer: string;
  final: Record<string, any> | null;
  error: { code?: string; message: string } | null;
  confirm: ConfirmPrompt | null;
  clarify: ClarifyPrompt | null;
  statusHint: string | null;
  status: TurnStatus;
}

export function newTurn(id: string, question: string): Turn {
  return {
    id,
    question,
    cards: [],
    reasoning: [],
    answer: "",
    final: null,
    error: null,
    confirm: null,
    clarify: null,
    statusHint: null,
    status: "running"
  };
}

let cardCounter = 0;

export function applyEvent(turn: Turn, event: StreamEvent): void {
  const data = event.data ?? {};
  switch (event.name) {
    case "session":
      return;
    case "user":
      // 平台侧回放的「这一轮问了什么」：历史会话靠它把问题栏填上（实时流里我们已经先填过了）
      if (typeof data.text === "string" && data.text) {
        turn.question = data.text;
      }
      return;
    case "archived":
      // 归档是会话级状态，不是这一轮的内容：不落气泡，左侧列表由 ChatView 自己刷新
      return;
    case "intent":
      turn.statusHint = describeIntent(data);
      return;
    case "permission":
      if (data.allowed === false || data.denied === true) {
        turn.statusHint = "权限校验未通过，已终止本次查询";
        turn.status = "blocked";
      } else {
        turn.statusHint = "权限校验通过";
      }
      return;
    case "step":
      // 推理原文单独收（§20.6），不混进步骤卡
      if (String(data.type ?? "").toLowerCase().includes("think") || data.reasoning) {
        pushReasoning(turn, String(data.reasoning ?? data.content ?? ""));
        return;
      }
      // 消息块（type=message）的正文已经逐字进过回答气泡了，再落一张步骤卡就是同一段话出现两遍
      if (String(data.type ?? "").toLowerCase() === "message") {
        return;
      }
      pushCard(turn, "步骤卡", String(data.type ?? "step"), text(data.content), event.seq, data);
      return;
    case "skill_start":
      pushCard(turn, "技能", String(data.skillName ?? data.skillId ?? "技能"), text(data.summary ?? ""), event.seq, data);
      return;
    case "skill_step":
      pushCard(turn, "技能步骤", String(data.stepId ?? "step"), text(data.content), event.seq, data);
      return;
    case "tool": {
      // 服务端在结果事件里带回入参（JSON 文本）与结果字节数：卡片上一起显示，
      // 排查「模型到底传了什么」时不用再去翻日志
      const size = data.resultSize === undefined ? "" : `结果 ${data.resultSize} 字节`;
      const args = compactArgs(data.args);
      pushCard(turn, "工具调用", String(data.toolName ?? "tool"), [args, size].filter(Boolean).join(" · "), event.seq, data);
      return;
    }
    case "sandbox_job":
      pushCard(turn, "沙箱任务", String(data.status ?? "job"), String(data.jobId ?? ""), event.seq, data);
      return;
    case "artifact":
      pushCard(
        turn,
        "文件",
        String(data.name ?? data.artifactId ?? "文件"),
        [data.size === undefined ? "" : `${data.size} 字节`, String(data.link ?? "")].filter(Boolean).join(" · "),
        event.seq,
        data
      );
      return;
    case "confirm":
      turn.confirm = {
        confirmId: String(data.confirmId ?? ""),
        action: data.action === undefined ? undefined : String(data.action),
        summary: data.summary === undefined ? undefined : String(data.summary)
      };
      turn.status = "awaiting";
      return;
    case "clarify":
      turn.clarify = {
        missingSlots: Array.isArray(data.missingSlots) ? data.missingSlots.map(String) : undefined,
        candidates: Array.isArray(data.candidates) ? data.candidates : undefined
      };
      turn.statusHint = "需要补充或选择后再继续";
      return;
    case "token": {
      const delta = text(data.delta ?? data.text ?? data.content);
      if (delta) {
        turn.answer += delta;
      }
      if (data.reasoning) {
        pushReasoning(turn, String(data.reasoning));
      }
      return;
    }
    case "final":
      turn.final = data;
      if (typeof data.answer === "string" && data.answer) {
        turn.answer = data.answer;
      }
      return;
    case "error":
      turn.error = {
        code: data.code === undefined ? undefined : String(data.code),
        message: String(data.message ?? "服务暂不可用，请稍后再试")
      };
      return;
    case "done":
      // 「等确认」与「已停止」都是已经定稿的状态：收尾事件不该把它们翻回 done
      if (turn.status === "blocked" || turn.status === "stopped") {
        return;
      }
      // 用户点过「停止生成」的那一轮，服务端会在收尾事件上带 stopped=true（见后端 SseProjector）。
      // 以它为准把这一轮定稿成「已停止」：停止接口的响应和这条事件谁先到都对得上——
      // 事件先到就在这里定稿；响应先到则上面那句早把它标成 stopped 了。
      if (data.stopped === true) {
        turn.status = "stopped";
        turn.error = null;
        return;
      }
      turn.status = turn.error ? "error" : "done";
      return;
    default:
      pushCard(turn, `未识别事件 ${event.name}`, "", "", event.seq, data);
  }
}

/** 服务端收流后把界面状态定稿：没收到 done 就断了，也要让用户看见「这轮没跑完」。 */
export function settleWithoutDone(turn: Turn, reason: string): void {
  if (turn.status === "running") {
    turn.status = "error";
    turn.error = turn.error ?? { message: reason };
  }
}

/** 工具入参压成一行并截断：卡片是给人扫的，不是给人读的。 */
function compactArgs(value: unknown): string {
  if (typeof value !== "string" || !value.trim()) {
    return "";
  }
  const oneLine = value.replace(/\s+/g, " ").trim();
  return oneLine.length > 120 ? `${oneLine.slice(0, 120)}…` : oneLine;
}
function pushCard(
  turn: Turn,
  title: string,
  detail: string,
  extra: string,
  seq: number | null,
  payload: Record<string, any>
): void {
  cardCounter += 1;
  turn.cards.push({
    key: `card-${cardCounter}`,
    title,
    detail: [detail, extra].filter(Boolean).join(" · "),
    seq,
    payload
  });
}

function pushReasoning(turn: Turn, chunk: string): void {
  if (chunk.trim()) {
    turn.reasoning.push(chunk);
  }
}

function describeIntent(data: Record<string, any>): string {
  const parts = [data.intent, data.domain].filter(Boolean).map(String);
  if (data.confidence !== undefined) {
    parts.push(`置信度 ${data.confidence}`);
  }
  return parts.length ? `意图：${parts.join(" / ")}` : "已识别意图";
}

function text(value: unknown): string {
  return value === undefined || value === null ? "" : String(value);
}
