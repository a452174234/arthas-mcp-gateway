<script setup lang="ts">
// 004 portal 根组件：顶部导航 + 路由出口（后端管理 US1 / 任务导出 US2）。
</script>

<template>
  <div class="portal">
    <header class="portal-header">
      <h1>arthas portal</h1>
      <nav>
        <RouterLink to="/backends">后端管理</RouterLink>
        <RouterLink to="/tasks">任务导出</RouterLink>
      </nav>
    </header>
    <main class="portal-main">
      <RouterView />
    </main>
  </div>
</template>

<style>
.portal { font-family: system-ui, -apple-system, sans-serif; max-width: 1100px; margin: 0 auto; padding: 1rem; color: #222; }
.portal-header { display: flex; gap: 1.5rem; align-items: baseline; border-bottom: 1px solid #ddd; padding-bottom: .5rem; margin-bottom: 1.5rem; }
.portal-header h1 { font-size: 1.2rem; margin: 0; }
.portal-header nav a { margin-right: 1.2rem; text-decoration: none; color: #3366cc; }
.portal-header nav a.router-link-active { font-weight: bold; color: #000; }
</style>
