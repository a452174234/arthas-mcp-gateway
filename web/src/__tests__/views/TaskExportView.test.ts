import { mount, flushPromises } from '@vue/test-utils'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import TaskExportView from '../../views/TaskExportView.vue'
import type { TaskSummaryDto } from '../../api/adminClient'

// 004 T025 + T040：任务导出页（单查询展示 + 错误）+ 任务列表区（自动查首页/过滤/分页/点项/空态/错误态）。
// DownloadButton stub（其点击由 adminClient.downloadTaskExport 单测覆盖）。
vi.mock('../../api/adminClient', () => ({
  exportTask: vi.fn(),
  downloadTaskExport: vi.fn(),
  listTasks: vi.fn(),
  ApiError: class ApiError extends Error {
    constructor(public status: number, message: string, public reason?: string) {
      super(message)
      this.name = 'ApiError'
    }
  },
}))

import { exportTask, listTasks, ApiError } from '../../api/adminClient'

function summary(taskId: string, status = 'COMPLETED'): TaskSummaryDto {
  return {
    taskId, tool: 'watch', target: 'order-service', status,
    createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z', isError: false,
  }
}

describe('TaskExportView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({ items: [], total: 0, page: 0, size: 20 })
  })

  // ===== 列表区（T040 增量）=====

  it('挂载时自动查任务列表首页', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [summary('t-list-1')], total: 1, page: 0, size: 20,
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    expect(listTasks).toHaveBeenCalledWith({ page: 0, size: 20 })
    expect(wrapper.find('[data-testid="task-list"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-list"]').text()).toContain('t-list-1')
  })

  it('列表为空时显示空态（自验证反馈）', async () => {
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="task-list-empty"]').exists()).toBe(true)
  })

  it('列表加载失败显示错误态（自验证反馈）', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockRejectedValue(new ApiError(500, '服务器错误'))
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    expect(wrapper.find('[data-testid="task-list-error"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="task-list-error"]').text()).toContain('服务器错误')
  })

  it('status 过滤切换重查第一页', async () => {
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()
    await wrapper.get('[data-testid="status-filter"]').setValue('COMPLETED')
    await flushPromises()
    expect(listTasks).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'COMPLETED', page: 0 }))
  })

  it('点列表项填 taskId 并触发查询', async () => {
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [summary('t-pick')], total: 1, page: 0, size: 20,
    })
    ;(exportTask as ReturnType<typeof vi.fn>).mockResolvedValue({
      taskId: 't-pick', tool: 'watch', target: 'order-service', status: 'COMPLETED',
      createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z',
      isError: false, frames: ['frame-a'],
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    await wrapper.get('[data-testid="task-row-t-pick"]').trigger('click')
    await flushPromises()

    expect(exportTask).toHaveBeenCalledWith('t-pick')
    expect(wrapper.find('[data-testid="task-result"]').exists()).toBe(true)
  })

  it('分页下一页递增 page 参数', async () => {
    // 满页（items.length == size）才允许下一页（不满页=末页，按钮 disabled）
    const items = Array.from({ length: 20 }, (_, i) => summary(`t${i}`))
    ;(listTasks as ReturnType<typeof vi.fn>).mockResolvedValue({
      items, total: 25, page: 0, size: 20,
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })
    await flushPromises()

    await wrapper.get('[data-testid="next-page"]').trigger('click')
    await flushPromises()
    expect(listTasks).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }))
  })

  // ===== 单任务查询（T025 既有，保留）=====

  it('查询成功展示任务元信息 + frames 数量', async () => {
    ;(exportTask as ReturnType<typeof vi.fn>).mockResolvedValue({
      taskId: 't1', tool: 'watch', target: 'order-service', status: 'COMPLETED',
      createdAt: '2026-07-06T00:00:00Z', completedAt: '2026-07-06T00:00:05Z',
      isError: false, frames: ['frame-a', 'frame-b'],
    })
    const wrapper = mount(TaskExportView, { global: { stubs: ['DownloadButton'] } })

    await wrapper.get('[data-testid="task-input"]').setValue('t1')
    await wrapper.get('[data-testid="query-btn"]').trigger('click')
    await flushPromises()

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
    await flushPromises()

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
    await flushPromises()

    expect(wrapper.find('[data-testid="error"]').text()).toContain('未知任务')
  })
})
