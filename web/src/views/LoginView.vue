<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ApiError } from "@/api/http";
import { login } from "@/stores/auth";

/**
 * 登录页（§18.4.6 M1 / §19.4）。
 *
 * 失败只显示服务端给的**统一措辞**：不区分「用户不存在 / 口令不对 / 已停用」，
 * 否则这个页面就成了账号探测器（§4.5）。
 */
const route = useRoute();
const router = useRouter();

const username = ref("");
const password = ref("");
const busy = ref(false);
const message = ref<string | null>(null);

async function submit(): Promise<void> {
  message.value = null;
  busy.value = true;
  try {
    await login(username.value.trim(), password.value);
    const next = typeof route.query.next === "string" ? route.query.next : "/";
    await router.replace(next);
  } catch (error) {
    message.value =
      error instanceof ApiError ? error.message : "登录失败，请稍后重试";
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="login">
    <form class="panel" @submit.prevent="submit">
      <h1>医生数据智能助理</h1>
      <p class="muted">请使用院内账号登录。令牌只保存在本标签页，关闭即失效。</p>
      <label>
        <span class="muted">账号</span>
        <input v-model="username" autocomplete="username" required />
      </label>
      <label>
        <span class="muted">口令</span>
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="message" class="banner error">{{ message }}</p>
      <button class="primary" type="submit" :disabled="busy">{{ busy ? "登录中…" : "登录" }}</button>
    </form>
  </div>
</template>

<style scoped>
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 20px;
}

form {
  width: 340px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

p {
  margin: 0;
}
</style>
