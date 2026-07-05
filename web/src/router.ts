import { createRouter, createWebHistory } from 'vue-router'

// 004 portal 路由（research.md R10）：后端管理（US1）/ 任务导出（US2）。
// createWebHistory：SPA history 模式，Spring Boot 服务 static、根路径载入 index.html。
// views 懒加载（动态 import）；占位组件在 Phase 3/4 替换为真实实现。
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/backends' },
    { path: '/backends', name: 'backends', component: () => import('./views/BackendListView.vue') },
    { path: '/tasks', name: 'tasks', component: () => import('./views/TaskExportView.vue') },
  ],
})

export default router
