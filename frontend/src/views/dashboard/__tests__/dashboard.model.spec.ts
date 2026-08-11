import { describe, expect, it } from 'vitest'

import type { Metric, OverviewResponse } from '@/modules/analytics/analytics.types'
import {
  dashboardMetric,
  funnelRows,
  resolveDashboardNextAction,
  workflowStages,
} from '@/views/dashboard/dashboard.model'

describe('dashboard model', () => {
  it('promotes recovery when analytics cannot load', () => {
    expect(resolveDashboardNextAction(null, '统计概览暂时不可用')).toMatchObject({
      kind: 'retry',
      title: '恢复数据概览',
      ctaLabel: '重新加载',
    })
  })

  it('promotes active import jobs before later workflow stages', () => {
    const data = overview({
      activeJobs: [{ key: 'RUNNING', label: '运行中', count: 2 }],
      metrics: metricSet({ papers: 81, parsed: 0.9, email: 0.5 }),
    })

    expect(resolveDashboardNextAction(data, null)).toMatchObject({
      href: '/jobs',
      title: '查看正在运行的导入任务',
    })
  })

  it('starts with discovery when the library is empty', () => {
    const data = overview({ metrics: metricSet({ papers: 0, parsed: 0, email: 0 }) })

    expect(resolveDashboardNextAction(data, null)).toMatchObject({
      href: '/arxiv/discovery',
      title: '从论文发现开始',
    })
  })

  it('promotes source parsing while parsed coverage is below eighty percent', () => {
    const data = overview({ metrics: metricSet({ papers: 81, parsed: 0.284, email: 0.148 }) })

    expect(resolveDashboardNextAction(data, null)).toMatchObject({
      href: '/papers',
      title: '继续解析论文来源',
    })
  })

  it('promotes contact review while email discovery is below thirty percent', () => {
    const data = overview({ metrics: metricSet({ papers: 81, parsed: 0.85, email: 0.148 }) })

    expect(resolveDashboardNextAction(data, null)).toMatchObject({
      href: '/contacts',
      title: '检查联系人提取结果',
    })
  })

  it('promotes outreach only after extraction and contact thresholds are met', () => {
    const data = overview({ metrics: metricSet({ papers: 81, parsed: 0.85, email: 0.4 }) })

    expect(resolveDashboardNextAction(data, null)).toMatchObject({
      href: '/email/campaigns',
      title: '准备个性化邮件活动',
    })
  })

  it('finds metrics by stable key before using a compatibility label', () => {
    const stable = metric('cohortPapers', '旧标签', 81, 'count')
    const fallback = metric('legacy', '已导入论文', 30, 'count')

    expect(dashboardMetric([fallback, stable], 'cohortPapers', '已导入论文')).toBe(stable)
    expect(dashboardMetric([fallback], 'cohortPapers', '已导入论文')).toBe(fallback)
  })

  it('keeps all workflow stages honest when metrics are missing', () => {
    const stages = workflowStages(overview({ metrics: [] }))

    expect(stages).toHaveLength(4)
    expect(stages.every((stage) => stage.valueLabel === '暂无数据')).toBe(true)
    expect(stages.map((stage) => stage.href)).toEqual([
      '/arxiv/discovery', '/papers', '/contacts', '/email/campaigns',
    ])
  })

  it('formats real workflow metrics without changing their meaning', () => {
    const stages = workflowStages(overview({ metrics: metricSet({ papers: 81, parsed: 0.284, email: 0.148 }) }))

    expect(stages.map((stage) => stage.valueLabel)).toEqual([
      '81 篇论文', '28.4% 覆盖', '14.8% 邮箱发现率', '暂无数据',
    ])
  })

  it('scales funnel rows against the largest step without summing stages', () => {
    expect(funnelRows([
      { key: 'imported', label: '已导入', count: 81, previousCount: 0, rateFromPrevious: 1 },
      { key: 'parsed', label: '已解析', count: 23, previousCount: 81, rateFromPrevious: 23 / 81 },
    ])).toEqual([
      { key: 'imported', label: '已导入', count: 81, widthPercent: 100 },
      { key: 'parsed', label: '已解析', count: 23, widthPercent: 28.4 },
    ])
  })
})

function overview(overrides: Partial<OverviewResponse> = {}): OverviewResponse {
  return {
    window: { from: '2026-07-13', to: '2026-08-11', dateBasis: 'papers.imported_at', timezone: 'UTC' },
    freshness: { dataThrough: '2026-08-11T03:20:03Z', generatedAt: '2026-08-11T03:21:00Z', status: 'CURRENT' },
    metrics: metricSet({ papers: 81, parsed: 0.284, email: 0.148 }),
    dailyImported: [],
    primaryCategories: [],
    funnel: [],
    activeJobs: [],
    ...overrides,
  }
}

function metricSet(values: { papers: number; parsed: number; email: number }): Metric[] {
  return [
    metric('cohortPapers', '已导入论文', values.papers, 'count'),
    metric('parsedCoverage', '解析覆盖率', values.parsed, 'rate'),
    metric('emailDiscovery', '邮箱发现率', values.email, 'rate'),
  ]
}

function metric(key: string, label: string, value: number, unit: Metric['unit']): Metric {
  return {
    key,
    label,
    value,
    numerator: unit === 'rate' ? Math.round(value * 1000) : value,
    denominator: unit === 'rate' ? 1000 : 1,
    unit,
    definition: `${label} fixture`,
  }
}
