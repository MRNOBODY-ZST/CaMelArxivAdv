import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it, vi } from 'vitest'

import AuthorsAnalyticsView from '@/modules/analytics/AuthorsAnalyticsView.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'

vi.mock('@/modules/analytics/analytics.api', () => ({
  analyticsApi: {
    authors: vi.fn().mockResolvedValue({
      window: { from: '2026-08-01', to: '2026-08-06', dateBasis: 'papers.imported_at', timezone: 'UTC' },
      freshness: { dataThrough: '2026-08-06T10:00:00Z', status: 'CURRENT', generatedAt: '2026-08-06T11:00:00Z' },
      summary: { totalAuthors: 84, totalCollaborations: 132, totalPapers: 25, truncated: false },
      nodes: [
        { id: 'author-a', label: 'Alice Zhang', paperCount: 4, collaboratorCount: 1, contactCount: 2 },
        { id: 'author-b', label: 'Bob Li', paperCount: 3, collaboratorCount: 1, contactCount: 0 },
      ],
      edges: [{ source: 'author-a', target: 'author-b', sharedPaperCount: 2 }],
    }),
    filters: vi.fn().mockResolvedValue({
      minimumDate: '2026-08-01', maximumDate: '2026-08-06', categories: [], jobs: [], users: [], domains: [],
      confidenceLevels: [], relationTypes: [{ id: 'ALL', label: '全部' }],
    }),
  },
}))

describe('AuthorsAnalyticsView', () => {
  it('loads the filter-aware graph and reapplies changed filters', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/analytics/authors', component: AuthorsAnalyticsView }],
    })
    await router.push('/analytics/authors?from=2026-08-01&to=2026-08-06&categoryId=cs.AI')
    await router.isReady()
    const graphStub = {
      props: ['nodes', 'edges', 'loading', 'error'],
      template: '<div data-testid="author-network" :data-nodes="nodes.length" :data-edges="edges.length" />',
    }
    const wrapper = mount(AuthorsAnalyticsView, {
      global: { plugins: [router], stubs: { AuthorNetworkGraph: graphStub } },
    })
    await flushPromises()

    expect(analyticsApi.authors).toHaveBeenCalledWith(expect.objectContaining({
      from: '2026-08-01', to: '2026-08-06', categoryId: 'cs.AI',
    }))
    expect(wrapper.get('h1').text()).toBe('作者关系')
    expect(wrapper.text()).toContain('84')
    expect(wrapper.text()).toContain('132')
    expect(wrapper.text()).toContain('25')
    expect(wrapper.get('[data-testid="author-network"]').attributes('data-nodes')).toBe('2')
    expect(wrapper.get('[data-testid="author-network"]').attributes('data-edges')).toBe('1')
    expect(wrapper.text()).not.toContain('导出 CSV')

    await wrapper.get('input[type="date"]').setValue('2026-07-01')
    await wrapper.get('button.bg-brand-600').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query.from).toBe('2026-07-01')
    expect(analyticsApi.authors).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-07-01' }))
  })
})
