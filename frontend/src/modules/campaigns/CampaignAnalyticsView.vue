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
  unknown: sum.unknown + item.outcomeUnknown,
  bounced: sum.bounced + item.bounced,
  unsubscribed: sum.unsubscribed + item.unsubscribed,
  replied: sum.replied + item.replied,
  rawOpens: sum.rawOpens + item.rawOpens,
  humanOpens: sum.humanOpens + item.humanOpens,
  automatedOpens: sum.automatedOpens + item.automatedOpens,
  rawClicks: sum.rawClicks + item.rawClicks,
  humanClicks: sum.humanClicks + item.humanClicks,
  automatedClicks: sum.automatedClicks + item.automatedClicks,
}), {
  recipients: 0, accepted: 0, failures: 0, unknown: 0, bounced: 0, unsubscribed: 0, replied: 0,
  rawOpens: 0, humanOpens: 0, automatedOpens: 0, rawClicks: 0, humanClicks: 0, automatedClicks: 0,
}))

const chartOption = computed<EChartsCoreOption>(() => ({
  aria: { enabled: true },
  color: ['#4f6ef7', '#22c55e', '#ef4444', '#f59e0b'],
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 45, right: 20, top: 20, bottom: 60 },
  xAxis: { type: 'category', data: metrics.value.map((item) => item.name), axisLabel: { rotate: metrics.value.length > 4 ? 25 : 0 } },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: 'SMTP 接受', type: 'bar', data: metrics.value.map((item) => item.smtpAccepted) },
    { name: '回复', type: 'bar', data: metrics.value.map((item) => item.replied) },
    { name: '退信', type: 'bar', data: metrics.value.map((item) => item.bounced) },
    { name: '结果未知', type: 'bar', data: metrics.value.map((item) => item.outcomeUnknown) },
  ],
}))

const engagementOption = computed<EChartsCoreOption>(() => ({
  aria: { enabled: true },
  color: ['#6366f1', '#94a3b8', '#0ea5e9', '#cbd5e1'],
  tooltip: { trigger: 'axis' },
  legend: { bottom: 0 },
  grid: { left: 45, right: 20, top: 20, bottom: 60 },
  xAxis: { type: 'category', data: metrics.value.map((item) => item.name) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: '可能人工打开', type: 'bar', stack: 'open', data: metrics.value.map((item) => item.humanOpens) },
    { name: '自动化打开', type: 'bar', stack: 'open', data: metrics.value.map((item) => item.automatedOpens) },
    { name: '可能人工点击', type: 'bar', stack: 'click', data: metrics.value.map((item) => item.humanClicks) },
    { name: '自动化点击', type: 'bar', stack: 'click', data: metrics.value.map((item) => item.automatedClicks) },
  ],
}))

function percent(value: number): string {
  return `${(value * 100).toFixed(1)}%`
}

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
        只汇总正式活动；安全实流单独列示。SMTP 已接受不等于最终送达，回传不等于确认人工阅读。
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
      <div class="grid grid-cols-2 gap-4 xl:grid-cols-6">
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
            结果未知
          </p><p class="mt-1 text-2xl font-semibold text-rose-700">
            {{ totals.unknown }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            退信
          </p><p class="mt-1 text-2xl font-semibold text-indigo-700">
            {{ totals.bounced }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            回复
          </p><p class="mt-1 text-2xl font-semibold text-brand-700">
            {{ totals.replied }}
          </p>
        </DsCard>
        <DsCard padding="sm">
          <p class="text-xs text-slate-500">
            退订
          </p><p class="mt-1 text-2xl font-semibold text-amber-700">
            {{ totals.unsubscribed }}
          </p>
        </DsCard>
      </div>
      <div class="grid gap-6 xl:grid-cols-2">
        <AnalyticsChart
          title="投递结果与回复"
          description="退信、结果未知和回复来自正式投递或只读 IMAP 回传。"
          :option="chartOption"
          filename="campaign-delivery-outcomes"
        />
        <AnalyticsChart
          title="互动分类"
          description="可能人工只是分类结果；自动化流量不计入可能人工指标。"
          :option="engagementOption"
          filename="campaign-engagement"
        />
      </div>
      <DsCard padding="none">
        <div class="overflow-x-auto">
          <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
            <thead class="bg-slate-50 text-xs font-semibold uppercase tracking-wide text-slate-500">
              <tr>
                <th class="px-5 py-3">
                  活动
                </th><th class="px-5 py-3">
                  正式投递率
                </th><th class="px-5 py-3">
                  回复 / 退信 / 退订
                </th><th class="px-5 py-3">
                  打开：原始 / 可能人工 / 自动
                </th><th class="px-5 py-3">
                  点击：原始 / 可能人工 / 自动
                </th><th class="px-5 py-3">
                  安全实流（分账）
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-slate-100">
              <tr
                v-for="item in metrics"
                :key="item.id"
              >
                <td class="px-5 py-4">
                  <p class="font-semibold text-slate-900">
                    {{ item.name }}
                  </p><p class="mt-1 text-xs text-slate-500">
                    {{ item.status }} · {{ item.generationStatus }}
                  </p>
                </td>
                <td class="whitespace-nowrap px-5 py-4 text-slate-700">
                  {{ item.smtpAccepted }}/{{ item.recipients }} · {{ percent(item.rates.smtpAcceptance) }}
                </td>
                <td class="whitespace-nowrap px-5 py-4 text-slate-700">
                  {{ item.replied }} / {{ item.bounced }} / {{ item.unsubscribed }}
                </td>
                <td class="whitespace-nowrap px-5 py-4 text-slate-700">
                  {{ item.rawOpens }} / {{ item.humanOpens }} / {{ item.automatedOpens }}
                </td>
                <td class="whitespace-nowrap px-5 py-4 text-slate-700">
                  {{ item.rawClicks }} / {{ item.humanClicks }} / {{ item.automatedClicks }}
                </td>
                <td class="whitespace-nowrap px-5 py-4 text-slate-500">
                  {{ item.safety.runs }} 次 · {{ item.safety.smtpAccepted }} 接受 · {{ item.safety.replies }} 回复
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </DsCard>
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
