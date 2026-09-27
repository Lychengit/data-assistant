import { createRouter, createWebHistory } from "vue-router";
import { isAdmin, isLoggedIn } from "@/stores/auth";

/**
 * 路由（§14-11 / §18.4.6）：登录页 + 对话页 + 管理页（M2–M6）。
 *
 * 这里的角色判断**只是界面收敛**（不显示进不去的入口），真正的准入一律由服务端判定（§20.4）——
 * 前端守卫被绕过也不影响安全。
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/login",
      name: "login",
      component: () => import("@/views/LoginView.vue"),
      meta: { public: true, title: "登录" }
    },
    { path: "/", name: "chat", component: () => import("@/views/ChatView.vue"), meta: { title: "对话" } },
    {
      path: "/admin/role-grant",
      name: "admin-role-grant",
      component: () => import("@/views/admin/RoleGrantView.vue"),
      meta: { admin: true, title: "角色授权（技能 / 接口）" }
    },
    // 老地址留个重定向：这一页原先是「角色 × 接口」，加技能后换了名字，别让书签变成 404
    { path: "/admin/role-api", redirect: "/admin/role-grant" },
    {
      path: "/admin/skill",
      name: "admin-skill",
      component: () => import("@/views/admin/SkillView.vue"),
      meta: { admin: true, title: "技能包（M3）" }
    },
    {
      path: "/admin/api",
      name: "admin-api",
      component: () => import("@/views/admin/ApiView.vue"),
      meta: { admin: true, title: "接口注册（M4）" }
    },
    {
      path: "/admin/metric",
      name: "admin-metric",
      component: () => import("@/views/admin/MetricView.vue"),
      meta: { admin: true, title: "口径字典（M5）" }
    },
    {
      path: "/admin/audit",
      name: "admin-audit",
      component: () => import("@/views/admin/AuditView.vue"),
      meta: { admin: true, title: "审计与监控（M6）" }
    },
    {
      path: "/admin/llm",
      name: "admin-llm",
      component: () => import("@/views/admin/LlmView.vue"),
      meta: { admin: true, title: "模型供应商" }
    },
    { path: "/:pathMatch(.*)*", redirect: "/" }
  ]
});

router.beforeEach((to) => {
  if (to.meta.public) {
    return true;
  }
  if (!isLoggedIn.value) {
    return { name: "login", query: { next: to.fullPath } };
  }
  if (to.meta.admin && !isAdmin.value) {
    return { name: "chat" };
  }
  return true;
});
