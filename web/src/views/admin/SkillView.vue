<script setup lang="ts">
import { nextTick, onMounted, ref } from "vue";
import { useRoute } from "vue-router";
import {
  disableSkill,
  listApis,
  listPendingSkills,
  listSkills,
  reviewSkillVersion,
  setSkillEnabled,
  skillChecks,
  skillDetail,
  updateSkillBoundApis,
  uploadSkillPackage,
  type ApiRegistration,
  type SkillCheck,
  type SkillSummary,
  type SkillUploadResult,
  type SkillVersion
} from "@/api/admin";
import { ApiError } from "@/api/http";
import { display } from "@/lib/time";

/** 只用来读深链参数（`?bind=<skillCode>`）；页面自己不写 URL，免得把回退历史塞满。 */
const route = useRoute();

/**
 * M3 技能包管理（§18.4.6 M3 / §18.5.2 / §19.2）。
 *
 * 另外一件事：「这个技能能调哪些接口」（`skill_api`）**可以在这页直接改绑**（技能行 →「绑定接口」）。
 * 绑定原本只有包内 manifest 一个来源，为了换一个接口就要重建包、重算哈希、重走一遍上传评审，
 * 代价高到没人愿意改——所以页面直连写 `skill_api`。校验口径与上传时的自动检查逐条一致
 * （接口已注册 + 已启用），改动写 `config_audit`；
 * 重新发布该技能的包时按 manifest 重置。
 *
 * 界面顺序就是流程顺序：**上传 → 自动检查 → 人工评审 → 发布**。三条口径在页面上直说：
 *  - 内容寻址：同一个包重复上传不会产生第二版，也不会重跑检查；
 *  - 只用最新版：评审对象**永远**是该技能的最新包，旧包不能补发；
 *  - 自动检查里 blocking 不过就**自动驳回**，不会挂在待评审列表里等人点。
 */
const skills = ref<SkillSummary[]>([]);
const pending = ref<SkillVersion[]>([]);
const selected = ref<{ versions: SkillVersion[]; boundRoutes?: string[] } | null>(null);
const selectedCode = ref<string | null>(null);
const checks = ref<Record<number, SkillCheck[]>>({});
/** 待评审里正展开哪一行的检查明细；null = 都收起（再点一次同一行就是收起） */
const checksOpenFor = ref<number | null>(null);
/** 上传结果的检查明细默认收起：十几项铺开像刷日志，先给结论、要看再展开 */
const showUploadChecks = ref(false);
const upload = ref<SkillUploadResult | null>(null);
const apis = ref<ApiRegistration[]>([]);
/** 正在看「绑定接口」面板的技能编码；null = 面板收起 */
const bindingCode = ref<string | null>(null);
/** 该技能已审核的绑定接口（`skill_api`，规范三元组文本）——勾选状态以库里读回的这份为准，不靠本地猜 */
const bindingRoutes = ref<string[]>([]);
/** 正在提交的那一行（接口 id）：只禁用这一行，别把整页锁住 */
const bindingBusy = ref<number | null>(null);
/** 「复制模板」按钮刚被点过（2 秒后自己变回来，不留一个永远写着「已复制」的按钮） */
const copied = ref(false);

/**
 * 最小 manifest 模板：直接贴进包里，改 id / name / description 三处就能用。
 *
 * 刻意**不写** version / kind / params / boundRoutes：那几项要么能推（类型看有没有 script 块）、
 * 要么能后配（绑定接口在页面里勾）、要么只影响展示。模板越长，越像一道必须读懂的题。
 */
const minimalManifestTemplate = `{
  "id": "my_skill_code",
  "name": "技能名称",
  "description": "一句话说清它做什么——这句话会下发给模型，用来判断什么时候该用它",
  "script": {
    "path": "scripts/run.py",
    "data": "none",
    "timeoutSeconds": 60
  }
}`;

const reason = ref("");
const exportedColumns = ref("");
const error = ref<string | null>(null);
const message = ref<string | null>(null);
const busy = ref(false);

