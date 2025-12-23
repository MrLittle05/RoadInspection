import { defineConfig } from "vite";
import path from "path";
import { fileURLToPath } from "url";
import { viteSingleFile } from "vite-plugin-singlefile";

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

export default defineConfig({
  base: "./", // 确保相对路径，适配 WebView
  build: {
    outDir: path.resolve(__dirname, "../app/src/main/assets"),
    emptyOutDir: true, // 每次构建前清空目标文件夹，防止旧文件残留
  },
  plugins: [viteSingleFile()],
});
