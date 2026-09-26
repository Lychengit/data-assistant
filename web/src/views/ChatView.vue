<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import * as agent from "@/api/agent";
import { ApiError } from "@/api/http";
import TurnBlock from "@/components/TurnBlock.vue";
import { ChatStream, type StreamEvent, type StreamStatus } from "@/lib/sse";
import { applyEvent, newTurn, settleWithoutDone, type Turn } from "@/lib/turn";
import { ensureFreshToken } from "@/stores/auth";

/**
 * 对话页（§12.1 / §12.2 / §19.4）：Codex 风格流式展示「意图 → 权限 → 工具 → 思考 → 答案」，
 * 左侧是**历史会话列表**，随时切回旧会话接着聊。
 *
 * 接流方式是本页的关键：**消息走 POST、令牌走头、券进 SSE 的 query**。
 * 断开或刷新后用换券接口拿新券重连，并把最后收到的 seq 带回去，只补没看过的那一段。
 *
 * 历史会话没有自己的渲染路径：服务端回放的是**同一批事件**，我们喂给同一个 `applyEvent`，
 * 所以「切回去」看到的那一轮和刚答完时长得一模一样（§19.4：回放与续传共用一个事实源）。
 */
const turns = ref<Turn[]>([]);
const sessionId = ref<string | null>(null);
const question = ref("");
const busy = ref(false);
const streamStatus = ref<StreamStatus | "idle">("idle");
const streamDetail = ref<string | null>(null);
const showReasoning = ref(false);
const scroller = ref<HTMLElement | null>(null);
/** 已收到的最后一条事件 seq：重连时带回去当起点，服务端只补后面那段（§19.4） */
const lastSeq = ref<number | null>(null);
/** 我的历史会话（服务端按最后活动倒序） */
const sessions = ref<agent.SessionSummary[]>([]);
/** 切换会话时正在回放它的历史 */
const historyLoading = ref(false);
/** 列表是否已经读过一次：没读过就说「还没有历史会话」正是「历史丢了」错觉的来源 */
const sessionsLoaded = ref(false);
const sessionsFailed = ref(false);
/** 正在归档 / 取消归档的会话号：期间禁掉那个按钮，免得连点两下发两次相反的请求 */
const archiving = ref<string | null>(null);
/** 「已归档」分区是否展开：默认收起，主列表只放还在用的会话 */
const showArchived = ref(false);

/**
 * 正在生成的那一轮（没有就是 null）：发送键就在这时变成停止键。
 *
 * 从 turns 里算、而不是读下面那个 active 变量——active 是普通变量，被换掉时模板不会重算，
 * 按钮就会「点不动」或者「停不下来」。
 */
const runningTurn = computed(() => turns.value.find((turn) => turn.status === "running") ?? null);
/** 停止请求在途：期间禁掉按钮，免得连点两下发两次 */
const stopping = ref(false);
/** 停下之后状态行上的那句话：接口响应和收流事件两处都会用到，抽出来免得同一件事两种说法 */
const STOPPED_HINT = "已停止这一轮，可以接着问下一轮";

let stream: ChatStream | null = null;
/** 首发用发起轮返回的券；重连时置空，改走换券接口 */
let pendingTicket: string | null = null;
let active: Turn | null = null;

/** 会话快照：刷新页面后靠它把会话与光标接回来（§19.4 刷新不丢答案）。 */
const STORAGE_KEY = "doctor-assistant.chat";

interface ChatSnapshot {
  sessionId: string;
  lastSeq: number | null;
  turns: Turn[];
}

function persist(): void {
  if (!sessionId.value) {
    sessionStorage.removeItem(STORAGE_KEY);
    return;
  }
  const snapshot: ChatSnapshot = {
    sessionId: sessionId.value,
    lastSeq: lastSeq.value,
    turns: turns.value
  };
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(snapshot));
}

