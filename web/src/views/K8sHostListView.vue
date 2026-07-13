<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listK8sHosts, deleteK8sHost, type K8sHostDto } from '@/api/adminClient'
import K8sHostForm from '@/components/K8sHostForm.vue'

// 006 波3：K8S Host 管理（/admin/k8s-hosts CRUD → 热重载）。展示层（宪法原则六）。
const hosts = ref<K8sHostDto[]>([])
const error = ref('')
const showForm = ref(false)

async function refresh() {
  try {
    hosts.value = await listK8sHosts()
    error.value = ''
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

async function onDelete(name: string) {
  if (!confirm(`删除 K8S host「${name}」？将触发热重载。`)) return
  try {
    await deleteK8sHost(name)
    await refresh()
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

onMounted(refresh)
</script>

<template>
  <div class="card">
    <h2>K8S Host 管理</h2>
    <p class="hint">运维经此网页增删改 K8S Host（含 SSH 引导凭证），改完立即热重载生效（不需重启网关）。</p>
    <div class="actions">
      <button data-testid="add-host" @click="showForm = true">+ 新增 Host</button>
      <button data-testid="refresh-hosts" @click="refresh">刷新</button>
    </div>
    <p v-if="error" class="error" data-testid="host-error">{{ error }}</p>
    <table v-if="hosts.length">
      <thead>
        <tr>
          <th>名称</th><th>命名空间</th><th>接入方式</th><th>连接信息</th><th>操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="h in hosts" :key="h.name">
          <td>{{ h.name }}</td>
          <td>{{ h.namespace }}</td>
          <td>{{ h.mode }}</td>
          <td>
            <span v-if="h.mode === 'ssh'">{{ h.sshUser }}@{{ h.sshHost }} → {{ h.kubeconfigRemotePath }}</span>
            <span v-else>{{ h.kubeconfig }}</span>
          </td>
          <td>
            <button :data-testid="`del-${h.name}`" @click="onDelete(h.name)">删除</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p v-else>暂无 K8S Host（点「+ 新增 Host」添加，或手改 config/k8s-hosts.yaml）</p>
    <K8sHostForm v-if="showForm" @done="refresh(); showForm = false" @cancel="showForm = false" />
  </div>
</template>

<style scoped>
.hint { color: #666; font-size: 0.9em; }
.actions { margin: 0.5em 0; }
.error { color: #c00; }
</style>