async function load(): Promise<void> {
  error.value = null;
  try {
    skills.value = await listSkills();
    pending.value = await listPendingSkills();
    apis.value = await listApis();
  } catch (e) {
    error.value = text(e);
  }
}

async function open(skillCode: string): Promise<void> {
  selectedCode.value = skillCode;
  error.value = null;
  try {
    selected.value = await skillDetail(skillCode);
  } catch (e) {
    error.value = text(e);
  }
}

/** 详情按钮：展开的就是这个技能 → 收起；否则打开（面板收起后行不再占着版面）。 */
async function toggleDetail(skillCode: string): Promise<void> {
  if (selectedCode.value === skillCode && selected.value) {
    closeDetail();
    return;
  }
  await open(skillCode);
}

function closeDetail(): void {
  selected.value = null;
  selectedCode.value = null;
}

/** 「检查明细」按钮：同一行再点一次就是收起，明细不常驻在页面上。 */
async function showChecks(versionId: number): Promise<void> {
  if (checksOpenFor.value === versionId) {
    checksOpenFor.value = null;
    return;
  }
  error.value = null;
  try {
    checks.value = { ...checks.value, [versionId]: await skillChecks(versionId) };
    checksOpenFor.value = versionId;
  } catch (e) {
    error.value = text(e);
  }
}

/**
 * 检查结论的一行式摘要：先把「几项没过」摆在最前面——没过的比总数重要，
 * 通过项占多数时不该让人自己数。
 */
function checksSummary(items: SkillCheck[] | undefined): string {
  const list = items ?? [];
  const failed = blockingFailures(list).length;
  const warned = list.filter((check) => check.severity === "warning" && !check.passed).length;
  const head = failed ? `${failed} 项未过` : "全部通过";
  return warned ? `${head}；${warned} 项提示` : head;
}

async function onFile(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  busy.value = true;
  error.value = null;
  message.value = null;
  upload.value = null;
  try {
    upload.value = await uploadSkillPackage(file);
    message.value = upload.value.duplicateContent
      ? "这个包的内容之前上传过：复用同一个版本行，没有重跑检查（§19.2 内容寻址）"
      : "上传完成，已生成自动检查结论";
    await load();
    if (upload.value?.version) {
      await open(upload.value.version.skillCode);
    }
  } catch (e) {
    error.value = text(e);
  } finally {
    busy.value = false;
    input.value = "";
  }
}

async function review(version: SkillVersion, approve: boolean): Promise<void> {
  busy.value = true;
  error.value = null;
  message.value = null;
  try {
    const columns = exportedColumns.value
      .split(",")
      .map((item) => item.trim())
      .filter(Boolean);
    const reviewed = await reviewSkillVersion(version.id, approve, reason.value, columns);
    message.value = approve ? `已发布 ${reviewed.skillCode} ${reviewed.version}` : "已驳回";
    reason.value = "";
    await load();
    await open(reviewed.skillCode);
  } catch (e) {
    error.value = text(e);
  } finally {
    busy.value = false;
  }
}

async function toggle(skill: SkillSummary): Promise<void> {
  error.value = null;
  try {
    if (skill.status === "active") {
      await disableSkill(skill.skillCode);
    } else {
      await setSkillEnabled(skill.skillCode, true);
    }
    await load();
  } catch (e) {
    error.value = text(e);
  }
}

/**
 * 打开「绑定接口」面板：先从库里把该技能当前的绑定读回来，再让人勾。
 *
 * <p>不再顺手把「详情」面板也打开：两块面板管的是两件事（版本历史 / 能调哪些接口），
 * 点哪个开哪个——只为了拿 `boundRoutes` 就把版本表铺一屏，正是「关不掉的一堆东西」的来源。
 */
async function openBindings(skill: SkillSummary): Promise<void> {
  bindingCode.value = skill.skillCode;
  message.value = null;
  error.value = null;
  try {
    // 绑定接口是「这个技能到底能调哪些接口」的唯一事实，勾选状态以这份读回为准，不靠本地猜
    const detail = await skillDetail(skill.skillCode);
    bindingRoutes.value = detail.boundRoutes ?? [];
  } catch (e) {
    error.value = text(e);
  }
}

