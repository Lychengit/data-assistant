<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  auditConfig,
  auditDataAccess,
  auditOverview,
  auditPermission,
  auditReads,
  auditTrace
} from "@/api/admin";
import { ApiError } from "@/api/http";
import { defaultWindow, MAX_SPAN_DAYS, spanDays, utc, validateWindow, type AuditWindow } from "@/lib/time";

/**
 * M6 审计 / 回放 / 监控（§18.4.6 M6 / §20.4 / §20.5）。
 *
 * 三条硬口径，界面照做：
 *  - 审计**不走数据网关**（不套用户范围过滤），但只有 admin 能进；
 *  - 查询**必须带时间范围**，左闭右开、UTC、单次 ≤31 天——前端提前拦，服务端同样会拦；
 *  - 每次查询本身都会被记一条 `audit_read_audit`：所以页面上专门有一栏「谁查了审计」。
 */
type Tab = "overview" | "data-access" | "permission" | "config" | "reads" | "trace";

const tabs: { key: Tab; label: string }[] = [
  { key: "overview", label: "总览" },
  { key: "data-access", label: "数据访问" },
  { key: "permission", label: "权限判定" },
  { key: "config", label: "配置变更" },
  { key: "reads", label: "审计读记录" },
  { key: "trace", label: "链路回放" }
];

const tab = ref<Tab>("overview");
/** 输入框用本地时间（datetime-local），发出去一律转 UTC */
const fromLocal = ref(localInput(defaultWindow().from));
const toLocal = ref(localInput(defaultWindow().to));
const limit = ref(200);
const filter = ref("");

const rows = ref<Record<string, any>[]>([]);
const overview = ref<Record<string, any> | null>(null);
const trace = ref<Record<string, any> | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);

function localInput(iso: string): string {
  const date = new Date(iso);
  const pad = (value: number) => String(value).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

function window(): AuditWindow {
  return {
    from: utc(new Date(fromLocal.value)),
    to: utc(new Date(toLocal.value)),
    limit: limit.value
  };
}

function filterParams(): Record<string, string | undefined> {
  const [key, ...rest] = filter.value.split("=");
  return key && rest.length ? { [key.trim()]: rest.join("=").trim() } : {};
}

async function run(): Promise<void> {
  error.value = null;
  const current = window();
  const invalid = validateWindow(current);
  if (invalid) {
    error.value = invalid;
    return;
  }
  const days = spanDays(current.from, current.to);
  if (days > MAX_SPAN_DAYS) {
    error.value = `单次查询时间范围不得超过 ${MAX_SPAN_DAYS} 天（§20.5）`;
    return;
  }
  loading.value = true;
  rows.value = [];
  overview.value = null;
  trace.value = null;
  try {
    const params = filterParams();
    switch (tab.value) {
      case "overview":
        overview.value = await auditOverview(current);
        break;
      case "data-access":
        rows.value = await auditDataAccess(current, params);
        break;
      case "permission":
        rows.value = await auditPermission(current, params);
        break;
      case "config":
        rows.value = await auditConfig(current, params);
        break;
      case "reads":
        rows.value = await auditReads(current, params);
        break;
      case "trace":
        trace.value = await auditTrace(current, params);
        break;
    }
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : String(e);
  } finally {
    loading.value = false;
  }
}

function columns(items: Record<string, any>[]): string[] {
  return items.length ? Object.keys(items[0]) : [];
}

function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return "-";
  }
  return typeof value === "object" ? JSON.stringify(value) : String(value);
}

onMounted(run);
</script>

<template>
  <div>
    <h1>审计与监控（M6）</h1>
    <p class="muted">
      查询必须带时间范围（UTC、左闭右开、单次 ≤{{ MAX_SPAN_DAYS }} 天）。审计读本身也留痕（§20.4）。
    </p>

    <div class="panel section">
      <div class="row">
        <label class="grow"><span class="muted">起（本地时间）</span><input v-model="fromLocal" type="datetime-local" /></label>
        <label class="grow"><span class="muted">止（本地时间，不含）</span><input v-model="toLocal" type="datetime-local" /></label>
        <label><span class="muted">条数上限</span><input v-model.number="limit" type="number" min="1" :max="500" /></label>
        <label class="grow"><span class="muted">过滤（如 userId=alice）</span><input v-model="filter" /></label>
        <button class="primary" :disabled="loading" @click="run">{{ loading ? "查询中…" : "查询" }}</button>
      </div>
      <div class="row">
        <span class="muted mono">{{ window().from }} → {{ window().to }}</span>
      </div>
      <div class="row tabs">
        <button v-for="item in tabs" :key="item.key" :class="{ primary: tab === item.key }" @click="tab = item.key; run()">
          {{ item.label }}
        </button>
      </div>
      <p v-if="error" class="banner error">{{ error }}</p>
    </div>

    <div v-if="overview" class="panel section">
      <h2>总览</h2>
      <pre class="mono">{{ JSON.stringify(overview, null, 2) }}</pre>
    </div>

    <div v-if="trace" class="panel section">
      <h2>链路回放</h2>
      <pre class="mono">{{ JSON.stringify(trace, null, 2) }}</pre>
    </div>

    <div v-if="rows.length" class="panel">
      <h2>{{ rows.length }} 条记录</h2>
      <div class="scroll-x">
        <table>
          <thead>
            <tr><th v-for="name in columns(rows)" :key="name">{{ name }}</th></tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in rows" :key="index">
              <td v-for="name in columns(rows)" :key="name" class="mono">{{ cell(row[name]) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <p v-else-if="!loading && !overview && !trace && !error" class="muted">该时间窗内没有记录。</p>
  </div>
</template>

<style scoped>
.tabs {
  margin-top: 8px;
}

.scroll-x {
  overflow-x: auto;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
