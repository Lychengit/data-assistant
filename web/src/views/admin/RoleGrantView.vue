<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import {
  listApis,
  listRoleApi,
  listRoles,
  listRoleSkill,
  listSkills,
  skillDetail,
  revokeRoleApi,
  revokeRoleSkill,
  upsertRoleApi,
  upsertRoleSkill,
  type ApiRegistration,
  type RoleApiGrant,
  type RoleSkillGrant,
  type RoleSummary,
  type SkillSummary
} from "@/api/admin";
import { ApiError } from "@/api/http";
import { useRouter } from "vue-router";

/**
 * M2 角色授权（§18.4.6 M2 / §4.6 / §19.1）：「哪些角色能用哪些技能 / 能调哪些接口」就在这一页配。
 *
 * <p>口径是**并集**，两张表分工不同但结果取并：
 *  - `role_skill`（技能）：配了技能，这个角色**就获得了该技能绑定并审核通过的接口**（`skill_api`），
 *    同时这一轮模型看得到这个技能包。所以**技能是不用再逐个接口授一遍的**；
 *  - `role_api`（直配）：只管**没绑进技能**的接口。写接口也可以绑进技能给（2026-09-27 起不再限制），
 *    直配留给「不想让整个技能都拿到、只想单授给某个角色」的接口。
 *  - 接口是否真的能调，看页面「实际来源」列：技能带的、直配的、两者都有，都算。
 *
 * <p>数据范围**不在这里配**：范围由接口服务基于登录人自己推导（写在接口 SQL 里，§19.1）。
 * 把范围挂到授权行上，等于让权限库去猜业务表结构，接口一复杂就得改权限库。
 */
const router = useRouter();

const roles = ref<RoleSummary[]>([]);
const roleCode = ref("admin");
const skills = ref<SkillSummary[]>([]);
const apis = ref<ApiRegistration[]>([]);
const skillGrants = ref<RoleSkillGrant[]>([]);
const apiGrants = ref<RoleApiGrant[]>([]);
/** 技能绑定并已审核的接口（`skill_api`），形如 `interface-doctor POST /doctor/list`；键是技能编码 */
const boundRoutes = ref<Record<string, string[]>>({});
/** 正在提交的那一行（`skill:xxx` / `api:3`）：只禁用这一行，别把整页锁住 */
const busy = ref<string | null>(null);
const message = ref<string | null>(null);
const error = ref<string | null>(null);

const currentRole = computed(() => roles.value.find((role) => role.roleCode === roleCode.value) ?? null);

/** 授权行的存在就是"配过"；`canView` 只在这一行配过但被设成不可见时才有区别（界面上单独标出来）。 */
function skillGrant(skillCode: string): RoleSkillGrant | undefined {
  return skillGrants.value.find((grant) => grant.skillCode === skillCode);
}

function apiGrant(apiId: number | undefined): RoleApiGrant | undefined {
  return apiId === undefined ? undefined : apiGrants.value.find((grant) => grant.apiId === apiId);
}

async function loadCatalog(): Promise<void> {
  try {
    roles.value = await listRoles();
    if (roles.value.length && !roles.value.some((role) => role.roleCode === roleCode.value)) {
      roleCode.value = roles.value[0].roleCode;
    }
    skills.value = await listSkills();
    apis.value = await listApis();
    // 绑定接口是「配了技能就顺带拿到哪些接口」的唯一事实，必须取回来——否则管理员配之前看不到它会带来什么，
    // 只能靠猜（这也正是"要不要再逐个接口授一遍"这个疑问的来源）。
    const details = await Promise.all(skills.value.map((skill) => skillDetail(skill.skillCode)));
    boundRoutes.value = Object.fromEntries(
      skills.value.map((skill, index) => [skill.skillCode, details[index].boundRoutes ?? []])
    );
  } catch (e) {
    error.value = text(e);
  }
}

async function loadGrants(): Promise<void> {
  const role = roleCode.value.trim();
  if (!role) {
    error.value = "请选择要配置的角色";
    return;
  }
  error.value = null;
  try {
    // 两张表一次拉完：它们回答的是同一个问题（这个角色能干什么），分两次拉只会让界面闪两下
    const [grantedSkills, grantedApis] = await Promise.all([listRoleSkill(role), listRoleApi(role)]);
    skillGrants.value = grantedSkills;
    apiGrants.value = grantedApis;
  } catch (e) {
    error.value = text(e);
  }
}

function selectRole(): void {
  message.value = null;
  void loadGrants();
}

