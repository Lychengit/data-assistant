<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { deleteApi, listApis, setApiEnabled, upsertApi, type ApiRegistration } from "@/api/admin";
import { ApiError } from "@/api/http";

/**
 * M4 接口注册（§18.4.6 M4 / §19.3）。
 *
 * 三条口径：
 *  - **身份是三元组 `(服务, 方法, 路径)`**，不是一个接口编码：编码在代码里读不出来、也参与不了路由，
 *    只能靠人去记住"注册表写的"和"@PostMapping 实现的"保持一致；三元组每一项都能从代码读出来，
 *    对不上就是接口服务启动失败，而不是运行期静默 404；
 *  - **模型侧工具名由路径派生**（`/doctor/performance` → `iface_doctor_performance`），界面只读展示：
 *    不存在"配置的名字"和"模型看到的工具名"各说各话；
 *  - **数据范围与返回列都不在这里配**：范围由接口服务基于登录人自己推导（§19.1），
 *    返回哪些列由接口自己的 SQL 写死。
 */
const apis = ref<ApiRegistration[]>([]);
const error = ref<string | null>(null);
const message = ref<string | null>(null);
/** 编辑中的行号：非空时三元组锁定（改路径等于登记一条新接口，会造成两条并存） */
const editingId = ref<number | null>(null);

const form = reactive({
  service: "interface-doctor",
  httpMethod: "POST",
  httpPath: "",
  name: "",
  kind: "read",
  resource: "",
  paramSchema: "{}",
  enabled: true,
  scenario: "",
  resultSchema: "{}"
});

const METHODS = ["POST", "GET", "PUT", "DELETE"];

/**
 * 工具名预览：复用服务端的派生规则 `iface_ + 路径去斜杠换下划线`。
 *
 * <p>前端算一遍只是为了"边填边看"，真正的名字由服务端派生后回显——两边只有一条规则来源。
 */
