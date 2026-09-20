import { fileURLToPath, URL } from "node:url";
import vue from "@vitejs/plugin-vue";
import { defineConfig } from "vite";

/**
 * 骨架期两套后端分开部署（§18.3）：对话走 agent-service，登录与管理走 management-service。
 *
 * <p>开发期用代理把它们并到同一个 origin 下——生产由 nginx 做同样的事（见 `nginx.conf`）。
 * 这样前端代码里只有相对路径，不用区分环境，也不会把后端地址硬编码进构建产物。
 */
const AGENT_SERVICE = process.env.VITE_AGENT_SERVICE ?? "http://127.0.0.1:8081";
const MANAGEMENT_SERVICE = process.env.VITE_MANAGEMENT_SERVICE ?? "http://127.0.0.1:8082";

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) }
  },
  server: {
    port: 5173,
    proxy: {
      // SSE 不能被缓冲：http-proxy 默认透传，这里只要不额外加 compress 就没事。
      "/v1/agent": { target: AGENT_SERVICE, changeOrigin: true },
      "/v1/auth": { target: MANAGEMENT_SERVICE, changeOrigin: true },
      "/v1/admin": { target: MANAGEMENT_SERVICE, changeOrigin: true }
    }
  },
  build: { outDir: "dist", sourcemap: true }
});
