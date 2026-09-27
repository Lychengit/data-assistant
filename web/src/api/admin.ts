import { request } from "@/api/http";
import { toQuery, type AuditWindow } from "@/lib/time";

/**
 * 管理后台接口（§18.4.6 M2–M6）。
 *
 * 平台元数据（角色 / 接口 / 口径 / 技能 / 审计）**不挂在业务对象树上**，
 * 所以它们直连 management-service，不经数据网关；但入口只有 admin（§20.4）。
 */

export interface RoleApiGrant {
  roleCode: string;
  /** 接口注册行 id（`sys_api.id`）：授权比对的键，服务端和网关都用它 */
  apiId: number;
  httpMethod: string;
  httpPath: string;
  /** 展示用的一行式标识，如 `POST /doctor/performance` */
  route: string;
}

/** 角色（`sys_role`）：授权的**主语**，由 `GET /v1/admin/role` 提供，页面靠它填下拉框而不是让人手敲编码。 */
export interface RoleSummary {
  roleCode: string;
  roleName: string;
  /** 备注：这个角色大概管什么（只读说明） */
  remark: string | null;
}

/** 一条「角色 × 技能」授权（`role_skill`）：回答"这个角色能不能看到并使用这个技能"。 */
export interface RoleSkillGrant {
  roleCode: string;
  /** 技能行 id（`sys_skill.id`） */
  skillId: number;
  skillCode: string;
  skillName: string;
  /**
   * 可见性（`role_skill.can_view`）：不可见的技能不进网关下发的能力集，
   * 也就不会出现在模型的工作区里（§4.7）。
   */
  canView: boolean;
}

export interface ApiRegistration {
  /** 行号（`sys_api.id`）；新增时不填，由服务端分配 */
  id?: number;
  service: string;
  httpMethod: string;
  httpPath: string;
  /** 模型侧工具名（如 `iface_doctor_performance`）：**由路径派生、服务端算好回显**，改路径就改工具名 */
  toolName?: string;
  name: string;
  kind: string;
  resource: string | null;
  paramSchema: Record<string, any> | null;
  enabled: boolean;
  /** 适用场景：什么时候该用、什么时候不该用（进模型工具描述，决定它选不选得对接口） */
  scenario?: string | null;
  /** 返回字段契约：字段名 → {type, description}（进模型工具描述，决定它编不编字段名） */
  resultSchema?: Record<string, any> | null;
}

export interface MetricDefinition {
  metricKey: string;
  domain?: string | null;
  name?: string | null;
  aliases?: string[] | null;
  definition?: string | null;
  formula?: string | null;
  timeBasis?: string | null;
  unit?: string | null;
  scale?: number | null;
  rounding?: number | null;
  derivedOf?: Record<string, any> | null;
  timezone?: string | null;
  basis?: string | null;
  enabled?: boolean;
}

export interface UnitConversion {
  fromUnit: string;
  toUnit: string;
  factor: number;
}

export interface SkillSummary {
  skillCode: string;
  name: string;
  description: string | null;
  status: string;
  latestVersion: string | null;
  latestStatus: string | null;
  updatedAt: string | null;
}

export interface SkillVersion {
  id: number;
  skillCode: string;
  version: string;
  contentSha256: string;
  manifestSha256: string;
  kind: string;
  status: string;
  submittedBy: string;
  publishedBy: string | null;
  publishedAt: string | null;
  createdAt: string | null;
}

export interface SkillCheck {
  code: string;
  severity: string;
  passed: boolean;
  detail: string;
}

export interface SkillUploadResult {
  version: SkillVersion;
  checks: SkillCheck[];
  duplicateContent: boolean;
}

export interface SkillReview {
  id: number;
  skillVersionId: number;
  decision: string;
  reviewer: string;
  reason: string | null;
  exportedColumns: string[] | null;
  createdAt: string | null;
}

// ---------- M2 角色 / 接口授权 ----------

export function listRoleApi(roleCode: string): Promise<RoleApiGrant[]> {
  return request(`/v1/admin/role-api?${new URLSearchParams({ roleCode })}`);
}

export function upsertRoleApi(roleCode: string, apiId: number): Promise<RoleApiGrant> {
  return request("/v1/admin/role-api", { method: "PUT", body: { roleCode, apiId } });
}

export function revokeRoleApi(roleCode: string, apiId: number): Promise<{ revoked: boolean }> {
  return request(`/v1/admin/role-api?${new URLSearchParams({ roleCode, apiId: String(apiId) })}`, {
    method: "DELETE"
  });
}

// ---------- M2 角色清单 ----------

/** 角色只读清单：管理端两张配置表的主语，只有 admin 能读。 */
export function listRoles(): Promise<RoleSummary[]> {
  return request("/v1/admin/role");
}

