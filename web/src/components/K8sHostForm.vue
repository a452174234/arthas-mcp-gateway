<script setup lang="ts">
import { ref } from 'vue'
import { createK8sHost } from '../api/adminClient'

// 006 波3：新增 K8S Host 表单（SSH 引导 / kubeconfig 文件 二选一）。密码 type=password，仅写入用不回显。
const emit = defineEmits<{ done: []; cancel: [] }>()

const mode = ref<'ssh' | 'kubeconfig'>('ssh')
const name = ref('')
const namespace = ref('default')
const kubeconfig = ref('')
const sshHost = ref('')
const sshPort = ref<number | undefined>(22)
const sshUser = ref('root')
const sshPassword = ref('')
const kubeconfigRemotePath = ref('/etc/kubernetes/admin.conf')
const serverOverride = ref('')
const insecureSkipTlsVerify = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  if (!name.value.trim()) {
    error.value = 'name 必填'
    return
  }
  try {
    await createK8sHost({
      name: name.value.trim(),
      namespace: namespace.value || 'default',
      kubeconfig: mode.value === 'kubeconfig' ? kubeconfig.value : undefined,
      ssh: mode.value === 'ssh' ? {
        host: sshHost.value,
        port: sshPort.value,
        user: sshUser.value,
        password: sshPassword.value || undefined,
        kubeconfigRemotePath: kubeconfigRemotePath.value,
        serverOverride: serverOverride.value || undefined,
        insecureSkipTlsVerify: insecureSkipTlsVerify.value,
      } : undefined,
    })
    emit('done')
  } catch (e: unknown) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}
</script>

<template>
  <div class="card form">
    <h3>新增 K8S Host</h3>
    <p v-if="error" class="error" data-testid="form-error">{{ error }}</p>
    <label>名称 <input v-model="name" data-testid="form-name" placeholder="corp-prod" /></label>
    <label>命名空间 <input v-model="namespace" placeholder="default" /></label>
    <label>接入方式
      <select v-model="mode" data-testid="form-mode">
        <option value="ssh">SSH 引导（master IP+root+密码）</option>
        <option value="kubeconfig">kubeconfig 文件路径</option>
      </select>
    </label>
    <div v-if="mode === 'ssh'">
      <label>Master IP <input v-model="sshHost" data-testid="form-host" placeholder="10.0.1.5" /></label>
      <label>端口 <input v-model.number="sshPort" type="number" placeholder="22" /></label>
      <label>用户 <input v-model="sshUser" placeholder="root" /></label>
      <label>密码 <input v-model="sshPassword" type="password" data-testid="form-password" /></label>
      <label>远端 kubeconfig 路径 <input v-model="kubeconfigRemotePath" placeholder="/etc/kubernetes/admin.conf" /></label>
      <label>server-override（可选） <input v-model="serverOverride" placeholder="https://10.0.1.5:6443" /></label>
      <label><input v-model="insecureSkipTlsVerify" type="checkbox" /> 跳过 TLS 校验（SAN 不匹配兜底）</label>
    </div>
    <div v-else>
      <label>kubeconfig 文件路径 <input v-model="kubeconfig" placeholder="test-env/k8s/kubeconfig/k3s-admin.yaml" /></label>
    </div>
    <div class="actions">
      <button data-testid="form-submit" @click="submit">保存（触发热重载）</button>
      <button data-testid="form-cancel" @click="emit('cancel')">取消</button>
    </div>
  </div>
</template>

<style scoped>
.form { margin-top: 1em; padding: 1em; border: 1px solid #ddd; }
.form label { display: block; margin: 0.3em 0; }
.form input, .form select { padding: 0.3em; margin-left: 0.5em; }
.error { color: #c00; }
.actions { margin-top: 0.5em; }
</style>
