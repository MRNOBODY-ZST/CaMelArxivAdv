import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'

import DashboardAttention from '@/views/dashboard/DashboardAttention.vue'
import DashboardFunnel from '@/views/dashboard/DashboardFunnel.vue'
import DashboardNextAction from '@/views/dashboard/DashboardNextAction.vue'
import DashboardWorkflow from '@/views/dashboard/DashboardWorkflow.vue'
import type {
  DashboardAction,
  DashboardFunnelRow,
  DashboardWorkflowStage,
} from '@/views/dashboard/dashboard.model'

describe('dashboard presentation components', () => {
  it('renders one dominant route action with a real destination', async () => {
    const wrapper = await mountWithRouter(DashboardNextAction, {
      action: routeAction,
    })

    expect(wrapper.get('h2').text()).toBe('检查联系人提取结果')
    expect(wrapper.get('a').attributes('href')).toBe('/contacts')
    expect(wrapper.get('[aria-label="下一步行动"]').text()).toContain('14.8%')
  })

  it('emits retry instead of rendering a fake route for recovery', async () => {
    const wrapper = await mountWithRouter(DashboardNextAction, {
      action: { ...routeAction, kind: 'retry', href: undefined, title: '恢复数据概览', ctaLabel: '重新加载' },
    })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('retry')).toHaveLength(1)
    expect(wrapper.find('a').exists()).toBe(false)
  })

  it('renders the four-stage workflow as real navigation', async () => {
    const wrapper = await mountWithRouter(DashboardWorkflow, { stages })

    expect(wrapper.findAll('li')).toHaveLength(4)
    expect(wrapper.get('[aria-label="工作流程"]').text()).toContain('个性化触达')
    expect(wrapper.findAll('a').map((link) => link.attributes('href'))).toEqual([
      '/arxiv/discovery', '/papers', '/contacts', '/email/campaigns',
    ])
  })

  it('keeps operational attention in one compact, truthful list', () => {
    const wrapper = mount(DashboardAttention, {
      props: {
        activeJobs: 0,
        dataThrough: '2026-08-11T03:20:03Z',
        healthStatus: 'UP',
        healthError: false,
      },
    })

    expect(wrapper.get('[aria-label="需要关注"]').text()).toContain('运行任务')
    expect(wrapper.get('[aria-label="系统状态"]').text()).toContain('运行正常')
    expect(wrapper.text()).toContain('2026/08/11 03:20')
  })

  it('renders funnel widths against the largest real count', () => {
    const rows: DashboardFunnelRow[] = [
      { key: 'imported', label: '已导入', count: 81, widthPercent: 100 },
      { key: 'parsed', label: '已解析', count: 23, widthPercent: 28.4 },
    ]
    const wrapper = mount(DashboardFunnel, { props: { rows } })

    const rendered = wrapper.findAll('[data-testid="funnel-row"]')
    expect(rendered).toHaveLength(2)
    expect(rendered[1]!.get('[data-testid="funnel-bar"]').attributes('style')).toContain('28.4%')
    expect(rendered[1]!.attributes('aria-label')).toBe('已解析：23')
  })
})

const routeAction: DashboardAction = {
  kind: 'route',
  eyebrow: '下一步',
  title: '检查联系人提取结果',
  description: '当前邮箱发现率为 14.8%。先检查低置信度结果，再准备邮件活动。',
  ctaLabel: '查看联系人',
  href: '/contacts',
  tone: 'brand',
}

const stages: DashboardWorkflowStage[] = [
  { key: 'discover', title: '发现与导入', description: '筛选并导入相关论文', valueLabel: '81 篇论文', actionLabel: '发现论文', href: '/arxiv/discovery', tone: 'complete' },
  { key: 'parse', title: '解析论文', description: '下载来源并提取正文', valueLabel: '28.4% 覆盖', actionLabel: '查看论文库', href: '/papers', tone: 'attention' },
  { key: 'contacts', title: '整理联系人', description: '审核邮箱和有效性', valueLabel: '14.8% 邮箱发现率', actionLabel: '查看联系人', href: '/contacts', tone: 'attention' },
  { key: 'outreach', title: '个性化触达', description: '选择收件人并生成草稿', valueLabel: '暂无数据', actionLabel: '管理邮件活动', href: '/email/campaigns', tone: 'neutral' },
]

async function mountWithRouter(component: Parameters<typeof mount>[0], props: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/arxiv/discovery', component: { template: '<div />' } },
      { path: '/papers', component: { template: '<div />' } },
      { path: '/contacts', component: { template: '<div />' } },
      { path: '/email/campaigns', component: { template: '<div />' } },
    ],
  })
  await router.push('/')
  await router.isReady()
  return mount(component, { props, global: { plugins: [router] } })
}
