<script setup lang="ts">
import { onMounted, ref } from "vue";
import {
  disableSkill,
  listPendingSkills,
  listSkills,
  reviewSkillVersion,
  setSkillEnabled,
  skillChecks,
  skillDetail,
  uploadSkillPackage,
  type SkillCheck,
  type SkillSummary,
  type SkillUploadResult,
  type SkillVersion
} from "@/api/admin";
import { ApiError } from "@/api/http";
import { display } from "@/lib/time";

/**
 * M3 技能包管理（§18.4.6 M3 / §18.5.2 / §19.2）。
 *
 * 界面顺序就是流程顺序：**上传 → 自动检查 → 人工评审 → 发布**。三条口径在页面上直说：
 *  - 内容寻址：同一个包重复上传不会产生第二版，也不会重跑检查；
 *  - 只用最新版：评审对象**永远**是该技能的最新包，旧包不能补发；
 *  - 自动检查里 blocking 不过就**自动驳回**，不会挂在待评审列表里等人点。
 */
const skills = ref<SkillSummary[]>([]);
const pending = ref<SkillVersion[]>([]);
const selected = ref<{ versions: SkillVersion[] } | null>(null);
const selectedCode = ref<string | null>(null);
const checks = ref<Record<number, SkillCheck[]>>({});
const upload = ref<SkillUploadResult | null>(null);

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

async function showChecks(versionId: number): Promise<void> {
  error.value = null;
  try {
    checks.value = { ...checks.value, [versionId]: await skillChecks(versionId) };
  } catch (e) {
    error.value = text(e);
  }
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

function blockingFailures(items: SkillCheck[] | undefined): SkillCheck[] {
  return (items ?? []).filter((check) => check.severity === "blocking" && !check.passed);
}

function text(error: unknown): string {
  return error instanceof ApiError ? error.message : String(error);
}

onMounted(load);
</script>

<template>
  <div>
    <h1>技能包（M3）</h1>
    <p class="muted">
      上传 → 自动检查 → 人工评审 → 发布成不可变版本。包按内容哈希存放，同一个包不会产生第二版；评审对象永远是最新包。
    </p>

    <p v-if="message" class="banner">{{ message }}</p>
    <p v-if="error" class="banner error">{{ error }}</p>

    <div class="panel section">
      <h2>上传技能包</h2>
      <div class="row">
        <input type="file" accept=".zip" :disabled="busy" @change="onFile" style="width: auto" />
        <span class="muted">zip 内含 manifest.json；包体只走请求体，不落 URL、不进日志</span>
      </div>
      <div v-if="upload" class="section">
        <div class="row">
          <span class="tag" :class="upload.version.status === 'checked' ? 'ok' : 'danger'">
            {{ upload.version.status }}
          </span>
          <span class="mono">{{ upload.version.skillCode }} {{ upload.version.version }}</span>
          <span class="muted mono">content {{ upload.version.contentSha256.slice(0, 12) }}…</span>
        </div>
        <ul class="checks">
          <li v-for="check in upload.checks" :key="check.code">
            <span class="tag" :class="check.severity === 'warning' ? 'warn' : check.passed ? 'ok' : 'danger'">
              {{ check.severity }}
            </span>
            <span class="mono">{{ check.code }}</span>
            <span class="muted">{{ check.detail }}</span>
          </li>
        </ul>
        <p v-if="blockingFailures(upload.checks).length" class="banner error">
          自动检查未通过，已自动驳回（不会挂在待评审列表里）。
        </p>
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
            <td><button @click="showChecks(version.id)">检查明细</button></td>
          </tr>
          <tr v-if="!pending.length"><td colspan="6" class="muted">没有待评审的技能包。</td></tr>
        </tbody>
      </table>

      <div v-for="version in pending" :key="'checks-' + version.id" class="section">
        <div v-if="checks[version.id]" class="panel">
          <h2>#{{ version.id }} 自动检查</h2>
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
        <thead><tr><th>技能</th><th>名称</th><th>状态</th><th>最新版本</th><th>版本状态</th><th>更新时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="skill in skills" :key="skill.skillCode">
            <td class="mono">{{ skill.skillCode }}</td>
            <td>{{ skill.name }}</td>
            <td><span class="tag" :class="skill.status === 'active' ? 'ok' : 'danger'">{{ skill.status }}</span></td>
            <td>{{ skill.latestVersion ?? "-" }}</td>
            <td>{{ skill.latestStatus ?? "-" }}</td>
            <td class="muted">{{ display(skill.updatedAt) }}</td>
            <td class="row">
              <button @click="open(skill.skillCode)">详情</button>
              <button @click="toggle(skill)">{{ skill.status === "active" ? "停用" : "启用" }}</button>
            </td>
          </tr>
          <tr v-if="!skills.length"><td colspan="7" class="muted">尚未发布任何技能。</td></tr>
        </tbody>
      </table>

      <div v-if="selected" class="section">
        <h2>{{ selectedCode }} 的版本</h2>
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
      </div>
    </div>
  </div>
</template>

<style scoped>
.checks {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
</style>
