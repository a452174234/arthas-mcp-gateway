import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import BackendListView from '../../views/BackendListView.vue'

// 004 T017：后端管理页（mock adminClient.listBackends，验证列表渲染 + summary + 错误）。
vi.mock('../../api/adminClient', () => ({
  listBackends: vi.fn(),
  createBackend: vi.fn(),
  updateBackend: vi.fn(),
  deleteBackend: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string, public reason?: string) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

import { listBackends, createBackend } from '../../api/adminClient'

const sampleBackend = {
  name: 'order-service', source: 'STATIC', state: 'ACTIVE', healthy: true, breaker: 'CLOSED',
  url: 'http://10.0.0.10:8563', protocol: 'STREAMABLE', authMode: 'NONE',
  connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5,
}

describe('BackendListView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('挂载时加载并渲染后端列表 + summary', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockResolvedValue({
      backends: [sampleBackend],
      summary: { total: 1, healthy: 1, unhealthy: 0 },
    })
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))

    expect(wrapper.find('[data-testid="summary"]').text()).toContain('共 1 个')
    expect(wrapper.findAll('[data-testid="backend-row"]')).toHaveLength(1)
    expect(wrapper.get('[data-testid="backend-row"]').text()).toContain('order-service')
  })

  it('加载失败显示错误提示', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockRejectedValue(
      new (await import('../../api/adminClient')).ApiError(500, '读取失败', 'admin_io_error'),
    )
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))
    expect(wrapper.find('[data-testid="error"]').text()).toContain('读取失败')
  })

  it('新增按钮打开表单，提交触发 createBackend', async () => {
    ;(listBackends as ReturnType<typeof vi.fn>).mockResolvedValue({
      backends: [], summary: { total: 0, healthy: 0, unhealthy: 0 },
    })
    ;(createBackend as ReturnType<typeof vi.fn>).mockResolvedValue(sampleBackend)
    const wrapper = mount(BackendListView, { global: { stubs: ['HealthBadge'] } })
    await new Promise((r) => setTimeout(r, 10))

    await wrapper.get('[data-testid="btn-add"]').trigger('click')
    expect(wrapper.find('[data-testid="backend-form"]').exists()).toBe(true)

    await wrapper.get('[data-testid="form-name"]').setValue('order-service')
    await wrapper.get('[data-testid="form-url"]').setValue('http://10.0.0.10:8563')
    await wrapper.get('[data-testid="form-submit"]').trigger('submit')
    await new Promise((r) => setTimeout(r, 10))

    expect(createBackend).toHaveBeenCalled()
  })
})
