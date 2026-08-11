import type { FunnelStep, Metric, OverviewResponse } from '@/modules/analytics/analytics.types'

export interface DashboardAction {
  kind: 'route' | 'retry'
  eyebrow: '下一步'
  title: string
  description: string
  ctaLabel: string
  href?: string
  tone: 'brand' | 'warning'
}

export interface DashboardWorkflowStage {
  key: 'discover' | 'parse' | 'contacts' | 'outreach'
  title: string
  description: string
  valueLabel: string
  actionLabel: string
  href: string
  tone: 'complete' | 'attention' | 'neutral'
}

export interface DashboardFunnelRow {
  key: string
  label: string
  count: number
  widthPercent: number
}

export function resolveDashboardNextAction(
  overview: OverviewResponse | null,
  analyticsError: string | null,
): DashboardAction {
  if (analyticsError) {
    return action({
      kind: 'retry',
      title: '恢复数据概览',
      description: '暂时无法读取工作台指标。重新加载后再决定下一步。',
      ctaLabel: '重新加载',
      tone: 'warning',
    })
  }

  const activeJobs = overview?.activeJobs.reduce((total, item) => total + item.count, 0) ?? 0
  if (activeJobs > 0) {
    return action({
      title: '查看正在运行的导入任务',
      description: `当前有 ${activeJobs} 个任务仍在排队、运行或暂停。先确认进度和异常，再继续处理数据。`,
      ctaLabel: '查看导入任务',
      href: '/jobs',
    })
  }

  const papers = dashboardMetric(overview?.metrics ?? [], 'cohortPapers', '已导入论文')
  if (!papers || papers.value <= 0) {
    return action({
      title: '从论文发现开始',
      description: '当前范围还没有可用论文。先设置检索条件并选择需要导入的研究成果。',
      ctaLabel: '发现论文',
      href: '/arxiv/discovery',
    })
  }

  const parsed = dashboardMetric(overview?.metrics ?? [], 'parsedCoverage', '解析覆盖率')
  if (!parsed || parsed.value < 0.8) {
    return action({
      title: '继续解析论文来源',
      description: parsed
        ? `当前解析覆盖率为 ${formatRate(parsed.value)}。先补齐论文来源，联系人提取才会更完整。`
        : '当前还没有解析覆盖率。先检查已导入论文的来源解析状态。',
      ctaLabel: '查看论文库',
      href: '/papers',
    })
  }

  const email = dashboardMetric(overview?.metrics ?? [], 'emailDiscovery', '邮箱发现率')
  if (!email || email.value < 0.3) {
    return action({
      title: '检查联系人提取结果',
      description: email
        ? `当前邮箱发现率为 ${formatRate(email.value)}。先检查低置信度结果，再准备邮件活动。`
        : '当前还没有邮箱发现率。先检查联系人提取结果和有效性标记。',
      ctaLabel: '查看联系人',
      href: '/contacts',
    })
  }

  return action({
    title: '准备个性化邮件活动',
    description: '论文解析和联系人覆盖已达到建议阈值，可以选择收件人并生成个性化邮件草稿。',
    ctaLabel: '创建邮件活动',
    href: '/email/campaigns',
  })
}

function action(values: Omit<DashboardAction, 'eyebrow' | 'kind' | 'tone'> & Partial<Pick<DashboardAction, 'kind' | 'tone'>>): DashboardAction {
  return {
    kind: values.kind ?? 'route',
    eyebrow: '下一步',
    tone: values.tone ?? 'brand',
    ...values,
  }
}

export function dashboardMetric(
  metrics: Metric[],
  key: string,
  fallbackLabel: string,
): Metric | undefined {
  return metrics.find((metric) => metric.key === key)
    ?? metrics.find((metric) => metric.label === fallbackLabel)
}

export function workflowStages(overview: OverviewResponse | null): DashboardWorkflowStage[] {
  const metrics = overview?.metrics ?? []
  const papers = dashboardMetric(metrics, 'cohortPapers', '已导入论文')
  const parsed = dashboardMetric(metrics, 'parsedCoverage', '解析覆盖率')
  const email = dashboardMetric(metrics, 'emailDiscovery', '邮箱发现率')
  return [
    {
      key: 'discover',
      title: '发现与导入',
      description: '筛选并导入相关论文',
      valueLabel: papers ? `${formatCount(papers.value)} 篇论文` : '暂无数据',
      actionLabel: '发现论文',
      href: '/arxiv/discovery',
      tone: papers && papers.value > 0 ? 'complete' : 'neutral',
    },
    {
      key: 'parse',
      title: '解析论文',
      description: '下载来源并提取正文',
      valueLabel: parsed ? `${formatRate(parsed.value)} 覆盖` : '暂无数据',
      actionLabel: '查看论文库',
      href: '/papers',
      tone: rateTone(parsed, 0.8),
    },
    {
      key: 'contacts',
      title: '整理联系人',
      description: '审核邮箱和有效性',
      valueLabel: email ? `${formatRate(email.value)} 邮箱发现率` : '暂无数据',
      actionLabel: '查看联系人',
      href: '/contacts',
      tone: rateTone(email, 0.3),
    },
    {
      key: 'outreach',
      title: '个性化触达',
      description: '选择收件人并生成草稿',
      valueLabel: '暂无数据',
      actionLabel: '管理邮件活动',
      href: '/email/campaigns',
      tone: 'neutral',
    },
  ]
}

export function funnelRows(steps: FunnelStep[]): DashboardFunnelRow[] {
  const maximum = Math.max(0, ...steps.map((step) => step.count))
  return steps.map((step) => ({
    key: step.key,
    label: step.label,
    count: step.count,
    widthPercent: maximum === 0 ? 0 : Math.round((step.count / maximum) * 1_000) / 10,
  }))
}

function formatCount(value: number): string {
  return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 0 }).format(value)
}

function formatRate(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

function rateTone(metric: Metric | undefined, threshold: number): DashboardWorkflowStage['tone'] {
  if (!metric) return 'neutral'
  return metric.value >= threshold ? 'complete' : 'attention'
}