const toolNamePreview = computed<string>(() => {
  const path = form.httpPath.trim();
  if (!/^\/[A-Za-z0-9/_-]*$/.test(path) || path.includes("//") || path.includes("..")) {
    return "（路径不合法：必须以 / 开头，只允许字母数字与 / _ -，且不含 // 或 ..）";
  }
  return "iface_" + path.slice(1).replace(/\//g, "_");
});

async function load(): Promise<void> {
  error.value = null;
  try {
    apis.value = await listApis();
  } catch (e) {
    error.value = text(e);
  }
}

async function save(): Promise<void> {
  message.value = null;
  error.value = null;
  try {
    await upsertApi({
      id: editingId.value ?? undefined,
      service: form.service.trim(),
      httpMethod: form.httpMethod,
      httpPath: form.httpPath.trim(),
      name: form.name.trim(),
      kind: form.kind,
      resource: form.resource.trim() || null,
      paramSchema: JSON.parse(form.paramSchema || "{}"),
      enabled: form.enabled,
      scenario: form.scenario.trim() || null,
      resultSchema: JSON.parse(form.resultSchema || "{}")
    });
    message.value = "已登记（同一次变更写一条 config_audit，§20.7）";
    reset();
    await load();
  } catch (e) {
    error.value = e instanceof SyntaxError ? "参数定义 / 返回字段不是合法 JSON" : text(e);
  }
}

async function toggle(api: ApiRegistration): Promise<void> {
  error.value = null;
  try {
    await setApiEnabled(api.id!, !api.enabled);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

async function remove(api: ApiRegistration): Promise<void> {
  error.value = null;
  try {
    await deleteApi(api.id!);
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

function edit(api: ApiRegistration): void {
  editingId.value = api.id ?? null;
  form.service = api.service;
  form.httpMethod = api.httpMethod;
  form.httpPath = api.httpPath;
  form.name = api.name;
  form.kind = api.kind;
  form.resource = api.resource ?? "";
  form.paramSchema = JSON.stringify(api.paramSchema ?? {}, null, 2);
  form.enabled = api.enabled;
  form.scenario = api.scenario ?? "";
  form.resultSchema = JSON.stringify(api.resultSchema ?? {}, null, 2);
}

function reset(): void {
  editingId.value = null;
  form.service = "interface-doctor";
  form.httpMethod = "POST";
  form.httpPath = "";
  form.name = "";
  form.kind = "read";
  form.resource = "";
  form.paramSchema = "{}";
  form.enabled = true;
  form.scenario = "";
  form.resultSchema = "{}";
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(load);
</script>

<template>
  <div>
    <h1>接口注册（M4）</h1>
    <p class="muted">
      接口身份是「服务 + 方法 + 路径」三元组，模型工具名由路径派生。停用后网关解析不到目标服务，调用会被拒（§20.3）。
      数据范围由接口服务基于登录人推导，不在这里配（§19.1）。
    </p>

    <div class="panel section">
      <h2>{{ editingId === null ? "登记接口" : "编辑接口 #" + editingId }}</h2>
      <div class="row">
        <label class="grow">
          <span class="muted">服务</span>
          <input v-model="form.service" :disabled="editingId !== null" placeholder="interface-doctor" />
        </label>
        <label>
          <span class="muted">方法</span>
          <select v-model="form.httpMethod" :disabled="editingId !== null">
            <option v-for="method in METHODS" :key="method" :value="method">{{ method }}</option>
          </select>
        </label>
        <label class="grow">
          <span class="muted">路径</span>
          <input v-model="form.httpPath" :disabled="editingId !== null" placeholder="/doctor/performance" />
        </label>
        <label class="grow"><span class="muted">名称</span><input v-model="form.name" /></label>
      </div>
      <p class="muted mono">模型工具名：{{ toolNamePreview }}</p>
      <p v-if="editingId !== null" class="muted">
        三元组是接口身份，编辑时锁定。要换路径：停用旧接口，再登记一条新的——否则旧的会留在注册表里，
        两条都往模型面前摆。
      </p>
      <div class="row">
        <label class="grow">
          <span class="muted">类型</span>
          <select v-model="form.kind">
            <option value="read">read（只读）</option>
            <option value="write">write（写，默认不对技能开放，ADR-19）</option>
          </select>
        </label>
        <label class="grow"><span class="muted">资源</span><input v-model="form.resource" /></label>
        <label class="row"><input type="checkbox" v-model="form.enabled" style="width: auto" /><span>启用</span></label>
      </div>
      <label>
        <span class="muted">参数定义（JSON）——回答「怎么填」</span>
        <textarea v-model="form.paramSchema" rows="4" class="mono"></textarea>
      </label>
      <label>
        <span class="muted">适用场景——回答「什么时候用它、什么时候不该用」，直接进模型看到的工具描述</span>
        <textarea v-model="form.scenario" rows="3"></textarea>
      </label>
      <label>
        <span class="muted">
          返回字段（JSON：字段名 → {type, description}）——回答「能拿到什么」，同样进工具描述。
          模型看不到 outputSchema，只能靠这段文字，漏了它就会编字段名；技能包的导出字段也以它为上界
        </span>
        <textarea v-model="form.resultSchema" rows="4" class="mono"></textarea>
      </label>
      <div class="row">
        <button class="primary" @click="save">保存</button>
        <button v-if="editingId !== null" @click="reset">取消编辑</button>
      </div>
      <p v-if="message" class="banner">{{ message }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
    </div>

    <div class="panel">
      <h2>已登记接口</h2>
      <table>
        <thead>
          <tr>
            <th>接口</th><th>名称</th><th>服务</th><th>类型</th><th>场景</th><th>状态</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="api in apis" :key="api.id">
            <td class="mono">{{ api.httpMethod }} {{ api.httpPath }}</td>
            <td>{{ api.name }}</td>
            <td>{{ api.service }}</td>
            <td>
              <span class="tag" :class="api.kind === 'read' ? '' : 'warn'">{{ api.kind }}</span>
            </td>
            <td class="muted">
              <span v-if="api.scenario">{{ api.scenario.length > 40 ? api.scenario.slice(0, 40) + "…" : api.scenario }}</span>
              <span v-else class="warn">未填（模型只看到入参）</span>
            </td>
            <td><span class="tag" :class="api.enabled ? 'ok' : 'danger'">{{ api.enabled ? "启用" : "停用" }}</span></td>
            <td class="row">
              <button @click="edit(api)">编辑</button>
              <button @click="toggle(api)">{{ api.enabled ? "停用" : "启用" }}</button>
              <button class="danger" @click="remove(api)">删除</button>
            </td>
          </tr>
          <tr v-if="!apis.length"><td colspan="7" class="muted">尚未登记任何接口。</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>