function restoreChat(): void {
  const raw = sessionStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return;
  }
  try {
    const saved = JSON.parse(raw) as ChatSnapshot;
    if (!saved.sessionId || !Array.isArray(saved.turns) || !saved.turns.length) {
      sessionStorage.removeItem(STORAGE_KEY);
      return;
    }
    sessionId.value = saved.sessionId;
    lastSeq.value = saved.lastSeq ?? null;
    turns.value = saved.turns;
    // 必须从 ref 里读回来：读取时才拿得到响应式代理（见 pushTurn）
    active = turns.value[turns.value.length - 1];
    // 只有「还没跑完」的轮次值得续看：已收过 done 的轮次没有可补的尾巴，
    // 拿末尾的 afterSeq 去连只会挂在那儿等服务端超时。
    if (active.status === "running") {
      streamStatus.value = "closed";
      streamDetail.value = "已接回上次的会话，点「重新连接」从上次的位置接着看";
    }
  } catch {
    sessionStorage.removeItem(STORAGE_KEY);
  }
}

/**
 * 会话列表：读不到就先空着——列表坏了不该把主流程（提问）一起拖死。
 *
 * 但要记下「读失败」而不是让它长得像「没有会话」：空列表和没读到在界面上一模一样，
 * 而用户看到「还没有历史会话」的第一反应是历史被清空了（§19.4 的会话一直都在日志里）。
 */
async function loadSessions(): Promise<void> {
  try {
    sessions.value = await agent.listSessions();
    sessionsFailed.value = false;
  } catch {
    // 列表读不到就保持现状：它是辅助信息，不该把整个对话页标红
    sessionsFailed.value = true;
  } finally {
    sessionsLoaded.value = true;
  }
}

/**
 * 左侧列表的一句话状态。
 *
 * 空列表必须说清是「还没读出来」还是「真的没有」：两者在界面上一模一样，
 * 而用户看到「还没有历史会话」的第一反应是历史被清空了。
 */
const sessionListHint = computed(() => {
  // 读失败优先说失败：它比「正在载入…」更需要用户动手（刷新）
  if (sessionsFailed.value) {
    return "历史会话读取失败，刷新页面重试";
  }
  return historyLoading.value || !sessionsLoaded.value ? "正在载入…" : "";
});

/** 主列表只放未归档的：归档是「从列表里收起来」，不是删（§8.3 / §20.5）。 */
const activeSessions = computed(() => sessions.value.filter((item) => !item.archived));
const archivedSessions = computed(() => sessions.value.filter((item) => item.archived));

/**
 * 归档 / 取消归档（逻辑标记，不是删除）。
 *
 * 只替换列表里的这一行、不整表重拉：归档本身也是一条新记录，重拉会让这一行「跳」到最上面，
 * 用户会以为自己点错了。就地替换则位置不动，只有归档标记变化。
 */
async function toggleArchive(item: agent.SessionSummary): Promise<void> {
  if (archiving.value) {
    return;
  }
  archiving.value = item.sessionId;
  try {
    const updated = await agent.archiveSession(item.sessionId, !item.archived);
    const index = sessions.value.findIndex((entry) => entry.sessionId === updated.sessionId);
    if (index >= 0) {
      sessions.value[index] = updated;
    }
  } catch (error) {
    reportError(error);
  } finally {
    archiving.value = null;
  }
}

/** 切换历史会话：先摘掉旧连接，再用回放事件重建整个对话。 */
async function openSession(id: string): Promise<void> {
  if (id === sessionId.value) {
    return;
  }
  // 先摘引用再 close：close() 会同步回调 closed，留着引用会把「主动切走」记成「连接失败」
  detachStream();
  sessionId.value = id;
  turns.value = [];
  active = null;
  lastSeq.value = null;
  streamStatus.value = "idle";
  streamDetail.value = null;
  historyLoading.value = true;
  try {
    const history = await agent.sessionHistory(id);
    let maxSeq: number | null = null;
    for (const slice of history) {
      const turn = pushTurn(newTurn(slice.turnId, ""));
      for (const event of slice.events) {
        applyEvent(turn, event as StreamEvent);
        if (maxSeq === null || event.seq > maxSeq) {
          maxSeq = event.seq;
        }
      }
      if (!turn.question) {
        // 老会话：提问正文是后来才进日志的（见后端 SessionHistory 的兼容说明）。
        // 空着会让用户以为界面坏了，所以明说「没保存」。
        turn.question = "（这条历史记录没有保存当时的提问）";
      }
    }
    lastSeq.value = maxSeq;
    active = turns.value.length ? turns.value[turns.value.length - 1] : null;
    if (active && active.status === "running") {
      // 最后一轮**没有结束标记**：它是在**别的实例**上跑着的那一轮（§19.4 / T1-09）。
      // 这里必须主动接上去等结果——不然用户刷新到别的实例上只会看到一个转圈的气泡，
      // 却没有任何连接在给它送后续事件。服务端会等那一轮跑完，再把整段结果补过来。
      // 游标清空：历史里的 seq 是「投影的第几条」，和实时流不是一套编号，换实例后带它没有意义。
      lastSeq.value = null;
      attach();
    }
  } catch (error) {
    reportError(error);
  } finally {
    historyLoading.value = false;
    persist();
    await scrollToEnd();
  }
}

