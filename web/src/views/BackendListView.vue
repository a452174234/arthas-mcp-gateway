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
.summary { color: #555; }
.error { color: #c0392b; }
.backends-table { width: 100%; border-collapse: collapse; margin: 1rem 0; }
.backends-table th, .backends-table td { border: 1px solid #ddd; padding: 0.3rem 0.5rem; text-align: left; }
button { margin-right: 0.3rem; }
</style>
