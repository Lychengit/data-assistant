<script setup lang="ts">
import { useRoute, useRouter } from "vue-router";
import { auth, isAdmin, isLoggedIn, logout } from "@/stores/auth";

const route = useRoute();
const router = useRouter();

const adminLinks = [
  { to: "/admin/role-grant", label: "角色授权（技能/接口）" },
  { to: "/admin/skill", label: "技能包 M3" },
  { to: "/admin/api", label: "接口注册 M4" },
  { to: "/admin/metric", label: "口径字典 M5" },
  { to: "/admin/llm", label: "模型供应商" },
  { to: "/admin/audit", label: "审计 M6" }
];

async function signOut(): Promise<void> {
  await logout();
  await router.push({ name: "login" });
}
</script>

<template>
  <header v-if="isLoggedIn" class="topbar">
    <div class="row">
      <strong>医生数据智能助理</strong>
      <nav class="row">
        <RouterLink to="/">对话</RouterLink>
        <template v-if="isAdmin">
          <RouterLink v-for="link in adminLinks" :key="link.to" :to="link.to">{{ link.label }}</RouterLink>
        </template>
      </nav>
    </div>
    <div class="row">
      <span class="muted">{{ auth.displayName }}（{{ auth.roles.join(" / ") || "无角色" }}）</span>
      <button @click="signOut">登出</button>
    </div>
  </header>
  <main :class="route.meta.public ? '' : 'page'">
    <RouterView />
  </main>
</template>

<style scoped>
.topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 20px;
  background: #fff;
  border-bottom: 1px solid var(--line);
  position: sticky;
  top: 0;
  z-index: 10;
}

nav a {
  padding: 4px 8px;
  border-radius: 6px;
  color: var(--muted);
}

nav a.router-link-active {
  background: var(--brand-soft);
  color: var(--brand);
}
</style>