async function send(): Promise<void> {
  const text = question.value.trim();
  // 生成中不发新一轮：服务端的轮次闸门本来也会拒（「上一轮还在处理中」），
  // 但让界面自己先说清楚更好——这段时间发送键已经变成停止键了。
  if (!text || busy.value || runningTurn.value || !(await ensureFreshToken())) {
    return;
  }
  busy.value = true;
  streamDetail.value = null;
  try {
    if (!sessionId.value) {
      sessionId.value = await agent.createSession();
    }
    const ticket = await agent.startTurn(sessionId.value, text);
    question.value = "";
    // 游标**不清零**：同一会话的下一轮从上一轮结束的 seq 往后接，否则服务端会把整段重播回来
    active = pushTurn(newTurn(ticket.turnId, text));
    pendingTicket = ticket.ticket;
    persist();
    attach();
    // 这一轮把新会话带进了列表（标题就是刚问的这句），刷新一次让左侧跟上
    void loadSessions();
  } catch (error) {
    reportError(error);
  } finally {
    busy.value = false;
    await scrollToEnd();
  }
}

/** HITL 确认（§19.9）：服务端续跑并给一张新券，我们换券继续看同一轮。 */
async function answerConfirm(turn: Turn, confirmId: string, approved: boolean): Promise<void> {
  if (!sessionId.value) {
    return;
  }
  try {
    const ticket = await agent.confirm(sessionId.value, confirmId, approved);
    turn.confirm = null;
    turn.status = "running";
    active = turn;
    pendingTicket = ticket.ticket;
    attach();
  } catch (error) {
    reportError(error, turn);
  }
}

function attach(): void {
  // 先摘引用、再关旧连接：close() 会**同步**回调 closed，此刻 stream 仍指着旧实例，
  // 守卫 stream !== self 拦不住它，就把「上一轮正常关闭」记到了刚开始的这一轮头上。
  const previous = stream;
  stream = null;
  previous?.close();
  let self: ChatStream;
  self = new ChatStream({
    urlFactory: async (afterSeq) => {
      if (pendingTicket) {
        const ticket = pendingTicket;
        pendingTicket = null;
        return agent.streamUrl(ticket, afterSeq);
      }
      // 重连必须换一张新券：旧券已核销（§19.4）
      const resumed = await agent.resumeTicket(sessionId.value!);
      return agent.streamUrl(resumed.ticket, afterSeq);
    },
    onEvent: (event) => {
      if (stream !== self || !active) {
        return;
      }
      applyEvent(active, event);
      if (event.name === "archived") {
        // 归档是会话级状态，不属于任何一轮：不落气泡，只把左侧列表刷新过来
        void loadSessions();
      }
      if (event.seq !== null) {
        lastSeq.value = event.seq;
      }
      if (event.name === "done") {
        // 收流后主动关：不自闭的话服务端会一直挂着这条连接
        self.close();
        streamStatus.value = "closed";
        if (active?.status === "stopped") {
          // close() 会顺带把状态行文案清掉，这里把「已停止」那句补回来：
          // stopGenerating 里也设过同一句，谁先谁后都显示得出来
          streamDetail.value = STOPPED_HINT;
        }
      }
      persist();
      void scrollToEnd();
    },
    onStatus: (status, detail) => {
      if (stream !== self) {
        return;
      }
      streamStatus.value = status;
      streamDetail.value = detail ?? null;
      if ((status === "failed" || status === "closed") && active && active.status === "running") {
        // 连接没了但这一轮还没跑完：如实告诉用户「没看完」，而不是假装回答结束了
        settleWithoutDone(active, detail ?? "连接已断开，可点「重新连接」接着看");
      }
      persist();
    }
  });
  stream = self;
  // 从当前游标接着看：不带上它，服务端会把整个会话重播一遍（上一轮的卡会跑到这一轮里）
  void stream.start(lastSeq.value);
}

