<script setup lang="ts">
// 004 任务结果下载按钮（触发浏览器原样下载 attachment，INV-EXP-1）。
import { downloadTaskExport } from '../api/adminClient'

defineProps<{
  taskId: string
  disabled?: boolean
}>()
</script>

<template>
  <button
    :disabled="disabled || !taskId"
    data-testid="download-btn"
    @click="downloadTaskExport(taskId)"
  >
    下载 JSON
  </button>
</template>
