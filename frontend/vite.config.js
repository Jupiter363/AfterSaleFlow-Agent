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
