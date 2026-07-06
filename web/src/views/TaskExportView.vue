<script setup lang="ts">
// 004 US2 任务导出页（admin-api-contract §2）+ 增量任务列表区（FR-015）。
// 上方：最近任务列表（自动查首页 + status 过滤 + 分页 + 点项填 taskId 衔接导出）。
// 下方：按 taskId 查询 + 下载（attachment 原样）。
import { ref, onMounted } from 'vue'
import { exportTask, listTasks, ApiError, type TaskExportDto, type TaskSummaryDto } from '../api/adminClient'
import DownloadButton from '../components/DownloadButton.vue'

// ===== 列表区状态 =====
const tasks = ref<TaskSummaryDto[]>([])
const total = ref(0)
const page = ref(0)
const size = ref(20)
const statusFilter = ref('')
const listLoading = ref(false)
const listError = ref('')

// ===== 单任务查询状态（既有）=====
const taskId = ref('')
const dto = ref<TaskExportDto | null>(null)
const error = ref('')

async function loadList() {
  listLoading.value = true
  listError.value = ''
  try {
    const params: { page: number; size: number; status?: string } = { page: page.value, size: size.value }
    if (statusFilter.value) params.status = statusFilter.value
    const res = await listTasks(params)
    tasks.value = res.items
    total.value = res.total
  } catch (e) {
    tasks.value = []
    total.value = 0
    listError.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '列表加载失败'
  } finally {
    listLoading.value = false
  }
}

function onStatusChange(e: Event) {
  statusFilter.value = (e.target as HTMLSelectElement).value
  page.value = 0
  loadList()
}

function nextPage() {
  page.value++
  loadList()
}

function prevPage() {
  if (page.value > 0) {
    page.value--
    loadList()
  }
}

/** 点列表项 → 填 taskId + 触发查询，衔接既有导出流。 */
function pickTask(t: TaskSummaryDto) {
  taskId.value = t.taskId
  query()
}

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

onMounted(() => loadList())
</script>

<template>
  <section data-testid="tasks-view">
    <h2>任务导出</h2>

    <!-- ===== 列表区（增量 FR-015）===== -->
    <div class="list-section">
      <div class="list-header">
        <h3>最近任务 <span class="count">（{{ total }}）</span></h3>
        <select data-testid="status-filter" :value="statusFilter" @change="onStatusChange">
          <option value="">全部状态</option>
          <option value="WORKING">WORKING</option>
          <option value="COMPLETED">COMPLETED</option>
          <option value="FAILED">FAILED</option>
          <option value="CANCELLED">CANCELLED</option>
        </select>
      </div>

      <div v-if="listLoading" class="state-msg" data-testid="task-list-loading">加载中…</div>
      <div v-else-if="listError" class="error" data-testid="task-list-error">{{ listError }}</div>
      <div v-else-if="tasks.length === 0" class="state-msg" data-testid="task-list-empty">暂无任务</div>
      <table v-else data-testid="task-list" class="task-table">
        <thead>
          <tr>
            <th>taskId</th><th>工具</th><th>目标</th><th>状态</th><th>创建时间</th><th>错误</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="t in tasks"
            :key="t.taskId"
            :data-testid="`task-row-${t.taskId}`"
            class="task-row"
            @click="pickTask(t)"
          >
            <td class="mono">{{ t.taskId }}</td>
            <td>{{ t.tool }}</td>
            <td>{{ t.target }}</td>
            <td><span class="status-badge" :class="`status-${t.status.toLowerCase()}`">{{ t.status }}</span></td>
            <td class="mono small">{{ t.createdAt }}</td>
            <td>{{ t.isError ? '是' : '—' }}</td>
          </tr>
        </tbody>
      </table>

      <div class="pagination">
        <button data-testid="prev-page" :disabled="page === 0" @click="prevPage">上一页</button>
        <span data-testid="page-info">第 {{ page + 1 }} 页 / 共 {{ Math.max(1, Math.ceil(total / size)) }} 页</span>
        <button data-testid="next-page" :disabled="tasks.length < size" @click="nextPage">下一页</button>
      </div>
    </div>

    <!-- ===== 单任务查询 + 导出（既有）===== -->
    <h3 class="query-title">按 taskId 查询 / 导出</h3>
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
.list-section {
  margin: 1rem 0 1.5rem; padding: 1.1rem 1.2rem; background: var(--card);
  border: 1px solid var(--border); border-radius: var(--radius); box-shadow: var(--shadow-sm);
}
.list-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 0.75rem; }
.list-header h3 { margin: 0; font-size: 1rem; color: var(--text); }
.count { color: var(--text-light); font-weight: normal; }
.list-header select {
  padding: 0.35rem 0.55rem; border: 1px solid var(--border); border-radius: 0.4rem;
  background: #fff; font-size: 0.85rem; cursor: pointer;
}
.list-header select:focus { outline: none; border-color: var(--primary); }
.state-msg { padding: 1.2rem; text-align: center; color: var(--text-light); font-size: 0.9rem; }
.task-table { width: 100%; border-collapse: collapse; font-size: 0.88rem; }
.task-table th {
  text-align: left; padding: 0.55rem 0.6rem; background: #f9fafb; color: var(--text);
  font-weight: 600; border-bottom: 1px solid var(--border); font-size: 0.82rem;
}
.task-table td { padding: 0.5rem 0.6rem; border-bottom: 1px solid #f3f4f6; color: var(--text); }
.task-row { cursor: pointer; transition: background 0.12s ease; }
.task-row:hover { background: #eff6ff; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 0.82rem; }
.small { font-size: 0.78rem; color: var(--text-light); }
.status-badge {
  display: inline-block; padding: 0.15rem 0.5rem; border-radius: 1rem;
  font-size: 0.74rem; font-weight: 500;
}
.status-completed { background: #d1fae5; color: #065f46; }
.status-working { background: #fef3c7; color: #92400e; }
.status-failed { background: #fee2e2; color: #991b1b; }
.status-cancelled { background: #e5e7eb; color: #4b5563; }
.pagination { display: flex; align-items: center; gap: 0.85rem; margin-top: 0.85rem; font-size: 0.85rem; color: var(--text-light); }
.pagination button {
  padding: 0.3rem 0.85rem; border: 1px solid var(--border); border-radius: 0.4rem;
  background: #fff; cursor: pointer; font-size: 0.82rem; transition: all 0.15s ease;
}
.pagination button:hover:not(:disabled) { border-color: var(--primary); color: var(--primary); }
.pagination button:disabled { opacity: 0.4; cursor: not-allowed; }
.query-title { margin: 1.5rem 0 0.5rem; font-size: 1rem; color: var(--text); }
.input-row { display: flex; gap: 0.5rem; margin: 0.5rem 0; }
.input-row input {
  flex: 1; padding: 0.55rem 0.75rem; border: 1px solid var(--border);
  border-radius: 0.4rem; font-size: 0.9rem; background: #fff;
}
.input-row input:focus { outline: none; border-color: var(--primary); box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12); }
.input-row button {
  padding: 0.55rem 1.3rem; background: var(--primary); color: #fff;
  border: none; border-radius: 0.4rem; cursor: pointer; font-size: 0.9rem; transition: all 0.15s ease;
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
