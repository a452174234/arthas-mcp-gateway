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
.input-row { display: flex; gap: 0.5rem; margin: 1rem 0; }
.input-row input {
  flex: 1; padding: 0.55rem 0.75rem; border: 1px solid var(--border);
  border-radius: 0.4rem; font-size: 0.9rem; background: #fff;
}
.input-row input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.input-row button {
  padding: 0.55rem 1.3rem; background: var(--primary); color: #fff;
  border: none; border-radius: 0.4rem; cursor: pointer; font-size: 0.9rem;
  transition: all 0.15s ease;
}
.input-row button:hover { background: var(--primary-dark); }
.error { color: var(--danger); background: #fef2f2; padding: 0.6rem 0.85rem; border-radius: 0.4rem; border: 1px solid #fecaca; margin: 0.75rem 0; font-size: 0.9rem; }
.result {
  margin-top: 1rem; padding: 1.2rem; background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm);
}
.result p { margin: 0.4rem 0; color: var(--text); font-size: 0.9rem; }
.result p strong { color: var(--primary); }
</style>
