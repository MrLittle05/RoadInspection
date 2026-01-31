import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { resolve } from "path";

export default defineConfig(({ mode }) => {
  // 加载环境变量
  const env = loadEnv(mode, process.cwd(), "");

  return {
    // [WebView 核心配置]
    // 强制使用相对路径 (./)，否则在 Android 手机上会因找不到根目录导致白屏
    base: "./",

    plugins: [react()],

    resolve: {
      alias: {
        "@": resolve(__dirname, "."),
      },
    },

    server: {
      port: 3000,
      host: "0.0.0.0",
    },

    build: {
      // [输出路径核心配置]
      // 将构建产物直接输出到 Android 项目的 assets 目录
      outDir: resolve(__dirname, "../app/src/main/assets"),

      // 构建前清空目标目录 (注意：这会删除 assets 下所有现有文件！)
      emptyOutDir: true,

      // [多页应用配置]
      // 告诉 Vite 有两个入口
      rollupOptions: {
        input: {
          // 主 React 应用入口
          main: resolve(__dirname, "index.html"),
          // 巡检页面入口 (原生 JS)
          inspection: resolve(__dirname, "inspection.html"),
        },
      },
    },
  };
});