/** 主动摘掉当前连接（切会话 / 新建会话 / 关闭页面都用它）。 */
function detachStream(): void {
  const previous = stream;
  stream = null;
  pendingTicket = null;
  previous?.close();
}

/** 手动续看：换券重连，从上次收到的位置继续（§19.4 断线续传）。 */
async function reconnect(): Promise<void> {
  if (!sessionId.value) {
    return;
  }
  streamDetail.value = null;
  if (!active) {
    active = pushTurn(newTurn(`resume-${Date.now()}`, "（接着看上一轮）"));
  }
  active.status = "running";
  active.error = null;
  persist();
  attach();
}

function stopReceiving(): void {
  // 只停「接收」：答案仍在服务端继续生成，随时可以重连回来看（§19.4）
  stream?.close();
  streamStatus.value = "closed";
}

/**
 * 停止生成（§19.4 / H-09）：让**服务端真的取消**这一轮。
 *
 * 和上面 stopReceiving（只断这条 SSE、模型照跑）是两件事。这个按钮的语义是「别跑了」：
 * 只停这一轮（会话还在、已产出的内容保留），停了之后可以立刻接着问下一轮。
 *
 * 「已停止」这个结论有两条来源，它们说的是同一件事，不会打架：
 * ①正常情况：服务端收尾时发的 done 带 stopped=true，由 lib/turn.ts 把这一轮定稿成「已停止」；
 * ②这里兜底：流已经断了（点过「停止接收」、或连接失败）收不到那条 done 时，用接口返回的标记它。
 * 兜底这一条要求「界面这边也还认为它在跑」才动手，否则界面会从已经收好的答案翻回「已停止」，那是骗人的。
 * 幂等由服务端保证；连点两下由 stopping 期间禁用按钮挡住。
 */
async function stopGenerating(): Promise<void> {
  const turn = runningTurn.value;
  if (!turn || !sessionId.value || stopping.value) {
    return;
  }
  stopping.value = true;
  try {
    const result = await agent.stopTurn(sessionId.value);
    if (result.stopped && turn.status === "running") {
      turn.status = "stopped";
    }
    streamDetail.value = result.stopped ? STOPPED_HINT : "这一轮已经跑完了，没有可停的";
    persist();
  } catch (error) {
    reportError(error);
  } finally {
    stopping.value = false;
  }
}

/**
 * 新会话：**只清空当前对话**，左侧历史一条都不动。
 *
 * 这正是这次的要点——以前「新会话」把整个页面清成空白，历史会话就再也找不回来了。
 */
function newSession(): void {
  detachStream();
  sessionId.value = null;
  turns.value = [];
  active = null;
  lastSeq.value = null;
  streamStatus.value = "idle";
  streamDetail.value = null;
  persist();
}

/**
 * 输入框回车：**Enter 发送、Shift+Enter 换行**（原来只有 Ctrl+Enter，太不顺手）。
 *
 * 输入法组合态必须放行（`event.isComposing` / 旧浏览器的 `keyCode === 229`）：
 * 中文拼音还没上屏时那个 Enter 是**选词**，拦下来会把半截拼音当提问发出去、候选框的字也一起丢。
 * 所以不写成 `@keydown.enter` 上的内联修饰符——修饰符拦不住输入法那一下。
 */
function onComposerEnter(event: KeyboardEvent): void {
  if (event.shiftKey || event.isComposing || event.keyCode === 229) {
    return;
  }
  event.preventDefault();
  void send();
}

function reportError(error: unknown, turn?: Turn): void {
  const message = error instanceof ApiError ? error.message : "请求失败，请稍后重试";
  const code = error instanceof ApiError ? error.code : undefined;
  if (turn) {
    turn.error = { message, code };
    turn.status = "error";
  } else {
    streamDetail.value = message;
  }
}