/** 绑定按钮：已经开着这个技能 → 收起；否则打开。 */
async function toggleBindings(skill: SkillSummary): Promise<void> {
  if (bindingCode.value === skill.skillCode) {
    closeBindings();
    return;
  }
  await openBindings(skill);
}

function closeBindings(): void {
  bindingCode.value = null;
  bindingRoutes.value = [];
}

/** 与 `skill_api` 里存的那串一模一样（服务名 + 大写方法 + 路径）；提交给后端的也是它。 */
function routeOf(api: ApiRegistration): string {
  return `${api.service} ${api.httpMethod.toUpperCase()} ${api.httpPath}`;
}

function isBound(api: ApiRegistration): boolean {
  return bindingRoutes.value.includes(routeOf(api));
}

/** 不能绑的原因；能绑返回 null。口径与上传时的自动检查同一条：接口得是注册过、且启用的。 */
function unbindableReason(api: ApiRegistration): string | null {
  return api.enabled ? null : "接口已停用，先到「接口注册 M4」启用";
}

/** 绑定项的一行式展示：整包都挂在同一个接口服务上，去掉服务名更好读。 */
function routesLabel(routes: string[]): string {
  return routes.length ? routes.map((route) => route.split(" ").slice(1).join(" ")).join("；") : "无";
}

function bindingLabel(): string {
  return routesLabel(bindingRoutes.value);
}

/**
 * 勾选即落库：提交的是**整个集合**（取消勾选 = 换一个更小的集合），失败时把勾弹回去，
 * 不留「界面勾着、库里没有」的中间态（与角色授权页同一套做法）。
 */
async function toggleBinding(api: ApiRegistration, event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const wanted = input.checked;
  if (bindingCode.value === null) {
    return;
  }
  const route = routeOf(api);
  const next = wanted
    ? [...bindingRoutes.value.filter((item) => item !== route), route]
    : bindingRoutes.value.filter((item) => item !== route);
  bindingBusy.value = api.id ?? null;
  message.value = null;
  error.value = null;
  try {
    const result = await updateSkillBoundApis(bindingCode.value, next);
    bindingRoutes.value = result.boundRoutes ?? next;
    message.value = `已更新 ${bindingCode.value} 的绑定接口：${bindingLabel()}`;
    // 绑定零缓存：下一轮对话网关按新集合下发（§0.3-4），这里不必缓存任何东西
    await load();
  } catch (e) {
    input.checked = !wanted;
    error.value = text(e);
  } finally {
    bindingBusy.value = null;
  }
}

/** 复制模板：剪贴板可能被浏览器策略挡住（非 https / 未授权），失败就照常报错，不假装复制成功。 */
async function copyTemplate(): Promise<void> {
  try {
    await navigator.clipboard.writeText(minimalManifestTemplate);
    copied.value = true;
    window.setTimeout(() => {
      copied.value = false;
    }, 2000);
  } catch (e) {
    error.value = text(e);
  }
}

/** 绑定接口面板在「已登记技能」下面，不滚一下等于没打开（进来的人会以为没生效）。 */
function scrollToBindings(): void {
  document.getElementById("skill-bindings")?.scrollIntoView({ block: "start" });
}

/**
 * 上传成功后的一步引导：包刚收下，这个人多半接着就要配「这个技能能调哪些接口」。
 * 让他自己回到上面的技能表里找那一行按钮，正是「功能有、但没人找得到」的来源。
 */
async function bindFromUpload(): Promise<void> {
  const code = upload.value?.version.skillCode;
  const skill = code === undefined ? undefined : skills.value.find((item) => item.skillCode === code);
  if (!skill) {
    return;
  }
  await openBindings(skill);
  await nextTick();
  scrollToBindings();
}

