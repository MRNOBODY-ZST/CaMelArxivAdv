<script setup lang="ts">
import { ArrowRightIcon, CheckCircleIcon, ExclamationTriangleIcon, ServerStackIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import { getSystemHealth, type SystemHealth } from '@/api/system'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'
import AnalyticsChart from '@/modules/analytics/AnalyticsChart.vue'
import AnalyticsMetricGrid from '@/modules/analytics/AnalyticsMetricGrid.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'
import { countBars, dailyOption, funnelBars } from '@/modules/analytics/chartOptions'
import type { AnalyticsQuery, OverviewResponse } from '@/modules/analytics/analytics.types'

const health = ref<SystemHealth | null>(null)
const overview = ref<OverviewResponse | null>(null)
const healthError = ref(false)
const analyticsError = ref<string | null>(null)
const loading = ref(true)

const healthIsUp = computed(() => health.value?.status === 'UP')
const healthLabel = computed(() => healthIsUp.value ? '运行正常' : health.value ? '状态异常' : '检查中')
const activeJobs = computed(() => overview.value?.activeJobs.reduce((sum, item) => sum + item.count, 0) ?? 0)
const query = computed<AnalyticsQuery>(() => {
  const today = new Date()
  const from = new Date(today)
  from.setUTCDate(from.getUTCDate() - 29)
  return { from: from.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10), relation: 'ALL' }
})

async function load(): Promise<void> {
  loading.value = true
  healthError.value = false
  analyticsError.value = null
  const [healthResult, analyticsResult] = await Promise.allSettled([
    getSystemHealth(), analyticsApi.overview(query.value),
  ])
  if (healthResult.status === 'fulfilled') health.value = healthResult.value
  else { health.value = null; healthError.value = true }
  if (analyticsResult.status === 'fulfilled') overview.value = analyticsResult.value
  else { overview.value = null; analyticsError.value = '统计概览暂时不可用' }
  loading.value = false
}

onMounted(load)
</script>

