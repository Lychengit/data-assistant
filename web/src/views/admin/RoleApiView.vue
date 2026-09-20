<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  listApis,
  listRoleApi,
  revokeRoleApi,
  upsertRoleApi,
  type ApiRegistration,
  type RoleApiGrant
} from "@/api/admin";
import { ApiError } from "@/api/http";

/**
 * M2 角色 / 接口授权（§18.4.6 M2 / §19.1）。
 *
 * 这里配的只有「这个角色**能不能调**这个接口」——`role_api` 是纯关联表 `(role_id, api_id)`。
 * 能看多大范围**不在这里配**：范围由接口服务基于登录人自己推导（数据过滤写在接口的 SQL 里），
 * 权限库因此完全不认识业务表结构，接口变复杂也不用动它。
 */
const roleCode = ref("boss");
const grants = ref<RoleApiGrant[]>([]);
const apis = ref<ApiRegistration[]>([]);
const apiId = ref<number | null>(null);
const message = ref<string | null>(null);
const error = ref<string | null>(null);

async function load(): Promise<void> {
  error.value = null;
  try {
    grants.value = await listRoleApi(roleCode.value.trim());
  } catch (e) {
    error.value = text(e);
  }
}

/** 接口下拉从注册表拉：授权必须指向一条真实存在的注册行，手打 id 只会打错。 */
async function loadApis(): Promise<void> {
  try {
    apis.value = await listApis();
    if (apiId.value === null && apis.value.length) {
      apiId.value = apis.value[0].id ?? null;
    }
  } catch (e) {
    error.value = text(e);
  }
}

async function save(): Promise<void> {
  message.value = null;
  error.value = null;
  if (apiId.value === null) {
    error.value = "请选择要授权的接口";
    return;
  }
  try {
    await upsertRoleApi(roleCode.value.trim(), apiId.value);
    message.value = "已授权（同一次变更会写一条 config_audit，§20.7）";
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function revoke(grant: RoleApiGrant): Promise<void> {
  error.value = null;
  try {
    await revokeRoleApi(grant.roleCode, grant.apiId);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

function label(api: ApiRegistration): string {
  return `${api.httpMethod} ${api.httpPath}（${api.name}）`;
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(async () => {
  await loadApis();
  await load();
});
</script>

<template>
  <div>
    <h1>角色授权（M2）</h1>
    <p class="muted">
      授权只回答「能不能调」。能看多大范围由接口服务基于登录人自己推导（写在各接口的 SQL 里，§19.1）——
      这里配不了范围，也不该配：范围挂在授权行上，权限库就得去猜业务表结构。
    </p>

    <div class="panel section">
      <div class="row">
        <label class="grow">
          <span class="muted">角色编码</span>
          <input v-model="roleCode" placeholder="boss / director / admin" />
        </label>
        <button @click="load">查询</button>
      </div>
    </div>

    <div class="panel section">
      <h2>新增一条授权</h2>
      <div class="row">
        <label class="grow">
          <span class="muted">接口</span>
          <select v-model.number="apiId">
            <option v-for="api in apis" :key="api.id" :value="api.id">{{ label(api) }}</option>
          </select>
        </label>
        <button class="primary" @click="save">授权</button>
      </div>
      <p v-if="message" class="banner">{{ message }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
    </div>

    <div class="panel">
      <h2>当前授权</h2>
      <table>
        <thead>
          <tr>
            <th>角色</th>
            <th>接口</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="grant in grants" :key="grant.roleCode + '/' + grant.apiId">
            <td>{{ grant.roleCode }}</td>
            <td class="mono">{{ grant.route }}</td>
            <td><button class="danger" @click="revoke(grant)">撤销</button></td>
          </tr>
          <tr v-if="!grants.length">
            <td colspan="3" class="muted">该角色暂无授权。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>