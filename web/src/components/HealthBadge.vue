<script setup lang="ts">
// 004 健康徽标（SC-003：healthy/breaker 可视化）。
defineProps<{
  healthy: boolean
  breaker: 'OPEN' | 'CLOSED'
}>()
</script>

<template>
  <span
    class="badge"
    :class="healthy ? 'badge-ok' : 'badge-bad'"
    :data-testid="`health-${healthy ? 'ok' : 'bad'}`"
  >
    {{ healthy ? '健康' : '异常' }} · {{ breaker }}
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex; align-items: center; gap: 0.35rem;
  padding: 0.2rem 0.65rem; border-radius: 1rem;
  font-size: 0.78rem; font-weight: 500;
}
.badge::before { content: ''; width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.badge-ok { background: #d1fae5; color: #065f46; }
.badge-bad { background: #fee2e2; color: #991b1b; }
</style>