<template>
  <div class="mx-auto max-w-[1600px]">
    <div class="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <p class="text-sm text-slate-500">
          概览 / 数据总览
        </p>
        <h1 class="mt-1 text-2xl font-bold tracking-tight text-slate-950 sm:text-[28px]">
          数据总览
        </h1>
        <p class="mt-1 text-sm text-slate-500">
          科研数据采集、联系人解析与合规邮件活动的实时概况
        </p>
      </div>
      <RouterLink
        to="/analytics/ingestion"
        class="inline-flex min-h-11 items-center justify-center gap-2 self-start rounded-md bg-white px-3.5 text-sm font-medium text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset hover:bg-slate-50 sm:self-auto"
      >
        查看完整分析 <ArrowRightIcon class="size-4" />
      </RouterLink>
    </div>

    <div
      v-if="analyticsError"
      class="mt-5 rounded-lg bg-amber-50 p-3 text-sm text-amber-800 ring-1 ring-amber-200"
    >
      {{ analyticsError }}
    </div>

    <AnalyticsMetricGrid
      class="mt-6"
      :metrics="overview?.metrics ?? []"
      :loading="loading"
    />

    <section
      aria-label="任务和数据状态"
      class="mt-4 grid divide-y divide-slate-200 overflow-hidden rounded-lg bg-white shadow-xs ring-1 ring-slate-200 md:grid-cols-2 md:divide-x md:divide-y-0"
    >
      <div class="flex min-h-24 items-center justify-between gap-4 p-5">
        <div>
          <p class="text-xs font-medium text-slate-500">
            当前运行任务
          </p><p class="mt-2 text-xl font-semibold text-slate-900">
            {{ activeJobs }}
          </p><p class="mt-1 text-xs text-slate-400">
            待处理、排队、运行或暂停中的 arXiv 任务
          </p>
        </div>
        <span class="grid size-10 place-items-center rounded-md bg-slate-50 text-slate-400"><ServerStackIcon class="size-5" /></span>
      </div>
      <div class="flex min-h-24 items-center justify-between gap-4 p-5">
        <div>
          <p class="text-xs font-medium text-slate-500">
            数据新鲜度
          </p><p class="mt-2 text-sm font-semibold text-slate-900">
            {{ overview?.freshness.dataThrough ? new Date(overview.freshness.dataThrough).toLocaleString('zh-CN', { timeZone: 'UTC' }) : '暂无数据' }}
          </p><p class="mt-1 text-xs text-slate-400">
            UTC · papers.imported_at 队列
          </p>
        </div>
        <DsBadge
          :tone="overview ? 'positive' : 'neutral'"
          dot
        >
          {{ overview ? '已生成' : '无数据' }}
        </DsBadge>
      </div>
    </section>

    <div
      data-design-skill="bento-grid"
      class="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-6"
    >
      <AnalyticsChart
        class="lg:col-span-3"
        title="每日论文导入"
        description="最近 30 个 UTC 导入日；少于 8 个数据点使用柱状图。"
        :option="dailyOption(overview?.dailyImported ?? [])"
        :empty="(overview?.dailyImported.length ?? 0) === 0"
        :loading="loading"
        :error="analyticsError"
        filename="camel-arxiv-overview-daily"
      />
      <AnalyticsChart
        class="lg:col-span-2"
        title="Primary Category"
        description="队列中论文的主分类 Top 10。"
        :option="countBars(overview?.primaryCategories ?? [], true)"
        :empty="(overview?.primaryCategories.length ?? 0) === 0"
        :loading="loading"
        :error="analyticsError"
        filename="camel-arxiv-overview-category"
      />
      <AnalyticsChart
        class="lg:col-span-1"
        title="Source 漏斗"
        description="每篇论文只计最新解析。"
        :option="funnelBars(overview?.funnel ?? [])"
        :empty="!(overview?.funnel.some((item) => item.count > 0) ?? false)"
        :loading="loading"
        :error="analyticsError"
        filename="camel-arxiv-overview-funnel"
      />

      <DsCard class="min-h-64 lg:col-span-3">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">
              邮件运营
            </h2><p class="mt-1 text-xs text-slate-500">
              SMTP 已接受不代表最终送达
            </p>
          </div><DsBadge tone="neutral">
            发送阶段
          </DsBadge>
        </div>
        <div class="mt-8 rounded-md bg-slate-50 p-5 text-sm text-slate-500">
          邮件模板、审批、活动发送及追踪统计将在后续发送数据阶段启用；当前不会制造活动指标。
        </div>
      </DsCard>
      <DsCard class="min-h-64 lg:col-span-2">
        <h2 class="text-sm font-semibold text-slate-900">
          统计口径
        </h2>
        <dl class="mt-5 space-y-4 text-sm">
          <div>
            <dt class="text-xs text-slate-400">
              日期基准
            </dt><dd class="mt-1 font-medium text-slate-700">
              papers.imported_at · UTC
            </dd>
          </div><div>
            <dt class="text-xs text-slate-400">
              联系人隐私
            </dt><dd class="mt-1 font-medium text-slate-700">
              仅返回域名与聚合值
            </dd>
          </div><div>
            <dt class="text-xs text-slate-400">
              重跑去重
            </dt><dd class="mt-1 font-medium text-slate-700">
              论文 / 联系人的最新记录
            </dd>
          </div>
        </dl>
      </DsCard>
      <DsCard class="min-h-64 lg:col-span-1">
        <h2 class="text-sm font-semibold text-slate-900">
          系统健康状态
        </h2><p class="mt-1 text-xs text-slate-500">
          来自实时健康接口
        </p>
        <div
          v-if="loading"
          class="mt-7 space-y-3"
        >
          <DsSkeleton class="h-10 w-10" /><DsSkeleton class="h-5 w-24" />
        </div>
        <div
          v-else-if="healthError"
          class="mt-7"
        >
          <ExclamationTriangleIcon class="size-8 text-amber-500" /><p class="mt-3 text-sm font-semibold text-slate-900">
            健康检查不可用
          </p><DsButton
            class="mt-4"
            variant="secondary"
            size="sm"
            @click="load"
          >
            重新检查
          </DsButton>
        </div>
        <div
          v-else
          class="mt-7"
        >
          <component
            :is="healthIsUp ? CheckCircleIcon : ExclamationTriangleIcon"
            :class="[healthIsUp ? 'text-emerald-500' : 'text-amber-500', 'size-8']"
          /><p class="mt-3 text-sm font-semibold text-slate-900">
            {{ healthLabel }}
          </p><p class="mt-1 text-xs/5 text-slate-500">
            API 状态：{{ health?.status }}
          </p>
        </div>
      </DsCard>
    </div>
  </div>
</template>
