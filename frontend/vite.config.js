// 文件作用：前端工程代码文件，支撑售后争议系统的页面、交互、样式或构建配置。
// 说明：本注释用于帮助读者先了解本文件职责，再继续阅读具体实现。

import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: process.env.VITE_API_PROXY_TARGET || "http://127.0.0.1:8080",
        changeOrigin: true,
      },
      "/agent-api": {
        target:
          process.env.VITE_AGENT_API_PROXY_TARGET || "http://127.0.0.1:18000",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/agent-api/, ""),
      },
      "/ocr-api": {
        target: process.env.VITE_OCR_API_PROXY_TARGET || "http://127.0.0.1:18010",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/ocr-api/, ""),
      },
    },
  },
  test: { environment: "jsdom" },
});