function relativeTime(ms: number): string {
  if (!ms) {
    return "";
  }
  const delta = Date.now() - ms;
  if (delta < 60_000) {
    return "刚刚";
  }
  if (delta < 3_600_000) {
    return `${Math.floor(delta / 60_000)} 分钟前`;
  }
  if (delta < 86_400_000) {
    return `${Math.floor(delta / 3_600_000)} 小时前`;
  }
  const at = new Date(ms);
  const pad = (value: number) => String(value).padStart(2, "0");
  return at.getFullYear() === new Date().getFullYear()
    ? `${at.getMonth() + 1} 月 ${at.getDate()} 日`
    : `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}`;
}

async function scrollToEnd(): Promise<void> {
  await nextTick();
  if (scroller.value) {
    scroller.value.scrollTop = scroller.value.scrollHeight;
  }
}

/**
 * 入列并返回**响应式代理**。
 *
 * 这里必须把 push 进去的元素再读回来：`ref([])` 是深响应式的，数组元素在**读取时**才被包成代理。
 * 若继续持有 push 前的裸对象，后续 `applyEvent` 改的就是裸对象——界面不会重渲染（事件全收到、屏幕全不动）。
 */
function pushTurn(turn: Turn): Turn {
  turns.value.push(turn);
  return turns.value[turns.value.length - 1];
}

onMounted(async () => {
  await loadSessions();
  restoreChat();
  // 新开的标签页没有会话快照，但历史在服务端：直接接上最近聊过的那个（Codex 的做法）
  if (!sessionId.value && sessions.value.length) {
    await openSession(sessions.value[0].sessionId);
  }
});

onBeforeUnmount(() => {
  persist();
  stream?.close();
});
</script>

<template>
  <div class="chat-layout">
    <aside class="side panel">
      <button class="primary new" @click="newSession">＋ 新会话</button>
      <p v-if="sessionListHint" class="muted side-hint">{{ sessionListHint }}</p>
      <p v-else-if="!activeSessions.length && !archivedSessions.length" class="muted side-hint">还没有历史会话</p>
      <ul class="session-list">
        <li v-for="item in activeSessions" :key="item.sessionId" class="side-row">
          <button
            class="session"
            :class="{ active: item.sessionId === sessionId }"
            :title="item.title"
            @click="openSession(item.sessionId)"
          >
            <span class="title">{{ item.title }}</span>
            <span class="muted meta">{{ item.questions }} 问 · {{ relativeTime(item.lastActiveMs) }}</span>
          </button>
          <button
            class="icon"
            :disabled="archiving === item.sessionId"
            title="归档：只是从列表里收起来，不会删除，随时可以取消归档"
            @click.stop="toggleArchive(item)"
          >
            归档
          </button>
        </li>
      </ul>

      <div v-if="archivedSessions.length" class="archived">
        <button class="muted toggle" @click="showArchived = !showArchived">
          {{ showArchived ? "▾" : "▸" }} 已归档（{{ archivedSessions.length }}）
        </button>
        <ul v-if="showArchived" class="session-list">
          <li v-for="item in archivedSessions" :key="item.sessionId" class="side-row">
            <button
              class="session"
              :class="{ active: item.sessionId === sessionId }"
              :title="item.title"
              @click="openSession(item.sessionId)"
            >
              <span class="title">{{ item.title }}</span>
              <span class="muted meta">{{ item.questions }} 问 · 已归档</span>
            </button>
            <button
              class="icon"
              :disabled="archiving === item.sessionId"
              title="取消归档：会话一直都在，归档只是收起来"
              @click.stop="toggleArchive(item)"
            >
              恢复
            </button>
          </li>
        </ul>
      </div>
    </aside>

    <section class="chat">
      <div class="row head">
        <h1>对话</h1>
        <span class="tag">{{ sessionId ? `会话 ${sessionId.slice(0, 8)}…` : "尚未建立会话" }}</span>
        <span class="grow"></span>
        <label class="row switch">
          <input v-model="showReasoning" type="checkbox" />
          <span class="muted">显示推理原文（默认只看步骤卡，§20.6）</span>
        </label>
        <button v-if="streamStatus !== 'open'" :disabled="!sessionId" @click="reconnect">重新连接</button>
        <button v-else @click="stopReceiving">停止接收</button>
      </div>

      <div v-if="streamStatus !== 'idle'" class="row status">
        <span class="tag" :class="{ ok: streamStatus === 'open', warn: streamStatus === 'reconnecting', danger: streamStatus === 'failed' }">
          {{ streamStatus }}
        </span>
        <span v-if="streamDetail" class="muted">{{ streamDetail }}</span>
      </div>

      <div ref="scroller" class="scroll">
        <p v-if="!turns.length" class="muted empty">
          问点什么吧。每一步都会显示出来：意图、权限判定、工具调用、步骤卡，最后是带数据来源的答案。
        </p>
        <TurnBlock
          v-for="turn in turns"
          :key="turn.id"
          :turn="turn"
          :show-reasoning="showReasoning"
          @confirm="(confirmId, approved) => answerConfirm(turn, confirmId, approved)"
        />
      </div>

      <form class="composer" @submit.prevent="send">
        <textarea
          v-model="question"
          rows="3"
          placeholder="例如：上个月心内科各医生的门诊量排名（Enter 发送，Shift+Enter 换行）"
          @keydown.enter="onComposerEnter"
        ></textarea>
        <!-- 生成中就把「发送」换成停止键（Codex 的做法）：它停的是服务端这一轮，不是「不看了」 -->
        <button
          v-if="runningTurn"
          type="button"
          class="danger stop"
          :disabled="stopping"
          title="停止生成：取消这一轮（已产出的内容保留），停完可以马上接着问"
          aria-label="停止生成"
          @click="stopGenerating"
        >
          <svg class="stop-icon" viewBox="0 0 16 16" aria-hidden="true"><rect x="3.5" y="3.5" width="9" height="9" rx="1.5" /></svg>
        </button>
        <button v-else class="primary" type="submit" :disabled="busy || !question.trim()">
          {{ busy ? "发送中…" : "发送" }}
        </button>
      </form>
    </section>
  </div>
