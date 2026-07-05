<script setup lang="ts">
// 004 US1 后端管理页（admin-api-contract §1）。列表 + 增删改 + 健康徽标 + 错误提示。
import { onMounted, ref } from 'vue'
import {
  listBackends, createBackend, updateBackend, deleteBackend,
  type BackendDto, type BackendListResponse,
  type CreateBackendRequest, type UpdateBackendRequest, ApiError,
} from '../api/adminClient'
import BackendForm from '../components/BackendForm.vue'
import HealthBadge from '../components/HealthBadge.vue'

const data = ref<BackendListResponse | null>(null)
const error = ref('')
const showForm = ref(false)
const editing = ref<BackendDto | null>(null)

async function refresh() {
  try {
    data.value = await listBackends()
    error.value = ''
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '加载失败'
  }
}

onMounted(refresh)

function openAdd() {
  editing.value = null
  showForm.value = true
}
function openEdit(b: BackendDto) {
  editing.value = b
  showForm.value = true
}

async function onSubmit(req: CreateBackendRequest | UpdateBackendRequest) {
  try {
    if (editing.value) {
      await updateBackend(editing.value.name, req as UpdateBackendRequest)
    } else {
      await createBackend(req as CreateBackendRequest)
    }
    showForm.value = false
    await refresh()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '保存失败'
  }
}

async function onDelete(b: BackendDto) {
  if (!confirm(`删除后端 ${b.name}？`)) return
  try {
    await deleteBackend(b.name)
    await refresh()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.message}（${e.reason ?? e.status}）` : '删除失败'
  }
}
</script>

<template>
  <section data-testid="backends-view">
    <h2>后端管理</h2>
    <p v-if="data?.summary" class="summary" data-testid="summary">
      共 {{ data.summary.total }} 个（健康 {{ data.summary.healthy }} / 异常 {{ data.summary.unhealthy }}）
    </p>
    <div v-if="error" class="error" data-testid="error">{{ error }}</div>
    <table class="backends-table" data-testid="backends-table">
      <thead><tr><th>名称</th><th>来源</th><th>健康</th><th>URL</th><th>认证</th><th>操作</th></tr></thead>
      <tbody>
        <tr v-for="b in data?.backends ?? []" :key="b.name" data-testid="backend-row">
          <td>{{ b.name }}</td>
          <td>{{ b.source }}</td>
          <td><HealthBadge :healthy="b.healthy" :breaker="b.breaker" /></td>
          <td>{{ b.url }}</td>
          <td>{{ b.authMode }}</td>
          <td>
            <button :data-testid="`edit-${b.name}`" @click="openEdit(b)">编辑</button>
            <button :data-testid="`del-${b.name}`" @click="onDelete(b)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <button data-testid="btn-add" @click="openAdd">新增后端</button>
    <BackendForm v-if="showForm" :initial="editing" @submit="onSubmit" @cancel="showForm = false" />
  </section>
</template>

<style scoped>
.summary { color: var(--text-muted); font-size: 0.9rem; margin: 0.5rem 0 1rem 0; }
.error { color: var(--danger); background: #fef2f2; padding: 0.6rem 0.85rem; border-radius: 0.4rem; border: 1px solid #fecaca; margin: 0.75rem 0; font-size: 0.9rem; }
.backends-table {
  width: 100%; border-collapse: separate; border-spacing: 0; margin: 1rem 0;
  background: var(--card); border-radius: var(--radius); overflow: hidden;
  box-shadow: var(--shadow-sm);
}
.backends-table th {
  background: #f9fafb; color: var(--text-muted); font-weight: 600; font-size: 0.78rem;
  text-transform: uppercase; letter-spacing: 0.05em; padding: 0.75rem 0.85rem;
  text-align: left; border-bottom: 1px solid var(--border);
}
.backends-table td { padding: 0.7rem 0.85rem; border-bottom: 1px solid var(--border); font-size: 0.9rem; }
.backends-table tbody tr:hover { background: #f9fafb; }
.backends-table tbody tr:last-child td { border-bottom: none; }
button {
  padding: 0.35rem 0.8rem; border: 1px solid var(--border); background: var(--card);
  border-radius: 0.35rem; cursor: pointer; font-size: 0.85rem; color: var(--text);
  margin-right: 0.3rem; transition: all 0.15s ease;
}
button:hover { background: #f3f4f6; border-color: #d1d5db; }
[data-testid='btn-add'] { background: var(--primary); color: #fff; border-color: var(--primary); margin-top: 0.5rem; padding: 0.5rem 1.1rem; }
[data-testid='btn-add']:hover { background: var(--primary-dark); border-color: var(--primary-dark); }
</style>