function blockingFailures(items: SkillCheck[] | undefined): SkillCheck[] {
  return (items ?? []).filter((check) => check.severity === "blocking" && !check.passed);
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

/**
 * 深链：`/admin/skill?bind=<skillCode>` 直接展开这个技能的绑定面板——「角色授权」页的
 * 「配置」按钮就是这么跳过来的。技能找不到时静默忽略（参数可能是手写错的），
 * 不能因为一个 URL 参数把整页打断。
 */
async function openFromQuery(): Promise<void> {
  const wanted = typeof route.query.bind === "string" ? route.query.bind : null;
  const skill = wanted === null ? undefined : skills.value.find((item) => item.skillCode === wanted);
  if (!skill) {
    return;
  }
  await openBindings(skill);
  await nextTick();
  scrollToBindings();
}

onMounted(async () => {
  await load();
  await openFromQuery();
});
</script>

<template>
  <div>
    <h1>技能包（M3）</h1>
    <p class="muted">
      上传 → 自动检查 → 人工评审 → 发布成不可变版本。包按内容哈希存放，同一个包不会产生第二版；评审对象永远是最新包。
      绑定接口（这个技能能调哪些接口）在下面「已登记技能」里点<strong>绑定接口</strong>勾选，不必写进 manifest、也不必重新发版。
    </p>

    <p v-if="message" class="banner">{{ message }}</p>
    <p v-if="error" class="banner error">{{ error }}</p>

    <div class="panel section">
      <h2>上传技能包</h2>
      <div class="row">
        <input type="file" accept=".zip" :disabled="busy" @change="onFile" style="width: auto" />
        <span class="muted">zip 内含 manifest.json；包体只走请求体，不落 URL、不进日志</span>
      </div>
      <p class="muted">
        manifest <strong>只必填三项</strong>：<span class="mono">id</span>（技能编码）、
        <span class="mono">name</span>、<span class="mono">description</span>；脚本类再加一个
        <span class="mono">script</span> 块。版本不写就按包内容自动生成，类型不写就看包内有没有
        script 块——都不用先学。其余字段（<span class="mono">params</span> /
        <span class="mono">resources</span> / <span class="mono">exports</span> /
        <span class="mono">status</span>）都可以不写。
      </p>
      <p class="muted">
        <strong>接口不用写进包里</strong>：上传只负责把包收下（接口清单是另一件事），发布后到下面「已登记技能」点
        <strong>绑定接口</strong>勾选即可，改接口也不必重打包重发版。上传后包停在<strong>待评审</strong>，
        要点下面的「批准并发布」才算上线。
      </p>
      <details class="section">
        <summary class="muted">最小 manifest 长这样（点开可复制）</summary>
        <div class="row">
          <button @click="copyTemplate">{{ copied ? "已复制" : "复制模板" }}</button>
        </div>
        <pre class="mono template">{{ minimalManifestTemplate }}</pre>
      </details>
      <div v-if="upload" class="section">
        <div class="row">
          <span class="tag" :class="upload.version.status === 'checked' ? 'ok' : 'danger'">
            {{ upload.version.status }}
          </span>
          <span class="mono">{{ upload.version.skillCode }} {{ upload.version.version }}</span>
          <span class="muted mono">content {{ upload.version.contentSha256.slice(0, 12) }}…</span>
        </div>
        <p v-if="upload.duplicateContent" class="banner warn">
          这个包与已存在的版本<strong>内容完全相同</strong>：复用了同一个版本行，没有重跑检查，也
          <strong>没有新增待评审</strong>。要发新版，请改包内容并把版本号升上去（如 1.0.6）后重新打包上传。
        </p>
        <p v-else-if="blockingFailures(upload.checks).length" class="banner error">
          上传已收到，但自动检查未通过，<strong>已自动驳回</strong>（不会挂在待评审列表里）。对照下面的明细改包后重传。
        </p>
        <p v-else class="banner">
          上传成功，已进入「待评审」——下一步到下面<strong>待评审（最新包）</strong>点「检查明细」再「批准并发布」；
          发布后如果这个技能还没绑接口，就点下面的<strong>去绑定接口</strong>（包内没写 <span class="mono">boundRoutes</span> 时绑定不归包管）。
        </p>
        <div v-if="upload.version.status === 'checked'" class="row">
          <button @click="bindFromUpload">去绑定接口</button>
        </div>
        <div class="row">
          <button @click="showUploadChecks = !showUploadChecks">
            {{ showUploadChecks ? "收起" : "展开" }}自动检查明细（{{ checksSummary(upload.checks) }}）
          </button>
        </div>
        <ul v-if="showUploadChecks" class="checks">
          <li v-for="check in upload.checks" :key="check.code">
            <span class="tag" :class="check.severity === 'warning' ? 'warn' : check.passed ? 'ok' : 'danger'">
              {{ check.severity }}
            </span>
            <span class="mono">{{ check.code }}</span>
            <span class="muted">{{ check.detail }}</span>
          </li>
        </ul>
      </div>
    </div>

    <div class="panel section">
      <h2>待评审（最新包）</h2>
      <table>
        <thead><tr><th>技能</th><th>版本</th><th>类型</th><th>提交人</th><th>提交时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="version in pending" :key="version.id">
            <td class="mono">{{ version.skillCode }}</td>
            <td>{{ version.version }}</td>
            <td>{{ version.kind }}</td>
            <td>{{ version.submittedBy }}</td>
            <td class="muted">{{ display(version.createdAt) }}</td>
            <td>
              <button @click="showChecks(version.id)">
                {{ checksOpenFor === version.id ? "收起明细" : "检查明细" }}
              </button>
            </td>
          </tr>
          <tr v-if="!pending.length"><td colspan="6" class="muted">没有待评审的技能包。</td></tr>
        </tbody>
      </table>

      <div v-for="version in pending" :key="'checks-' + version.id" class="section">
        <div v-if="checks[version.id] && checksOpenFor === version.id" class="panel">
          <div class="row spread">
            <h2>#{{ version.id }} 自动检查</h2>
            <button @click="checksOpenFor = null">收起</button>
          </div>
          <ul class="checks">
            <li v-for="check in checks[version.id]" :key="check.code">
              <span class="tag" :class="check.severity === 'warning' ? 'warn' : check.passed ? 'ok' : 'danger'">
                {{ check.severity }}
              </span>
              <span class="mono">{{ check.code }}</span>
              <span class="muted">{{ check.detail }}</span>
            </li>
          </ul>
          <div class="row">
            <label class="grow">
              <span class="muted">评审结论说明</span>
              <input v-model="reason" placeholder="绑定接口是否最小、导出字段是否合规" />
            </label>
            <label class="grow">
              <span class="muted">核过的导出字段（逗号分隔）</span>
              <input v-model="exportedColumns" placeholder="doctor_id, dept_code" />
            </label>
          </div>
          <div class="row">
            <button
              class="primary"
              :disabled="busy || blockingFailures(checks[version.id]).length > 0"
              @click="review(version, true)"
            >
              批准并发布
            </button>
            <button class="danger" :disabled="busy" @click="review(version, false)">驳回</button>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <h2>已登记技能</h2>
      <table>
        <thead><tr><th>技能</th><th>名称</th><th>状态</th><th>最新版本</th><th>版本状态</th><th>更新时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.skillCode">
            <td class="mono">{{ skill.skillCode }}</td>
            <td>{{ skill.name }}</td>
            <td><span class="tag" :class="skill.status === 'active' ? 'ok' : 'danger'">{{ skill.status }}</span></td>
            <td>{{ skill.latestVersion ?? "-" }}</td>
            <td>{{ skill.latestStatus ?? "-" }}</td>
            <td class="muted">{{ display(skill.updatedAt) }}</td>
            <td class="row">
              <button @click="toggleBindings(skill)">
                {{ bindingCode === skill.skillCode ? "收起绑定" : "绑定接口" }}
              </button>
              <button @click="toggleDetail(skill.skillCode)">
                {{ selectedCode === skill.skillCode && selected ? "收起详情" : "详情" }}
              </button>
              <button @click="toggle(skill)">{{ skill.status === "active" ? "停用" : "启用" }}</button>
            </td>
          </tr>
          <tr v-if="!skills.length"><td colspan="7" class="muted">尚未发布任何技能。</td></tr>
        </tbody>
      </table>

      <div v-if="selected" class="section">
        <div class="row spread">
          <h2>{{ selectedCode }} 的版本</h2>
          <button @click="closeDetail">收起</button>
        </div>
        <table>
          <thead><tr><th>id</th><th>版本</th><th>状态</th><th>内容哈希</th><th>发布人</th><th>发布时间</th></tr></thead>
          <tbody>
            <tr v-for="version in selected.versions" :key="version.id">
              <td>{{ version.id }}</td>
              <td>{{ version.version }}</td>
              <td>{{ version.status }}</td>
              <td class="mono muted">{{ version.contentSha256.slice(0, 16) }}…</td>
              <td>{{ version.publishedBy ?? "-" }}</td>
              <td class="muted">{{ display(version.publishedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <p class="muted">停用不删包：执行记录里的包哈希要一直查得到（§19.2 可追溯）。</p>
        <p class="muted">
          已绑定接口（skill_api）：<span class="mono">{{ routesLabel(selected.boundRoutes ?? []) }}</span>
          —— 改绑在下面「绑定接口」面板里（它读的是同一份 <span class="mono">skill_api</span>）。
        </p>
      </div>
    </div>

    <div v-if="bindingCode" id="skill-bindings" class="panel section">
      <div class="row spread">
        <h2>{{ bindingCode }} 的绑定接口（skill_api）</h2>
        <button @click="closeBindings">收起</button>
      </div>
      <p class="muted">
        勾选即落库（零缓存，下一轮对话就按新绑定下发）：提交的是<strong>整个集合</strong>，取消勾选就是从集合里去掉。
        校验口径与上传时的自动检查逐条一致——接口必须已注册且启用（<strong>写接口同样可绑</strong>：
        写操作的闸门是执行时的一次性确认卡，不是配置期的绑定禁令）。这里改的是<strong>已发布版本</strong>的绑定：与该技能包内
        <span class="mono">manifest.boundRoutes</span> 不一致时以这里为准，重新发布该技能的包会按 manifest 重置。
        当前已绑定：<span class="mono">{{ bindingLabel() }}</span>
      </p>
      <table>
        <thead>
          <tr><th>接口</th><th>名称</th><th>注册状态</th><th>类型</th><th>绑定</th><th>说明</th></tr>
        </thead>
        <tbody>
          <tr v-for="api in apis" :key="api.id ?? routeOf(api)">
            <td class="mono">{{ routeOf(api) }}</td>
            <td>{{ api.name }}</td>
            <td>
              <span class="tag" :class="api.enabled ? 'ok' : 'danger'">{{ api.enabled ? "enabled" : "disabled" }}</span>
            </td>
            <td>{{ api.kind }}</td>
            <td>
              <label class="row check">
                <input
                  type="checkbox"
                  :checked="isBound(api)"
                  :disabled="unbindableReason(api) !== null || bindingBusy === api.id"
                  @change="toggleBinding(api, $event)"
                />
              </label>
            </td>
            <td class="muted">{{ unbindableReason(api) ?? "" }}</td>
          </tr>
          <tr v-if="!apis.length">
            <td colspan="6" class="muted">还没有已注册的接口（先去「接口注册 M4」登记）。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
/* 勾选框按内容宽度走：全局 input 是 width:100%，用在这里会把整行撑满、点哪儿都能误触 */
/* 标题 + 收起按钮排一行：收起是这个面板唯一的出口，不滚动就该看得见 */
.spread {
  justify-content: space-between;
}

.spread h2 {
  margin: 0;
}

.check input[type="checkbox"] {
  width: auto;
}

/* 模板按代码块展示：JSON 缩进不能被人一眼看成一坨，撑不下的横向滚动，别把整页宽度拖走 */
.template {
  margin: 8px 0 0;
  padding: 8px;
  background: rgba(127, 127, 127, 0.12);
  border-radius: 4px;
  overflow-x: auto;
}

.checks {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
