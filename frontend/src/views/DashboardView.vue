<script setup lang="ts">
import { ArrowRightIcon, MagnifyingGlassIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { getSystemHealth, type SystemHealth } from '@/api/system'
import AnalyticsChart from '@/modules/analytics/AnalyticsChart.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'
import { dailyOption } from '@/modules/analytics/chartOptions'
import type { AnalyticsQuery, OverviewResponse } from '@/modules/analytics/analytics.types'
import DashboardAttention from '@/views/dashboard/DashboardAttention.vue'
import DashboardFunnel from '@/views/dashboard/DashboardFunnel.vue'
import DashboardNextAction from '@/views/dashboard/DashboardNextAction.vue'
import DashboardWorkflow from '@/views/dashboard/DashboardWorkflow.vue'
import {
  funnelRows,
  resolveDashboardNextAction,
  workflowStages,
} from '@/views/dashboard/dashboard.model'

const health = ref<SystemHealth | null>(null)
const overview = ref<OverviewResponse | null>(null)
const healthError = ref(false)
const analyticsError = ref<string | null>(null)
const loading = ref(true)

const activeJobs = computed(() => overview.value?.activeJobs.reduce((sum, item) => sum + item.count, 0) ?? 0)
const nextAction = computed(() => resolveDashboardNextAction(overview.value, analyticsError.value))
const stages = computed(() => workflowStages(overview.value))
const sourceRows = computed(() => funnelRows(overview.value?.funnel ?? []))
const query = computed<AnalyticsQuery>(() => {
  const today = new Date()
  const from = new Date(today)
  from.setUTCDate(from.getUTCDate() - 29)
  return { from: from.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10), relation: 'ALL' }
})

const analyticsLinks = [
  { label: '采集分析', href: '/analytics/ingestion' },
  { label: '论文分析', href: '/analytics/papers' },
  { label: '联系人分析', href: '/analytics/contacts' },
  { label: '作者关系', href: '/analytics/authors' },
  { label: '活动分析', href: '/analytics/campaigns' },
  { label: '链接分析', href: '/analytics/links' },
] as const

async function load(): Promise<void> {
  loading.value = true
  healthError.value = false
  analyticsError.value = null
  const [healthResult, analyticsResult] = await Promise.allSettled([
    getSystemHealth(), analyticsApi.overview(query.value),
  ])
  if (healthResult.status === 'fulfilled') health.value = healthResult.value
  else healthError.value = true
  if (analyticsResult.status === 'fulfilled') overview.value = analyticsResult.value
  else analyticsError.value = '统计概览暂时不可用'
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-[1520px]">
    <header class="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <h1 class="text-[28px] font-semibold tracking-tight text-slate-950 sm:text-[30px]">
          工作台
        </h1>
        <p class="mt-1.5 text-sm text-slate-500">
          按流程推进论文采集、联系人整理与个性化邮件。
        </p>
      </div>
      <RouterLink
        to="/arxiv/discovery"
        class="inline-flex min-h-11 items-center justify-center gap-2 self-start rounded-md px-3.5 text-sm font-semibold text-brand-600 ring-1 ring-brand-200 ring-inset hover:bg-brand-50 sm:self-auto"
      >
        <MagnifyingGlassIcon
          class="size-4"
          aria-hidden="true"
        />
        发现更多论文
      </RouterLink>
    </header>

    <div class="mt-6 grid gap-6 xl:grid-cols-[minmax(0,1fr)_20rem]">
      <div class="min-w-0 space-y-6">
        <DashboardNextAction
          :action="nextAction"
          @retry="load"
        />
        <DashboardWorkflow :stages="stages" />
      </div>
      <DashboardAttention
        :active-jobs="activeJobs"
        :data-through="overview?.freshness.dataThrough ?? null"
        :health-status="health?.status ?? null"
        :health-error="healthError"
      />
    </div>

    <section
      class="mt-8"
      aria-labelledby="dashboard-evidence-title"
    >
      <div class="flex items-end justify-between gap-4 border-b border-slate-200 pb-4">
        <div>
          <h2
            id="dashboard-evidence-title"
            class="text-base font-semibold text-slate-950"
          >
            数据证据
          </h2>
          <p class="mt-1 text-xs text-slate-500">
            用趋势和来源处理进度验证流程是否正常推进。
          </p>
        </div>
        <RouterLink
          to="/analytics/ingestion"
          class="inline-flex min-h-10 items-center gap-1 text-xs font-semibold text-brand-600 hover:text-brand-700"
        >
          查看完整分析<ArrowRightIcon
            class="size-3.5"
            aria-hidden="true"
          />
        </RouterLink>
      </div>
      <div class="mt-5 grid gap-6 xl:grid-cols-[minmax(0,1.35fr)_minmax(22rem,.85fr)]">
        <AnalyticsChart
          title="每日论文导入"
          description="最近 30 个 UTC 导入日；少于 8 个数据点使用柱状图。"
          :option="dailyOption(overview?.dailyImported ?? [])"
          :empty="(overview?.dailyImported.length ?? 0) === 0"
          :loading="loading"
          :error="analyticsError"
          filename="camel-arxiv-overview-daily"
        />
        <DashboardFunnel
          :rows="sourceRows"
          :loading="loading"
          :error="analyticsError"
        />
      </div>
    </section>

    <nav
      aria-label="深入了解"
      class="mt-6 flex flex-wrap items-center gap-x-5 gap-y-2 border-y border-slate-200 py-4"
    >
      <span class="text-xs font-semibold text-slate-500">深入了解</span>
      <RouterLink
        v-for="link in analyticsLinks"
        :key="link.href"
        :to="link.href"
        class="inline-flex min-h-9 items-center gap-1 text-xs font-medium text-brand-600 hover:text-brand-700"
      >
        {{ link.label }}<ArrowRightIcon
          class="size-3"
          aria-hidden="true"
        />
      </RouterLink>
    </nav>
  </div>
</template>
