import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import App from '../App.vue'

// 004 T007：portal 根组件骨架（导航 + 路由出口）。RouterLink/RouterView stub（单元级，不引 vue-router 完整装配）。
describe('App 根组件', () => {
  it('渲染标题 arthas portal', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterLink', 'RouterView'] } })
    expect(wrapper.find('h1').text()).toBe('arthas portal')
  })

  it('含两个导航入口（后端管理 / 任务导出）', () => {
    const wrapper = mount(App, { global: { stubs: ['RouterLink', 'RouterView'] } })
    const links = wrapper.findAllComponents({ name: 'RouterLink' })
    // stub 后组件名可能为 'router-link'；用 findAll('routerlink-stub') 兜底
    const navEntries = links.length || wrapper.findAll('routerlink-stub').length
    expect(navEntries).toBeGreaterThanOrEqual(2)
  })
})