</template>

<style scoped>
.chat-layout {
  display: flex;
  gap: 12px;
  height: calc(100vh - 120px);
}

.side {
  flex: 0 0 240px;
  width: 240px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  overflow-y: auto;
}

.side .new {
  width: 100%;
}

.side-hint {
  margin: 0;
  font-size: 12px;
}

.session-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.session {
  width: 100%;
  text-align: left;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  border-color: transparent;
  background: transparent;
}

.session:hover {
  background: #fafbfc;
}

.session.active {
  background: var(--brand-soft);
  border-color: #c7d7fb;
  color: var(--brand);
}

.session .title {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.session .meta {
  font-size: 11px;
}

.chat {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.head {
  margin-bottom: 0;
}

.switch input {
  width: auto;
}

.scroll {
  flex: 1;
  overflow-y: auto;
  padding: 4px 2px;
}

.empty {
  margin-top: 40px;
  text-align: center;
}

.composer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
}

.composer textarea {
  resize: vertical;
}

.composer button {
  height: 40px;
  min-width: 88px;
}

/* 停止键：方形图标按钮，和发送键同一个位置、同一个高度（生成时它顶掉发送键） */
.composer button.stop {
  min-width: 44px;
  width: 44px;
  padding: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.composer button.stop .stop-icon {
  width: 14px;
  height: 14px;
  fill: currentColor;
}

.side-row {
  display: flex;
  align-items: stretch;
  gap: 2px;
}

.side-row .session {
  flex: 1 1 auto;
  min-width: 0;
}

/* 归档按钮平时不抢眼：它是辅助动作，不该和「打开会话」争视觉重心 */
.icon {
  flex: 0 0 auto;
  padding: 0 6px;
  font-size: 11px;
  color: var(--muted);
  border-color: transparent;
  background: transparent;
}

.icon:hover:not(:disabled) {
  border-color: var(--line);
  color: var(--text);
}

.archived {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 4px;
  padding-top: 6px;
  border-top: 1px dashed var(--line);
}

.toggle {
  padding: 2px 4px;
  font-size: 12px;
  text-align: left;
  border-color: transparent;
  background: transparent;
}

@media (max-width: 720px) {
  .chat-layout {
    flex-direction: column;
    height: auto;
  }

  .side {
    flex: 0 0 auto;
    width: auto;
    max-height: 30vh;
  }

  .chat {
    height: calc(100vh - 120px);
  }
}
</style>