// ---------- M2 角色 / 技能授权 ----------

/**
 * 角色已配的技能。给技能是**一件事**，不是两件：这个角色既"看得到这个技能包"，
 * **也顺带获得该技能绑定并审核通过的接口**（`skill_api`），授权结果与 `role_api` 取并集（§4.6 / ADR-19）。
 * 没绑进技能的接口（`role_api`）才需要在 `role_api` 里直配。
 */
export function listRoleSkill(roleCode: string): Promise<RoleSkillGrant[]> {
  return request(`/v1/admin/role-skill?${new URLSearchParams({ roleCode })}`);
}

export function upsertRoleSkill(
  roleCode: string,
  skillCode: string,
  canView = true
): Promise<RoleSkillGrant> {
  return request("/v1/admin/role-skill", { method: "PUT", body: { roleCode, skillCode, canView } });
}

export function revokeRoleSkill(roleCode: string, skillCode: string): Promise<{ revoked: boolean }> {
  return request(`/v1/admin/role-skill?${new URLSearchParams({ roleCode, skillCode })}`, {
    method: "DELETE"
  });
}

// ---------- M4 接口注册 ----------

export function listApis(): Promise<ApiRegistration[]> {
  return request("/v1/admin/api");
}

export function upsertApi(registration: ApiRegistration): Promise<ApiRegistration> {
  return request("/v1/admin/api", { method: "PUT", body: registration });
}

export function setApiEnabled(apiId: number, enabled: boolean): Promise<ApiRegistration> {
  return request(`/v1/admin/api/${apiId}/enabled?enabled=${enabled}`, { method: "PUT" });
}

export function deleteApi(apiId: number): Promise<{ deleted: boolean }> {
  return request(`/v1/admin/api/${apiId}`, { method: "DELETE" });
}

// ---------- M5 口径字典 ----------

export function listMetrics(): Promise<MetricDefinition[]> {
  return request("/v1/admin/metric");
}

export function upsertMetric(metric: MetricDefinition): Promise<MetricDefinition> {
  return request("/v1/admin/metric", { method: "PUT", body: metric });
}

export function setMetricEnabled(metricKey: string, enabled: boolean): Promise<Record<string, any>> {
  return request(`/v1/admin/metric/${encodeURIComponent(metricKey)}/enabled?enabled=${enabled}`, { method: "PUT" });
}

export function deleteMetric(metricKey: string): Promise<{ deleted: boolean }> {
  return request(`/v1/admin/metric/${encodeURIComponent(metricKey)}`, { method: "DELETE" });
}

export function listUnitConversions(): Promise<UnitConversion[]> {
  return request("/v1/admin/metric/unit-convert");
}

export function upsertUnitConversion(conversion: UnitConversion): Promise<UnitConversion> {
  return request("/v1/admin/metric/unit-convert", { method: "PUT", body: conversion });
}

// ---------- M3 技能包 ----------

export function listSkills(): Promise<SkillSummary[]> {
  return request("/v1/admin/skill");
}

export function listPendingSkills(): Promise<SkillVersion[]> {
  return request("/v1/admin/skill/pending");
}

/** 技能详情：`boundRoutes` 是该技能**已审核**的绑定接口（`skill_api`），形如 `interface-doctor POST /doctor/list`。 */
export function skillDetail(
  skillCode: string
): Promise<{ skill?: SkillSummary; versions: SkillVersion[]; boundRoutes: string[] }> {
  return request(`/v1/admin/skill/${encodeURIComponent(skillCode)}`);
}

/**
 * 页面改绑技能绑定的接口（`skill_api`）：**整体替换**，不是增量。
 *
 * <p>此前绑定只有技能包 manifest 的 `boundRoutes` 一个来源——换一个接口就要重建包、重算内容哈希、
 * 重走上传与评审，代价高到没人愿意改。所以管理端把「技能能调哪些接口」也做成可配项：这里提交什么集合，
 * 库里就是什么集合（提交空数组 = 清空绑定）。
 *
 * <p>服务端校验口径与上传时的自动检查逐条一致：接口必须已注册且启用（写接口同样可绑）；
 * 一处不合法就整条拒绝（不会「合法的先绑上」）。改动写 `config_audit`，重新发布该技能的包会按 manifest 重置。
 */
export function updateSkillBoundApis(
  skillCode: string,
  boundRoutes: string[]
): Promise<{ skillCode: string; boundRoutes: string[]; updated: boolean }> {
  return request(`/v1/admin/skill/${encodeURIComponent(skillCode)}/apis`, {
    method: "PUT",
    body: { boundRoutes }
  });
}

export function skillChecks(versionId: number): Promise<SkillCheck[]> {
  return request(`/v1/admin/skill/versions/${versionId}/checks`);
}

