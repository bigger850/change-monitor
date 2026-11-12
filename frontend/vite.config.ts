import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    // 本地联调：/api/v1/* -> http://localhost:8080/*
    // 后端 Controller 不带 /v1 前缀，前缀统一由网关（dev proxy / Nginx）剥离
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/v1/, '')
      }
    }
  }
})
