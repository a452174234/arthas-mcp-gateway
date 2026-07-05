/// <reference types="vitest" />
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 004 portal 前端构建配置（research.md R10/R11/R12）：
//  - build.outDir → ../target/classes/static：产物落 Maven 输出，Spring Boot 打包进 JAR
//    （同源服务、单 JAR；target/ 已 gitignore，不入库）
//  - dev server proxy /admin + /actuator → :8761：开发期前后端协作（HMR）
//  - test: jsdom 环境（Vitest 组件测试，@vue/test-utils）
export default defineConfig({
  plugins: [vue()],
  build: {
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
  server: {
    port: 5173,
    proxy: {
      '/admin': 'http://localhost:8761',
      '/actuator': 'http://localhost:8761',
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
})