/**
 * 上传技能包：**内容寻址**，同一个包重复上传不会产生第二版，也不会重跑检查（§19.2）。
 * 包体只走 multipart body，不落 URL、不进日志。
 */
export function uploadSkillPackage(file: File): Promise<SkillUploadResult> {
  const form = new FormData();
  form.append("file", file, file.name);
  return request("/v1/admin/skill/upload", { method: "POST", body: form });
}

/** 评审对象**永远是最新包**：批准即发布成不可变版本（§19.2 / §18.4.6 M3）。 */
export function reviewSkillVersion(
  versionId: number,
  approve: boolean,
  reason: string,
  exportedColumns: string[]
): Promise<SkillVersion> {
  return request(`/v1/admin/skill/versions/${versionId}/review`, {
    method: "POST",
    body: { approve, reason, exportedColumns }
  });
}

export function setSkillEnabled(skillCode: string, enabled: boolean): Promise<Record<string, any>> {
  return request(`/v1/admin/skill/${encodeURIComponent(skillCode)}/enabled`, {
    method: "PUT",
    body: { enabled }
  });
}

/** 停用（不删包：执行记录里的包哈希要一直查得到，§19.2）。 */
export function disableSkill(skillCode: string): Promise<Record<string, any>> {
  return request(`/v1/admin/skill/${encodeURIComponent(skillCode)}`, { method: "DELETE" });
}

// ---------- M6 审计 / 回放 / 监控 ----------

export function auditDataAccess(window: AuditWindow, filter: Record<string, string | undefined> = {}) {
  return request<Record<string, any>[]>(`/v1/admin/audit/data-access?${toQuery(window, filter)}`);
}

export function auditPermission(window: AuditWindow, filter: Record<string, string | undefined> = {}) {
  return request<Record<string, any>[]>(`/v1/admin/audit/permission?${toQuery(window, filter)}`);
}

export function auditConfig(window: AuditWindow, filter: Record<string, string | undefined> = {}) {
  return request<Record<string, any>[]>(`/v1/admin/audit/config?${toQuery(window, filter)}`);
}

/** 「谁在何时查了审计」——审计读本身也留痕（§20.4）。 */
export function auditReads(window: AuditWindow, filter: Record<string, string | undefined> = {}) {
  return request<Record<string, any>[]>(`/v1/admin/audit/reads?${toQuery(window, filter)}`);
}

export function auditTrace(window: AuditWindow, filter: Record<string, string | undefined> = {}) {
  return request<Record<string, any>>(`/v1/admin/audit/trace?${toQuery(window, filter)}`);
}

export function auditOverview(window: AuditWindow) {
  return request<Record<string, any>>(`/v1/admin/audit/overview?${toQuery(window)}`);
}

// ---------- 模型供应商（ADR-14） ----------

/**
 * 供应商配置的**对外视图**：注意这里**没有 apiKey 字段**——
 * 服务端任何一条路径都不回文明文，只给 `keyHint`（§20.1.6）。
 * 前端因此拿不到「能拿去调模型」的字符串，也就不可能把它漏进浏览器存储或前端日志。
 */
export interface LlmProvider {
  providerId: string;
  adapter: string;
  baseUrl: string;
  model: string;
  keyHint: string;
  enabled: boolean;
}

/** 适配器目录：界面用它填下拉框与默认端点（含 DeepSeek 的默认值）。 */
export interface LlmAdapterInfo {
  id: string;
  defaultBaseUrl: string | null;
  defaultModel: string | null;
}

/** 写入体：`apiKey` 留空（null/undefined）= **密钥不变**，只改端点、模型或开关。 */
export interface LlmProviderInput {
  providerId: string;
  adapter: string;
  baseUrl: string;
  model: string;
  apiKey?: string | null;
  enabled: boolean;
}

export function listLlmProviders(): Promise<LlmProvider[]> {
  return request("/v1/admin/llm-provider");
}

export function listLlmAdapters(): Promise<LlmAdapterInfo[]> {
  return request("/v1/admin/llm-provider/adapters");
}

export function upsertLlmProvider(provider: LlmProviderInput): Promise<LlmProvider> {
  return request("/v1/admin/llm-provider", { method: "PUT", body: provider });
}

export function setLlmProviderEnabled(providerId: string, enabled: boolean): Promise<LlmProvider> {
  return request(`/v1/admin/llm-provider/${encodeURIComponent(providerId)}/enabled?enabled=${enabled}`, {
    method: "PUT"
  });
}

export function deleteLlmProvider(providerId: string): Promise<{ deleted: boolean }> {
  return request(`/v1/admin/llm-provider/${encodeURIComponent(providerId)}`, { method: "DELETE" });
}