async function toggleSkill(skill: SkillSummary, event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const wanted = input.checked;
  await toggle(`skill:${skill.skillCode}`, input, wanted, async () => {
    if (wanted) {
      await upsertRoleSkill(roleCode.value, skill.skillCode, true);
      message.value = `已授权：${roleCode.value} 可用技能 ${skill.skillCode}`;
    } else {
      await revokeRoleSkill(roleCode.value, skill.skillCode);
      message.value = `已撤销：${roleCode.value} 不再可用技能 ${skill.skillCode}`;
    }
  });
}

async function toggleApi(api: ApiRegistration, event: Event): Promise<void> {
  const input = event.target as HTMLInputElement;
  const wanted = input.checked;
  if (api.id === undefined) {
    input.checked = false;
    error.value = "这条接口没有 id，先保存接口注册再授权";
    return;
  }
  await toggle(`api:${api.id}`, input, wanted, async () => {
    if (wanted) {
      await upsertRoleApi(roleCode.value, api.id as number);
      message.value = `已授权：${roleCode.value} 可调 ${api.httpMethod} ${api.httpPath}`;
    } else {
      await revokeRoleApi(roleCode.value, api.id as number);
      message.value = `已撤销：${roleCode.value} 不再可调 ${api.httpMethod} ${api.httpPath}`;
    }
  });
}

/**
 * 勾选即落库：授权改动零缓存、下一轮对话就生效（§0.3-4），所以不留"保存"按钮——
 * 留了反而会出现"界面勾着、库里没有"的中间态。提交失败时把勾**弹回去**，不让界面撒谎。
 */
async function toggle(key: string, input: HTMLInputElement, wanted: boolean, write: () => Promise<void>): Promise<void> {
  busy.value = key;
  message.value = null;
  error.value = null;
  try {
    await write();
    await loadGrants();
  } catch (e) {
    input.checked = !wanted;
    error.value = text(e);
  } finally {
    busy.value = null;
  }
}

function label(api: ApiRegistration): string {
  return `${api.httpMethod} ${api.httpPath}`;
}

/** 这个技能绑定并已审核的接口。 */
function routesOf(skillCode: string): string[] {
  return boundRoutes.value[skillCode] ?? [];
}

/** 接口的三元组标识：与 `skill_api` 里存的那串一模一样（服务名 + 大写方法 + 路径）。 */
function routeOf(api: ApiRegistration): string {
  return `${api.service} ${api.httpMethod.toUpperCase()} ${api.httpPath}`;
}

/**
 * 技能表「自带接口」列：服务名对这一眼判断没帮助（整包都挂在同一个接口服务上），
 * 去掉前缀只留「方法 路径」。空包要说清楚是空的，别让人以为是没加载出来。
 */
function routesLabel(skillCode: string): string {
  const routes = routesOf(skillCode);
  return routes.length ? routes.map((route) => route.split(" ").slice(1).join(" ")).join("；") : "无";
}

/**
 * 去「技能包 M3」配这个技能的绑定接口。
 * 「技能 ↔ 接口」只有那一个写口子（`skill_api`，见 SkillView），这页管的是"哪个角色能看到这个技能"；
 * 但管理员在这页看到「自带接口」是空的时，第一反应就是在这里改——给条直达路径，
 * 不然只会得出"没地方配"的结论。
 */
function editBindings(skillCode: string): void {
  router.push({ path: "/admin/skill", query: { bind: skillCode } });
}

/**
 * 接口表「实际来源」列：这行的调用权到底从哪来。
 * 技能带过来的也要算——这行的勾没打，不代表这个角色调不动它，界面必须说出来（§4.6 并集）。
 */
function apiSource(api: ApiRegistration): string {
  const bySkills = skillsBringing(api);
  const direct = apiGrant(api.id) !== undefined;
  if (bySkills.length && direct) return `技能（${bySkills.join("、")}）+ 直配`;
  if (bySkills.length) return `技能（${bySkills.join("、")}）`;
  return direct ? "仅直配" : "无";
}

