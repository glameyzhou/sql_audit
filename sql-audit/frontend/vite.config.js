import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      // W2 的 server API 前缀（契约占位，后端起来后自动转发）
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
