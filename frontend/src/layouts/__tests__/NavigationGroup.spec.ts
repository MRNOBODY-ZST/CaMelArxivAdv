import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'

import NavigationGroup from '@/layouts/NavigationGroup.vue'

const TestIcon = defineComponent({
  name: 'TestIcon',
  setup: () => () => h('svg', { 'aria-hidden': 'true' }),
})

const items = [
  { label: '论文发现', href: '/arxiv/discovery', icon: TestIcon },
  { label: '论文库', href: '/papers', icon: TestIcon },
] as const

describe('NavigationGroup', () => {
  it('keeps a secondary group quiet until the user expands it', async () => {
    const wrapper = await mountGroup('/', false)

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('a').exists()).toBe(false)

    await wrapper.get('button').trigger('click')

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.findAll('a')).toHaveLength(2)
  })

  it('opens the group that contains the active route and marks the route current', async () => {
    const wrapper = await mountGroup('/papers', false)

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('a[aria-current="page"]').text()).toBe('论文库')
  })

  it('opens when navigation moves to one of its descendants', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/arxiv/discovery', component: { template: '<div />' } },
        { path: '/papers', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()
    const wrapper = mount(NavigationGroup, {
      props: { id: 'research', label: '研究数据', items, currentPath: '/', defaultOpen: false },
      global: { plugins: [router] },
    })
    expect(wrapper.get('button').attributes('aria-expanded')).toBe('false')

    await wrapper.setProps({ currentPath: '/arxiv/discovery' })
    await nextTick()

    expect(wrapper.get('button').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('a[aria-current="page"]').attributes('href')).toBe('/arxiv/discovery')
  })
})

async function mountGroup(currentPath: string, defaultOpen: boolean) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/arxiv/discovery', component: { template: '<div />' } },
      { path: '/papers', component: { template: '<div />' } },
    ],
  })
  await router.push(currentPath)
  await router.isReady()
  return mount(NavigationGroup, {
    props: { id: 'research', label: '研究数据', items, currentPath, defaultOpen },
    global: { plugins: [router] },
  })
}
