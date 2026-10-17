import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Tauri 需要固定端口，并且不能在端口被占用时静默切换
const host = process.env.TAURI_DEV_HOST

/**
 * 开发服务器端口与后端代理目标都可通过环境变量覆盖
 * （start.cmd / start.sh 会从 config/local.env 读端口后注入）：
 *   WEB_PORT        前端端口，默认 5173
 *   BACKEND_PORT    后端端口，默认 8080
 *   VITE_API_TARGET 直接指定代理目标（优先级最高），例如 http://127.0.0.1:9000
 */
const webPort = Number(process.env.WEB_PORT || 5173)
const apiTarget = process.env.VITE_API_TARGET || 'http://127.0.0.1:' + (process.env.BACKEND_PORT || 8080)

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  clearScreen: false,
  server: {
    port: Number.isFinite(webPort) && webPort > 0 ? webPort : 5173,
    strictPort: true,
    host: host || false,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      // 这两个是后端自己提供的公开地址（本地存储直链、缩略图）。
      // 后端返回的是绝对地址，正常不会走到这里；代理上是为了兼容相对地址与老缓存页面。
      '/thumb': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/files': {
        target: apiTarget,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 2000,
  },
})
