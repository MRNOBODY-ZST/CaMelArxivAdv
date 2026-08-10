<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { ChartBarIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import DsAlert from '@/components/design-skill/DsAlert.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import AnalyticsChart from '@/modules/analytics/AnalyticsChart.vue'
import { campaignErrorMessage, campaignsApi } from '@/modules/campaigns/campaigns.api'
import type { CampaignAnalyticsView as CampaignMetric } from '@/modules/campaigns/campaigns.types'

const loading = ref(true)
const error = ref('')
const metrics = ref<CampaignMetric[]>([])
const page = ref(1)
const totalPages = ref(0)

const totals = computed(() => metrics.value.reduce((sum, item) => ({
  recipients: sum.recipients + item.recipients,
  accepted: sum.accepted + item.smtpAccepted,
  failures: sum.failures + item.permanentFailures,
  humanClicks: sum.humanClicks + item.humanClicks,
}), { recipients: 0, accepted: 0, failures: 0, humanClicks: 0 }))

const chartOption = computed<EChartsCoreOption>(() => ({
  aria: { enabled: true },
  color: ['#4f6ef7', '#22c55e', '#8b5cf6'],
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 45, right: 20, top: 20, bottom: 60 },
  xAxis: { type: 'category', data: metrics.value.map((item) => item.name), axisLabel: { rotate: metrics.value.length > 4 ? 25 : 0 } },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: 'SMTP 接受', type: 'bar', data: metrics.value.map((item) => item.smtpAccepted) },
    { name: '真人打开', type: 'bar', data: metrics.value.map((item) => item.humanOpens) },
    { name: '真人点击', type: 'bar', data: metrics.value.map((item) => item.humanClicks) },
  ],
}))

async function load(target = page.value): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const result = await campaignsApi.listCampaignAnalytics(target)
    metrics.value = result.items
    page.value = result.page
    totalPages.value = result.totalPages
  } catch (cause) {
    error.value = campaignErrorMessage(cause, '活动指标加载失败。')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section
    data-testid="campaign-analytics-view"
    aria-labelledby="campaign-analytics-title"
    class="space-y-6"
  >
    <header>
      <h1
        id="campaign-analytics-title"
        class="text-2xl font-semibold tracking-tight text-slate-950"
      >
        活动分析
      </h1><p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
        汇总真实活动收件人、SMTP 状态和经过分类的互动事件。
      </p>
    </header>
    <DsAlert
      v-if="error"
      tone="danger"
    >
      {{ error }}
    </DsAlert>
    <div
      v-if="loading"
      class="h-52 animate-pulse rounded-lg bg-slate-100"
      aria-label="活动指标加载中"
    />
    <template v-else-if="metrics.length">
      <div class="grid grid-cols-2 gap-4 xl:grid-cols-4">
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            收件人
          </p><p class="mt-1 text-2xl font-semibold text-slate-950">
            {{ totals.recipients }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            SMTP 接受
          </p><p class="mt-1 text-2xl font-semibold text-emerald-700">
            {{ totals.accepted }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            永久失败
          </p><p class="mt-1 text-2xl font-semibold text-rose-700">
            {{ totals.failures }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            真人点击
          </p><p class="mt-1 text-2xl font-semibold text-indigo-700">
            {{ totals.humanClicks }}
          </p>
        </DsCard>
      </div>
      <AnalyticsChart
        title="各活动互动概况"
        description="自动化打开与点击不计入真人指标。"
        :option="chartOption"
        filename="campaign-analytics"
      />
      <DsPagination
        v-if="totalPages > 1"
        :page="page"
        :total-pages="totalPages"
        @change="load"
      />
    </template>
    <DsCard
      v-else
      padding="none"
    >
      <DsEmptyState
        title="暂无活动指标"
        description="产生真实活动数据后才会显示图表。"
      >
        <template #icon>
          <ChartBarIcon class="size-9 text-slate-400" />
        </template>
      </DsEmptyState>
    </DsCard>
  </section>
</template>
