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
  display: inline-block;
  padding: 0.1rem 0.5rem;
  border-radius: 0.75rem;
  font-size: 0.8rem;
  color: #fff;
}
.badge-ok {
  background: #2a9d4a;
}
.badge-bad {
  background: #c0392b;
}
</style>
