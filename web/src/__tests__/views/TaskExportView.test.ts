import { mount } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import TaskExportView from '../../views/TaskExportView.vue'

// 004 T025：任务导出页（查询展示 + 错误提示）。DownloadButton stub（其点击由 adminClient.downloadTaskExport 单测覆盖）。
vi.mock('../../api/adminClient', () => ({
  exportTask: vi.fn(),
  downloadTaskExport: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string, public reason?: string) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

import { exportTask, ApiError } from '../../api/adminClient'

describe('TaskExportView', () => {
  beforeEach(() => vi.clearAllMocks())

  it('查询成功展示任务元信息 + frames 数量', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockResolvedValue({
      taskId: 't1', tool: 'watch', target: 'order-service', status: 'COMPLETED',
      createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z',
      isError: false, frames: ['frame-a', 'frame-b'],
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('t1')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await new Promise((r) => setTimeout(r, 10))

    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-result"]').text()).toContain('frames: 2')
    expect(wrapper.get('[data-testid="task-result"]').text()).toContain('order-service')
  })

  it('查询失败（409 未完成）显示错误提示', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(409, '任务未完成', 'task_not_completed'),
    )
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('t-x')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await new Promise((r) => setTimeout(r, 10))

    expect(wrapper.find('[data-testid="error"]').text()).toContain('任务未完成')
    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(false)
  })

  it('查询失败（404 不存在）显示错误', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(404, '未知任务', 'task_not_found'),
    )
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('nope')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await new Promise((r) => setTimeout(r, 10))

    expect(wrapper.find('[data-testid="error"]').text()).toContain('未知任务')
  })
})
