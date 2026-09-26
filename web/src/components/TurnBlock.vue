<script setup lang="ts">
import { computed, ref } from "vue";
import type { Turn } from "@/lib/turn";

/**
 * 一轮对话的渲染（§12.2 事件表 → 界面）：
 * 步骤卡 / 技能卡 / 工具卡 / 沙箱进度 / 文件卡 / 确认按钮 / 澄清选项 / 流式文本 / 最终答案。
 *
 * 「需要用户做选择」按 §12.1 的硬要求画在**对话气泡内**，不用弹窗。
 * 弹窗会藏掉上下文，用户在不知道前因的情况下点确认才是真的危险。
 */
const props = defineProps<{ turn: Turn; showReasoning: boolean }>();
const emit = defineEmits<{ (e: "confirm", confirmId: string, approved: boolean): void }>();

const feedback = ref<"up" | "down" | null>(null);

const sources = computed<string[]>(() => {
  const raw = props.turn.final?.["sources"] ?? props.turn.final?.["sourcesLabel"];
  if (!raw) {
    return [];
  }
  return Array.isArray(raw) ? raw.map((item) => (typeof item === "string" ? item : JSON.stringify(item))) : [String(raw)];
});

const expressions = computed<string[]>(() => {
  const raw = props.turn.final?.["expressions"] ?? props.turn.final?.["formula"];
  if (!raw) {
    return [];
  }
  return Array.isArray(raw) ? raw.map(String) : [String(raw)];
});

const figures = computed<{ name: string; url: string }[]>(() => {
  const raw = props.turn.final?.["figures"];
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .map((item: any) => ({ name: String(item?.name ?? "图表"), url: String(item?.url ?? item?.link ?? "") }))
    .filter((item) => item.url !== "");
});

const answerText = computed<string>(() => {
  const finalAnswer = props.turn.final?.["answer"];
  return finalAnswer ? String(finalAnswer) : props.turn.answer;
});
</script>

<template>
  <div class="turn">
    <div class="bubble user">{{ turn.question }}</div>

    <div class="bubble assistant">
      <p v-if="turn.statusHint" class="muted hint">{{ turn.statusHint }}</p>

      <details v-if="showReasoning && turn.reasoning.length" class="reasoning">
        <summary>推理原文（{{ turn.reasoning.length }} 段）</summary>
        <pre>{{ turn.reasoning.join("\n") }}</pre>
      </details>

      <ol v-if="turn.cards.length" class="cards">
        <li v-for="card in turn.cards" :key="card.key" class="card">
          <div class="row">
            <span class="tag">{{ card.title }}</span>
            <span class="muted mono">#{{ card.seq ?? "-" }}</span>
          </div>
          <div v-if="card.detail" class="detail">{{ card.detail }}</div>
          <pre v-if="card.payload && Object.keys(card.payload).length" class="mono">{{ JSON.stringify(card.payload, null, 2) }}</pre>
        </li>
      </ol>

      <div v-if="turn.clarify" class="banner warn">
        <div>需要您补充信息：{{ (turn.clarify.missingSlots ?? []).join("、") || "（见候选）" }}</div>
        <ul v-if="turn.clarify.candidates && turn.clarify.candidates.length" class="candidates">
          <li v-for="(candidate, index) in turn.clarify.candidates" :key="index" class="mono">
            {{ typeof candidate === "string" ? candidate : JSON.stringify(candidate) }}
          </li>
        </ul>
      </div>

      <div v-if="turn.confirm" class="banner warn">
        <div><strong>需要确认：</strong>{{ turn.confirm.summary || turn.confirm.action || "该操作需要您确认后才会执行" }}</div>
        <div class="row">
          <button class="primary" @click="emit('confirm', turn.confirm.confirmId, true)">确认执行</button>
          <button class="danger" @click="emit('confirm', turn.confirm.confirmId, false)">取消</button>
        </div>
      </div>

      <div v-if="answerText" class="answer" :class="{ streaming: turn.status === 'running' }">{{ answerText }}</div>

      <div v-if="sources.length || expressions.length" class="meta">
        <div v-if="expressions.length">
          <span class="muted">计算过程：</span>
          <span v-for="(expression, index) in expressions" :key="index" class="mono expr">{{ expression }}</span>
        </div>
        <div v-if="sources.length">
          <span class="muted">数据来源：</span>
          <span v-for="(source, index) in sources" :key="index" class="tag">{{ source }}</span>
        </div>
      </div>

      <div v-for="figure in figures" :key="figure.url" class="figure">
        <div class="muted">{{ figure.name }}</div>
        <img :src="figure.url" :alt="figure.name" />
      </div>

      <!-- 停止要如实说：这一轮是被用户取消的，不是跑完的，也不是出错 -->
      <div v-if="turn.status === 'stopped'" class="muted hint">已停止生成：这一轮被取消，上面已经产出的内容保留。</div>

      <div v-if="turn.error" class="banner error">
        {{ turn.error.message }}<span v-if="turn.error.code" class="mono">（{{ turn.error.code }}）</span>
      </div>

      <div v-if="turn.final" class="row feedback">
        <button :class="{ primary: feedback === 'up' }" title="反馈仅本地记录：反馈接口尚未接入" @click="feedback = 'up'">👍</button>
        <button :class="{ primary: feedback === 'down' }" title="反馈仅本地记录：反馈接口尚未接入" @click="feedback = 'down'">👎</button>
        <span v-if="feedback" class="muted">已记录（本地）</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.turn {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 18px;
}

.bubble {
  border-radius: var(--radius);
  padding: 10px 12px;
}

.bubble.user {
  align-self: flex-end;
  max-width: 80%;
  background: var(--brand);
  color: #fff;
  white-space: pre-wrap;
}

.bubble.assistant {
  background: var(--panel);
  border: 1px solid var(--line);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hint {
  margin: 0;
  font-size: 13px;
}

.cards {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.card {
  border: 1px dashed var(--line);
  border-radius: 8px;
  padding: 8px 10px;
  background: #fcfdff;
}

.detail {
  margin-top: 4px;
}

pre {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--muted);
}

.answer {
  white-space: pre-wrap;
}

.answer.streaming::after {
  content: "▌";
  color: var(--brand);
}

.meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border-top: 1px dashed var(--line);
  padding-top: 8px;
}

.expr {
  display: inline-block;
  margin-right: 8px;
}

.figure img {
  max-width: 100%;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.candidates {
  margin: 6px 0 0;
  padding-left: 18px;
}

.feedback button {
  padding: 2px 10px;
}
</style>
