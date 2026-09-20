import { createApp } from "vue";
import App from "@/App.vue";
import { router } from "@/router";
import { restore } from "@/stores/auth";
import "@/style.css";

// 先恢复登录态再挂载：否则受保护路由会在首帧把已登录用户踢回登录页
restore();
createApp(App).use(router).mount("#app");
