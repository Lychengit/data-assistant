<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import {
  deleteMetric,
  listMetrics,
  listUnitConversions,
  setMetricEnabled,
  upsertMetric,
  upsertUnitConversion,
  type MetricDefinition,
  type UnitConversion
} from "@/api/admin";
import { ApiError } from "@/api/http";

/**
 * M5 口径字典（§6.1 / §18.4.6 M5）。
 *
 * 为什么这些字段要填全：数字复算靠的就是它们——精度（`scale` / `rounding`）、单位换算、
 * 派生算子的来源。少一个字段，口径就复现不出来，答案就不可争议（§6.3）。
 */
const metrics = ref<MetricDefinition[]>([]);
const conversions = ref<UnitConversion[]>([]);
const error = ref<string | null>(null);
const message = ref<string | null>(null);

const form = reactive({
  metricKey: "",
  domain: "",
  name: "",
  aliases: "",
  definition: "",
  formula: "",
  timeBasis: "natural_month",
  unit: "",
  scale: "" as string,
  rounding: "2",
  derivedOf: "",
  timezone: "Asia/Shanghai",
  basis: ""
});

const conversion = reactive({ fromUnit: "", toUnit: "", factor: "" as string });

async function load(): Promise<void> {
  error.value = null;
  try {
    metrics.value = await listMetrics();
    conversions.value = await listUnitConversions();
  } catch (e) {
    error.value = text(e);
  }
}

async function save(): Promise<void> {
  message.value = null;
  error.value = null;
  try {
    await upsertMetric({
      metricKey: form.metricKey.trim(),
      domain: form.domain.trim() || null,
      name: form.name.trim(),
      aliases: form.aliases.split(",").map((item) => item.trim()).filter(Boolean),
      definition: form.definition.trim() || null,
      formula: form.formula.trim() || null,
      timeBasis: form.timeBasis.trim() || null,
      unit: form.unit.trim() || null,
      scale: form.scale ? Number(form.scale) : null,
      rounding: form.rounding ? Number(form.rounding) : null,
      derivedOf: form.derivedOf ? JSON.parse(form.derivedOf) : null,
      timezone: form.timezone.trim() || null,
      basis: form.basis.trim() || null
    });
    message.value = "已保存（同一次变更写一条 config_audit，§20.7）";
    await load();
  } catch (e) {
    error.value = e instanceof SyntaxError ? "派生算子不是合法 JSON" : text(e);
  }
}

