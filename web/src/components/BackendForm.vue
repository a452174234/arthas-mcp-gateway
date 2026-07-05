<script setup lang="ts">
// 004 后端新增/编辑表单（admin-api-contract §1）。动态后端不可改（INV-DYN-1）→ 编辑态禁用动态。
import { reactive, watch } from 'vue'
import type { BackendDto, CreateBackendRequest, UpdateBackendRequest } from '../api/adminClient'

const props = defineProps<{
  initial?: BackendDto | null
}>()

const emit = defineEmits<{
  (e: 'submit', req: CreateBackendRequest | UpdateBackendRequest): void
  (e: 'cancel'): void
}>()

const editing = !!props.initial
const dynamic = props.initial?.source === 'DYNAMIC'

const form = reactive({
  name: props.initial?.name ?? '',
  url: props.initial?.url ?? '',
  protocol: props.initial?.protocol ?? 'STREAMABLE',
  authMode: props.initial?.authMode ?? 'NONE',
  token: '',
  maxConcurrentTasks: props.initial?.maxConcurrentTasks ?? 5,
})

watch(
  () => props.initial,
  (init) => {
    if (init) {
      form.name = init.name
      form.url = init.url
      form.protocol = init.protocol
      form.authMode = init.authMode
      form.maxConcurrentTasks = init.maxConcurrentTasks
    }
  },
)

function onSubmit() {
  if (editing) {
    if (dynamic) return // 动态不可改（INV-DYN-1）
    emit('submit', { url: form.url, authMode: form.authMode, maxConcurrentTasks: form.maxConcurrentTasks })
  } else {
    emit('submit', {
      name: form.name,
      url: form.url,
      protocol: form.protocol,
      authMode: form.authMode,
      token: form.token || undefined,
      maxConcurrentTasks: form.maxConcurrentTasks,
    })
  }
}
</script>

<template>
  <form class="backend-form" data-testid="backend-form" @submit.prevent="onSubmit">
    <label>名称<input v-model="form.name" :disabled="editing" required data-testid="form-name" /></label>
    <label>URL<input v-model="form.url" required data-testid="form-url" /></label>
    <label>协议
      <select v-model="form.protocol" :disabled="dynamic" data-testid="form-protocol">
        <option>STREAMABLE</option>
        <option>STATELESS</option>
      </select>
    </label>
    <label>认证
      <select v-model="form.authMode" :disabled="dynamic" data-testid="form-auth">
        <option>NONE</option>
        <option>BEARER</option>
        <option>BASIC</option>
      </select>
    </label>
    <label v-if="!editing || form.authMode === 'BEARER'">Token<input v-model="form.token" data-testid="form-token" /></label>
    <label>并发上限<input
        v-model.number="form.maxConcurrentTasks"
        type="number"
        min="1"
        max="5"
        :disabled="dynamic"
        data-testid="form-max"
      /></label>
    <div v-if="dynamic" class="dynamic-warn" data-testid="dynamic-warn">动态后端不可编辑（须先删再 ensure）</div>
    <div class="actions">
      <button type="submit" :disabled="dynamic" data-testid="form-submit">{{ editing ? '保存' : '新增' }}</button>
      <button type="button" data-testid="form-cancel" @click="emit('cancel')">取消</button>
    </div>
  </form>
</template>

<style scoped>
.backend-form {
  display: grid;
  gap: 0.5rem;
}
.backend-form label {
  display: flex;
  gap: 0.5rem;
  align-items: center;
  font-size: 0.9rem;
}
.backend-form input,
.backend-form select {
  flex: 1;
  padding: 0.2rem;
}
.dynamic-warn {
  color: #c0392b;
  font-size: 0.85rem;
}
.actions {
  display: flex;
  gap: 0.5rem;
}
</style>
