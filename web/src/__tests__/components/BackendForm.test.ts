import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import BackendForm from '../../components/BackendForm.vue'

// 004 T017：后端表单（新增提交 + 动态编辑禁用 INV-DYN-1）。

describe('BackendForm', () => {
  it('新增态：填表提交触发 submit 事件', async () => {
    const wrapper = mount(BackendForm)
    await wrapper.get('[data-testid="form-name"]').setValue('new-svc')
    await wrapper.get('[data-testid="form-url"]').setValue('http://h:8563')
    await wrapper.get('[data-testid="form-submit"]').trigger('submit')

    const events = wrapper.emitted('submit')
    expect(events).toHaveLength(1)
    expect(events![0][0]).toMatchObject({ name: 'new-svc', url: 'http://h:8563' })
  })

  it('动态后端编辑态：禁用提交 + 显示不可编辑警告 invDyn1', async () => {
    const wrapper = mount(BackendForm, {
      props: {
        initial: {
          name: 'dyn', source: 'DYNAMIC', state: 'ACTIVE', healthy: true, breaker: 'CLOSED',
          url: 'http://d:8563', protocol: 'STREAMABLE', authMode: 'BEARER',
          connectTimeoutMs: 5000, callTimeoutMs: 30000, maxConcurrentTasks: 5,
        },
      },
    })
    expect(wrapper.find('[data-testid="dynamic-warn"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="form-submit"]').attributes('disabled')).toBeDefined()
  })
})
