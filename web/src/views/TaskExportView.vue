<script setup lang="ts">
// 004 US2 任务导出页（admin-api-contract §2）。输入 taskId → 查询（exportTask 验证 + 展示）→ 下载（attachment 原样）。
import { ref } from 'vue'
import { exportTask, ApiError, type TaskExportDto } from '../api/adminClient'
import DownloadButton from '../components/DownloadButton.vue'

const taskId = ref('')
const dto = ref<TaskExportDto | null>(null)
const error = ref('')

async function query() {
  if (!taskId.value.trim()) return
  try {
    dto.value = await exportTask(taskId.value.trim())
    error.value = ''
  } catch (e) {
    dto.value = null
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '查询失败'
  }
}
</script>

<template>
  <section data-testid="tasks-view">
    <h2>任务导出</h2>
    <div class="input-row">
      <input
        v-model="taskId"
        placeholder="taskId（如 t-abc123）"
        data-testid="task-input"
      />
      <button data-testid="query-btn" @click="query">查询</button>
    </div>
    <div v-if="error" class="error" data-testid="error">{{ error }}</div>
    <div v-if="dto" class="result" data-testid="task-result">
      <p>任务 <strong>{{ dto.taskId }}</strong>（{{ dto.tool }} @ {{ dto.target }}）— {{ dto.status }}</p>
      <p>frames: {{ dto.frames.length }} 条<span v-if="dto.isError">（业务错误标志 isError=true）</span></p>
      <DownloadButton :task-id="dto.taskId" />
    </div>
  </section>
</template>

<style scoped>
.input-row {
  display: flex;
  gap: 0.5rem;
  margin: 1rem 0;
}
.input-row input {
  flex: 1;
  padding: 0.2rem;
}
.error {
  color: #c0392b;
}
.result {
  margin-top: 1rem;
  padding: 0.8rem;
  border: 1px solid #ddd;
  border-radius: 0.3rem;
}
</style>