async function saveConversion(): Promise<void> {
  message.value = null;
  error.value = null;
  try {
    await upsertUnitConversion({
      fromUnit: conversion.fromUnit.trim(),
      toUnit: conversion.toUnit.trim(),
      factor: Number(conversion.factor)
    });
    message.value = "单位换算已保存";
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function toggle(metric: MetricDefinition): Promise<void> {
  error.value = null;
  try {
    await setMetricEnabled(metric.metricKey, metric.enabled === false);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function remove(metric: MetricDefinition): Promise<void> {
  error.value = null;
  try {
    await deleteMetric(metric.metricKey);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

function edit(metric: MetricDefinition): void {
  form.metricKey = metric.metricKey;
  form.domain = metric.domain ?? "";
  form.name = metric.name ?? "";
  form.aliases = (metric.aliases ?? []).join(", ");
  form.definition = metric.definition ?? "";
  form.formula = metric.formula ?? "";
  form.timeBasis = metric.timeBasis ?? "";
  form.unit = metric.unit ?? "";
  form.scale = metric.scale === null || metric.scale === undefined ? "" : String(metric.scale);
  form.rounding = metric.rounding === null || metric.rounding === undefined ? "" : String(metric.rounding);
  form.derivedOf = metric.derivedOf ? JSON.stringify(metric.derivedOf) : "";
  form.timezone = metric.timezone ?? "";
  form.basis = metric.basis ?? "";
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(load);
</script>

<template>
  <div>
    <h1>口径字典（M5）</h1>
    <p class="muted">精度、单位换算、派生算子必须进库前就合法，否则数字复算不出来（§6.3）。</p>

    <p v-if="message" class="banner">{{ message }}</p>
    <p v-if="error" class="banner error">{{ error }}</p>

    <div class="panel section">
      <h2>新增 / 更新口径</h2>
      <div class="row">
        <label class="grow"><span class="muted">口径键</span><input v-model="form.metricKey" /></label>
        <label class="grow"><span class="muted">域</span><input v-model="form.domain" placeholder="doctor_performance" /></label>
        <label class="grow"><span class="muted">名称</span><input v-model="form.name" /></label>
        <label class="grow"><span class="muted">别名（逗号分隔）</span><input v-model="form.aliases" /></label>
      </div>
      <div class="row">
        <label class="grow"><span class="muted">时间基准</span><input v-model="form.timeBasis" /></label>
        <label class="grow"><span class="muted">单位</span><input v-model="form.unit" /></label>
        <label class="grow"><span class="muted">精度 scale</span><input v-model="form.scale" /></label>
        <label class="grow"><span class="muted">四舍五入位数</span><input v-model="form.rounding" /></label>
        <label class="grow"><span class="muted">时区</span><input v-model="form.timezone" /></label>
      </div>
      <div class="row">
        <label class="grow"><span class="muted">定义</span><input v-model="form.definition" /></label>
        <label class="grow"><span class="muted">公式</span><input v-model="form.formula" /></label>
        <label class="grow"><span class="muted">口径依据</span><input v-model="form.basis" /></label>
      </div>
      <label>
        <span class="muted">派生算子（JSON，如 {"sources":["gmv","cost"],"operator":"diff"}）</span>
        <textarea v-model="form.derivedOf" rows="3" class="mono"></textarea>
      </label>
      <div class="row"><button class="primary" @click="save">保存</button></div>
    </div>

    <div class="panel section">
      <h2>口径列表</h2>
      <table>
        <thead><tr><th>口径键</th><th>名称</th><th>单位</th><th>精度</th><th>派生</th><th></th></tr></thead>
        <tbody>
          <tr v-for="metric in metrics" :key="metric.metricKey">
            <td class="mono">{{ metric.metricKey }}</td>
            <td>{{ metric.name }}</td>
            <td>{{ metric.unit ?? "-" }}</td>
            <td>{{ metric.scale ?? "-" }} / {{ metric.rounding ?? "-" }}</td>
            <td class="mono muted">{{ metric.derivedOf ? JSON.stringify(metric.derivedOf) : "-" }}</td>
            <td class="row">
              <button @click="edit(metric)">编辑</button>
              <button @click="toggle(metric)">{{ metric.enabled === false ? "启用" : "停用" }}</button>
              <button class="danger" @click="remove(metric)">删除</button>
            </td>
          </tr>
          <tr v-if="!metrics.length"><td colspan="6" class="muted">口径字典为空。</td></tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>单位换算</h2>
      <div class="row">
        <label class="grow"><span class="muted">源单位</span><input v-model="conversion.fromUnit" placeholder="万" /></label>
        <label class="grow"><span class="muted">目标单位</span><input v-model="conversion.toUnit" placeholder="元" /></label>
        <label class="grow"><span class="muted">换算系数</span><input v-model="conversion.factor" placeholder="10000" /></label>
        <button class="primary" @click="saveConversion">保存</button>
      </div>
      <table>
        <thead><tr><th>源</th><th>目标</th><th>系数</th></tr></thead>
        <tbody>
          <tr v-for="item in conversions" :key="item.fromUnit + '->' + item.toUnit">
            <td>{{ item.fromUnit }}</td>
            <td>{{ item.toUnit }}</td>
            <td class="mono">{{ item.factor }}</td>
          </tr>
          <tr v-if="!conversions.length"><td colspan="3" class="muted">尚未配置换算。</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
