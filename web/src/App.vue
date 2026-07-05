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
:root {
  --primary: #2563eb;
  --primary-dark: #1d4ed8;
  --danger: #dc2626;
  --text: #1f2937;
  --text-muted: #6b7280;
  --bg: #f3f4f6;
  --card: #ffffff;
  --border: #e5e7eb;
  --shadow-sm: 0 1px 3px rgba(0, 0, 0, 0.08);
  --shadow-md: 0 4px 12px rgba(37, 99, 235, 0.15);
  --radius: 0.5rem;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--text); font-family: system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif; }
.portal { max-width: 1200px; margin: 0 auto; padding: 1.5rem; }
.portal-header {
  display: flex; align-items: center; gap: 2rem;
  background: linear-gradient(135deg, #1e3a8a 0%, #2563eb 100%);
  color: #fff; padding: 1rem 1.5rem; border-radius: var(--radius);
  box-shadow: var(--shadow-md); margin-bottom: 1.5rem;
}
.portal-header h1 { font-size: 1.25rem; margin: 0; font-weight: 600; letter-spacing: 0.02em; }
.portal-header nav { display: flex; gap: 0.25rem; margin-left: auto; }
.portal-header nav a {
  color: rgba(255, 255, 255, 0.85); text-decoration: none;
  padding: 0.4rem 0.95rem; border-radius: 0.4rem; font-size: 0.9rem;
  transition: all 0.15s ease;
}
.portal-header nav a:hover { background: rgba(255, 255, 255, 0.15); color: #fff; }
.portal-header nav a.router-link-active { background: rgba(255, 255, 255, 0.25); color: #fff; font-weight: 600; }
section h2 { font-size: 1.4rem; margin: 0 0 0.5rem 0; font-weight: 600; color: var(--text); }
</style>