/** 当前角色已配的技能里，谁把这个接口带进来了。 */
function skillsBringing(api: ApiRegistration): string[] {
  return skillGrants.value
    .map((grant) => grant.skillCode)
    .filter((skillCode) => routesOf(skillCode).includes(routeOf(api)));
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(async () => {
  await loadCatalog();
  await loadGrants();
});
</script>

<template>
  <div>
    <h1>角色授权（技能 / 接口）</h1>
    <p class="muted">
      两张表，取<strong>并集</strong>（§4.6 / ADR-19）：<strong>技能</strong>是独立授权单元，配了技能，
      这个角色既看得到这个技能包，<strong>也顺带拿到该技能绑定并审核通过的接口</strong>——不用把包里的接口再逐个授一遍。
      下面「接口授权」只补<strong>没绑进技能</strong>的那些（写接口也可以绑进技能，这里留给"只授给这个角色"的接口）。
      数据范围不在这里配（§19.1）。
    </p>

    <div class="panel section">
      <div class="row">
        <label class="grow">
          <span class="muted">角色</span>
          <select v-if="roles.length" v-model="roleCode" @change="selectRole">
            <option v-for="role in roles" :key="role.roleCode" :value="role.roleCode">
              {{ role.roleCode }}（{{ role.roleName }}）
            </option>
          </select>
          <input v-else v-model="roleCode" placeholder="boss / director / admin" @keyup.enter="selectRole" />
        </label>
        <button @click="selectRole">查询</button>
      </div>
      <p v-if="currentRole" class="muted">{{ currentRole.remark ?? "（无备注）" }}</p>
      <p v-if="message" class="banner">{{ message }}</p>
      <p v-if="error" class="banner error">{{ error }}</p>
    </div>

    <div class="panel section">
      <h2>技能授权</h2>
      <p class="muted">
        勾上 = 这个角色能看到并使用这个技能，<strong>同时获得「自带接口」里的全部接口</strong>；
        取消勾 = 撤销整行（不再可见，它带来的接口也不再由这一行授出）。
      </p>
      <p class="muted">
        「自带接口」是只读投影。要改<strong>这个技能能调哪些接口</strong>，点行尾<strong>配置</strong>跳到技能包页改
        （写接口也能绑，只是要到下面「接口授权」直接授给角色时才需要勾）。
      </p>
      <table>
        <thead>
          <tr>
            <th>技能</th>
            <th>名称</th>
            <th>自带接口</th>
            <th>绑定接口</th>
            <th>状态</th>
            <th>可用</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.skillCode">
            <td class="mono">{{ skill.skillCode }}</td>
            <td>{{ skill.name }}</td>
            <td class="mono muted">{{ routesLabel(skill.skillCode) }}</td>
            <td><button @click="editBindings(skill.skillCode)">配置</button></td>
            <td>
              <span class="tag" :class="skill.status === 'active' ? 'ok' : 'danger'">{{ skill.status }}</span>
            </td>
            <td>
              <label class="row check">
                <input
                  type="checkbox"
                  :checked="skillGrant(skill.skillCode) !== undefined"
                  :disabled="busy === `skill:${skill.skillCode}`"
                  @change="toggleSkill(skill, $event)"
                />
                <span v-if="skillGrant(skill.skillCode)?.canView === false" class="tag warn">库里配过，但已设为不可见</span>
              </label>
            </td>
          </tr>
          <tr v-if="!skills.length">
            <td colspan="6" class="muted">还没有已发布的技能（先去「技能包 M3」上传并发布）。</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="panel">
      <h2>接口授权</h2>
      <p class="muted">
        勾上 = 这个角色能<strong>直配</strong>这个接口。技能已经带进来的接口<strong>不必再勾</strong>，
        看「实际来源」一列就知道这行现在算不算通。
      </p>
      <table>
        <thead>
          <tr>
            <th>接口</th>
            <th>名称</th>
            <th>注册状态</th>
            <th>实际来源</th>
            <th>可调</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="api in apis" :key="api.id ?? label(api)">
            <td class="mono">{{ label(api) }}</td>
            <td>{{ api.name }}</td>
            <td>
              <span class="tag" :class="api.enabled ? 'ok' : 'danger'">{{ api.enabled ? "enabled" : "disabled" }}</span>
            </td>
            <td class="muted">{{ apiSource(api) }}</td>
            <td>
              <label class="row check">
                <input
                  type="checkbox"
                  :checked="apiGrant(api.id) !== undefined"
                  :disabled="busy === `api:${api.id}`"
                  @change="toggleApi(api, $event)"
                />
              </label>
            </td>
          </tr>
          <tr v-if="!apis.length">
            <td colspan="5" class="muted">还没有已注册的接口（先去「接口注册 M4」登记）。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
/* 勾选框按内容宽度走：全局 input 是 width:100%，用在这里会把整行撑满、点哪儿都能误触 */
.check input[type="checkbox"] {
  width: auto;
}
</style>
