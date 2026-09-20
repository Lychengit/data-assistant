<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import {
  deleteLlmProvider,
  listLlmAdapters,
  listLlmProviders,
  setLlmProviderEnabled,
  upsertLlmProvider,
  type LlmAdapterInfo,
  type LlmProvider
} from "@/api/admin";
import { ApiError } from "@/api/http";

/**
 * 模型供应商配置（ADR-14 / §20.1.6 / §20.7）。
 *
 * 三条要点：
 *  - **Key 只写不回读**：列表里显示的是 `keyHint`（`sk-****1234`），任何一个响应都不含明文，
 *    所以这个页面拿不到「能拿去调模型」的字符串，也不可能把它泄进浏览器存储或前端日志；
 *  - **同一时刻至多一个生效**：启用一个会自动关掉其它（服务端同事务处理，库里另有唯一索引兜底）；
 *  - 编辑时 `apiKey` 留空 = **密钥不改**（只改端点/模型/开关），不必为了换个 baseUrl 把 Key 重贴一遍。
 */
const providers = ref<LlmProvider[]>([]);
const adapters = ref<LlmAdapterInfo[]>([]);
const error = ref<string | null>(null);
const message = ref<string | null>(null);
const editing = ref(false);

const form = reactive({
  providerId: "",
  adapter: "deepseek",
  baseUrl: "",
  model: "",
  apiKey: "",
  enabled: false
});

async function load(): Promise<void> {
  error.value = null;
  try {
    providers.value = await listLlmProviders();
  } catch (e) {
    error.value = text(e);
  }
}

async function loadAdapters(): Promise<void> {
  try {
    adapters.value = await listLlmAdapters();
    applyAdapterDefaults();
  } catch (e) {
    error.value = text(e);
  }
}

/** 切适配器时把空的端点/模型补上默认值；已经填过的不覆盖。 */
function applyAdapterDefaults(): void {
  const adapter = adapters.value.find((item) => item.id === form.adapter);
  if (!adapter) {
    return;
  }
  if (!form.baseUrl.trim() && adapter.defaultBaseUrl) {
    form.baseUrl = adapter.defaultBaseUrl;
  }
  if (!form.model.trim() && adapter.defaultModel) {
    form.model = adapter.defaultModel;
  }
}

watch(() => form.adapter, applyAdapterDefaults);

async function save(): Promise<void> {
  message.value = null;
  error.value = null;
  try {
    const saved = await upsertLlmProvider({
      providerId: form.providerId.trim(),
      adapter: form.adapter,
      baseUrl: form.baseUrl.trim(),
      model: form.model.trim(),
      // 空串按「不变」处理：新增时服务端会明确拒绝「没有 Key 的新供应商」
      apiKey: form.apiKey.trim() || null,
      enabled: form.enabled
    });
    message.value = `已保存：${saved.providerId}（同一次变更写一条 config_audit，§20.7）`;
    form.apiKey = "";
    editing.value = false;
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function toggle(provider: LlmProvider): Promise<void> {
  error.value = null;
  message.value = null;
  try {
    await setLlmProviderEnabled(provider.providerId, !provider.enabled);
    message.value = provider.enabled
      ? `${provider.providerId} 已停用`
      : `${provider.providerId} 已启用（同时会关掉其它供应商，同一时刻只有一个生效）`;
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function remove(provider: LlmProvider): Promise<void> {
  error.value = null;
  message.value = null;
  try {
    await deleteLlmProvider(provider.providerId);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

function edit(provider: LlmProvider): void {
  editing.value = true;
  form.providerId = provider.providerId;
  form.adapter = provider.adapter;
  form.baseUrl = provider.baseUrl;
  form.model = provider.model;
  form.apiKey = "";
  form.enabled = provider.enabled;
}

function reset(): void {
  editing.value = false;
  form.providerId = "";
  form.adapter = "deepseek";
  form.baseUrl = "";
  form.model = "";
  form.apiKey = "";
  form.enabled = false;
  applyAdapterDefaults();
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(async () => {
  await loadAdapters();
  await load();
});
</script>

<template>
  <div>
    <h1>模型供应商（ADR-14）</h1>
    <p class="muted">
      当前接入的是<strong>大模型服务</strong>：OpenAI 兼容协议，默认 DeepSeek。Key 以密文入库，页面只回显尾部
      4 位，任何响应都不含明文（§20.1.6）。同一时刻<strong>至多一个生效</strong>，启用新的会自动关掉旧的。
    </p>

    <div class="panel section">
      <h2>{{ editing ? `编辑 ${form.providerId}` : "新增 / 更新供应商" }}</h2>
      <div class="row">
        <label class="grow"><span class="muted">供应商标识</span><input v-model="form.providerId" placeholder="deepseek" /></label>
        <label class="grow">
          <span class="muted">适配器</span>
          <select v-model="form.adapter">
            <option v-for="adapter in adapters" :key="adapter.id" :value="adapter.id">{{ adapter.id }}</option>
          </select>
        </label>
        <label class="grow"><span class="muted">模型名</span><input v-model="form.model" placeholder="deepseek-flash" /></label>
      </div>
      <div class="row">
        <label class="grow"><span class="muted">接口地址（OpenAI 兼容）</span><input v-model="form.baseUrl" placeholder="https://api.deepseek.com" /></label>
        <label class="grow">
          <span class="muted">API Key{{ editing ? "（留空表示不修改）" : "" }}</span>
          <input v-model="form.apiKey" type="password" autocomplete="off" :placeholder="editing ? '留空则保持原 Key' : 'sk-...'" />
        </label>
        <label class="row"><input type="checkbox" v-model="form.enabled" style="width: auto" /><span>启用</span></label>
      </div>
      <div class="row">
        <button class="primary" @click="save">保存</button>
        <button v-if="editing" @click="reset">取消编辑</button>
      </div>
      <p class="muted">Key 只在保存时提交一次，之后任何接口都不会再回传明文；编辑其它字段时留空即可保留原 Key。</p>
      <p v-if="message" class="banner">{{ message }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
    </div>

    <div class="panel">
      <h2>已配置供应商</h2>
      <table>
        <thead>
          <tr>
            <th>标识</th><th>适配器</th><th>模型</th><th>接口地址</th><th>Key</th><th>状态</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="provider in providers" :key="provider.providerId">
            <td class="mono">{{ provider.providerId }}</td>
            <td><span class="tag">{{ provider.adapter }}</span></td>
            <td class="mono">{{ provider.model }}</td>
            <td class="mono muted">{{ provider.baseUrl }}</td>
            <td class="mono muted">{{ provider.keyHint }}</td>
            <td><span class="tag" :class="provider.enabled ? 'ok' : 'danger'">{{ provider.enabled ? "生效中" : "停用" }}</span></td>
            <td class="row">
              <button @click="edit(provider)">编辑</button>
              <button @click="toggle(provider)">{{ provider.enabled ? "停用" : "启用" }}</button>
              <button class="danger" @click="remove(provider)">删除</button>
            </td>
          </tr>
          <tr v-if="!providers.length"><td colspan="7" class="muted">尚未配置任何模型供应商。</td></tr>
        </tbody>
      </table>
      <p v-if="providers.length && !providers.some((p) => p.enabled)" class="banner error">
        当前没有生效中的供应商：对话落到需要模型的运行时时会明确报错，而不是悄悄用一个旧配置。
      </p>
    </div>
  </div>
</template>
