<script setup lang="ts">
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import type { EChartsCoreOption } from 'echarts/core'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AnalyticsChart from '@/modules/analytics/AnalyticsChart.vue'
import AnalyticsFilterBar from '@/modules/analytics/AnalyticsFilterBar.vue'
import AnalyticsMetricGrid from '@/modules/analytics/AnalyticsMetricGrid.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'
import {
  countBars,
  dailyOption,
  dailySeriesOption,
  donutOption,
  durationBars,
  funnelOption,
  rateBars,
  treemapOption,
} from '@/modules/analytics/chartOptions'
import type {
  AnalyticsQuery,
  ContactsResponse,
  FilterOptionsResponse,
  IngestionResponse,
  NamedCount,
  PapersResponse,
} from '@/modules/analytics/analytics.types'
import { useAnalyticsFilters } from '@/modules/analytics/useAnalyticsFilters'

type ViewName = 'ingestion' | 'papers' | 'contacts'
type Payload = IngestionResponse | PapersResponse | ContactsResponse
interface ChartDefinition {
  key: string
  title: string
  description: string
  option: EChartsCoreOption
  size: number
  height: number
  wide: boolean
}

const props = defineProps<{ view: ViewName }>()
const route = useRoute()
const { filter, query, reset, sync } = useAnalyticsFilters()
const payload = ref<Payload | null>(null)
const loadedView = ref<ViewName | null>(null)
const loadedQuery = ref<AnalyticsQuery | null>(null)
const options = ref<FilterOptionsResponse | null>(null)
const loading = ref(true)
const exporting = ref(false)
const error = ref<string | null>(null)
let requestSerial = 0

const page = computed(() => ({
  ingestion: { title: '采集分析', subtitle: '从 arXiv 查询匹配到 Source 解析与邮箱发现的完整采集漏斗' },
  papers: { title: '论文分析', subtitle: '论文分类、时间、作者、版本和 Source 格式的队列结构' },
  contacts: { title: '联系人分析', subtitle: '联系人发现质量、域名、规则与协作关系；域名后缀不代表机构归属' },
}[props.view]))

const currentPayload = computed(() => loadedView.value === props.view ? payload.value : null)
const freshness = computed(() => {
  const dataThrough = currentPayload.value?.freshness.dataThrough
  return dataThrough
    ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(dataThrough))
    : '暂无数据'
})

const charts = computed<ChartDefinition[]>(() => {
  if (!currentPayload.value) return error.value ? errorCharts(props.view) : []
  if (props.view === 'ingestion') {
    const data = currentPayload.value as IngestionResponse
    const duration: NamedCount[] = [
      { key: 'average', label: '平均', count: data.duration.averageMs },
      { key: 'p50', label: 'P50', count: data.duration.p50Ms },
      { key: 'p90', label: 'P90', count: data.duration.p90Ms },
      { key: 'p95', label: 'P95', count: data.duration.p95Ms },
      { key: 'p99', label: 'P99', count: data.duration.p99Ms },
    ]
    return [
      chart('daily', '每日导入吞吐', '按 papers.imported_at 的 UTC 日期计数；少于 8 个点时使用柱状图。', dailyOption(data.dailyImported), data.dailyImported.length, 330, true),
      chart('funnel', 'Source 采集漏斗', '漏斗宽度代表每一级的论文数量；每篇论文只使用最新一次 extraction run。', funnelOption(data.funnel), data.funnel.some((item) => item.count > 0) ? data.funnel.length : 0, 360, true),
      chart('statuses', '最新解析状态', '同一论文只计最新 extraction run。', donutOption(data.extractionStatuses), data.extractionStatuses.length),
      chart('duration', '解析耗时分位数', `样本 ${data.duration.samples} 个，单位为毫秒。`, durationBars(duration), data.duration.samples),
      chart('errors', 'Worker 错误码', '按任务错误码聚合；不展示可能含敏感内容的错误摘要。', countBars(data.workerErrors, true), data.workerErrors.length),
      chart('throughput', '每日任务处理量', '按 UTC 日期与 arXiv 任务最终状态汇总 processed_count。', dailySeriesOption(data.jobThroughput), data.jobThroughput.length),
    ]
  }
  if (props.view === 'papers') {
    const data = currentPayload.value as PapersResponse
    return [
      chart('categories', 'Primary Category 分布', '按论文主分类统计，默认展示前 20 项。', countBars(data.categories, true), data.categories.length, 360, true),
      chart('allCategories', '全部 Category 构成', '矩形面积代表论文数，包含 Primary 与 Cross-list 关系。', treemapOption(data.allCategories), data.allCategories.length, 390, true),
      chart('crossListCategories', 'Cross-list Category 分布', '仅统计 Cross-list 关系。', countBars(data.crossListCategories, true), data.crossListCategories.length, 360),
      chart('groups', 'arXiv Group 分布', '由官方分类树映射；未分类论文单独列出。', donutOption(data.groups), data.groups.length),
      chart('relations', 'Primary / Cross-list 覆盖', '一篇论文可同时出现在多个关系类型中。', donutOption(data.categoryRelations), data.categoryRelations.length),
      chart('published', '发表月份', '显示 arXiv submitted_at，但论文仍受导入日期队列筛选。', dailyOption(data.publicationMonths), data.publicationMonths.length),
      chart('updated', '更新月份', '显示 arXiv updated_at，但论文仍受导入日期队列筛选。', dailyOption(data.updateMonths), data.updateMonths.length),
      chart('authors', '每篇论文作者数', '作者数按区间分桶。', countBars(data.authorCounts), data.authorCounts.length),
      chart('versions', '论文版本数', '以 papers.version_count 分桶。', countBars(data.versionCounts), data.versionCounts.length),
      chart('formats', 'Source 格式', '优先使用最新 extraction run 的格式，否则使用论文已知格式。', countBars(data.sourceFormats, true), data.sourceFormats.length),
    ]
  }
  const data = currentPayload.value as ContactsResponse
  return [
    chart('domains', '邮箱域名 Top 20', '仅展示域名聚合，不返回或导出完整邮箱地址。', countBars(data.domains, true), data.domains.length, 380),
    chart('confidence', '置信度分布', '同一论文与联系人的多次提取只保留最新映射。', donutOption(data.confidence), data.confidence.length),
    chart('domainClass', '常见服务商 / 其他域名', '平台规则推导，仅用于域名分类，不推断机构归属。', donutOption(data.inferredDomainClasses), data.inferredDomainClasses.length),
    chart('discovery', '分类邮箱发现率', '分子为有联系人论文数，分母为该 Primary Category 论文数。', rateBars(data.categoryDiscovery), data.categoryDiscovery.length, 380, true),
    chart('documents', '文档类联系人发现率', '分子为找到联系人的论文，分母为该最新文档类的解析论文。', rateBars(data.documentClasses), data.documentClasses.length),
    chart('rules', '提取规则命中', '按脱敏 extraction evidence 的 rule_name 聚合。', countBars(data.extractionRules, true), data.extractionRules.length),
    chart('reuse', '邮箱跨论文复用', '按同一加密联系人关联的不同论文数分桶。', countBars(data.reuseBuckets), data.reuseBuckets.length),
    chart('coauthors', '高频共同作者对', '按共同论文数排序的关系预览；完整交互网络请使用“作者关系”页。', countBars(data.coauthorPairs, true), data.coauthorPairs.length, 380),
  ]
})

function chart(
  key: string,
  title: string,
  description: string,
  option: EChartsCoreOption,
  size: number,
  height = 300,
  wide = false,
): ChartDefinition {
  return { key, title, description, option, size, height, wide }
}

function errorCharts(view: ViewName): ChartDefinition[] {
  const titles = view === 'ingestion'
    ? ['每日导入吞吐', 'Source 采集漏斗', '最新解析状态', '解析耗时分位数', 'Worker 错误码', '每日任务处理量']
    : view === 'papers'
      ? ['Primary Category 分布', '全部 Category 分布', 'Cross-list Category 分布', 'arXiv Group 分布', 'Primary / Cross-list 覆盖', '发表月份', '更新月份', '每篇论文作者数', '论文版本数', 'Source 格式']
      : ['邮箱域名 Top 20', '置信度分布', '常见服务商 / 其他域名', '分类邮箱发现率', '文档类联系人发现率', '提取规则命中', '邮箱跨论文复用', '高频共同作者对']
  return titles.map((title, index) => chart(`error-${index}`, title, '', {}, 0))
}

function updateFilter(key: keyof AnalyticsQuery, value: string | undefined): void {
  Object.assign(filter, { [key]: value })
}

async function load(): Promise<void> {
  const serial = ++requestSerial
  const requestedView = props.view
  const requestedQuery = { ...query.value }
  loading.value = true
  error.value = null
  payload.value = null
  loadedView.value = null
  loadedQuery.value = null
  try {
    const loader = analyticsApi[requestedView]
    const [result, available] = await Promise.all([loader(requestedQuery), analyticsApi.filters(requestedQuery)])
    if (serial !== requestSerial || requestedView !== props.view) return
    payload.value = result
    loadedView.value = requestedView
    loadedQuery.value = requestedQuery
    options.value = available
  } catch {
    if (serial !== requestSerial) return
    payload.value = null
    loadedView.value = null
    loadedQuery.value = null
    error.value = '统计数据加载失败，请检查筛选条件或稍后重试。'
  } finally {
    if (serial === requestSerial) loading.value = false
  }
}

async function apply(): Promise<void> {
  if (!await sync()) await load()
}

async function clear(): Promise<void> {
  if (!await reset()) await load()
}

async function exportCsv(): Promise<void> {
  if (!currentPayload.value || !loadedQuery.value) return
  exporting.value = true
  try {
    await analyticsApi.export(props.view, loadedQuery.value)
  } catch {
    error.value = 'CSV 导出失败，请稍后重试。'
  } finally {
    exporting.value = false
  }
}

onMounted(load)
watch(() => [props.view, route.fullPath], load)
</script>

<template>
  <div class="mx-auto max-w-[1600px] space-y-5">
    <header class="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
      <div>
        <p class="text-sm text-slate-500">
          数据分析 / {{ page.title }}
        </p>
        <h1 class="mt-1 text-2xl font-bold tracking-tight text-slate-950 sm:text-[28px]">
          {{ page.title }}
        </h1>
        <p class="mt-1 max-w-3xl text-sm text-slate-500">
          {{ page.subtitle }}
        </p>
      </div>
      <p class="shrink-0 text-xs text-slate-500">
        <span class="font-medium text-slate-700">数据截至</span> {{ freshness }} UTC
      </p>
    </header>

    <AnalyticsFilterBar
      :filter="filter"
      :options="options"
      :loading="loading"
      :exporting="exporting"
      @update="updateFilter"
      @apply="apply"
      @reset="clear"
      @export="exportCsv"
    />

    <div
      v-if="error"
      class="flex items-start gap-3 rounded-lg bg-rose-50 p-4 text-sm text-rose-700 ring-1 ring-rose-200"
    >
      <ExclamationTriangleIcon class="mt-0.5 size-5 shrink-0" /><p>{{ error }}</p>
    </div>

    <AnalyticsMetricGrid
      :metrics="currentPayload?.metrics ?? []"
      :loading="loading"
    />

    <div class="grid gap-4 lg:grid-cols-2">
      <div
        v-for="item in charts"
        :key="item.key"
        :data-chart-key="item.key"
        :class="item.wide ? 'lg:col-span-2' : ''"
      >
        <AnalyticsChart
          :title="item.title"
          :description="item.description"
          :option="item.option"
          :empty="item.size === 0"
          :loading="loading"
          :error="error"
          :height="item.height"
          :filename="`camel-arxiv-${view}-${item.key}`"
        />
      </div>
      <template v-if="loading">
        <AnalyticsChart
          v-for="index in 4"
          :key="index"
          title="加载中"
          :option="{}"
          loading
        />
      </template>
    </div>

    <p class="pb-2 text-xs/5 text-slate-400">
      统计口径：UTC 的 papers.imported_at 日期队列；比率为 0 时代表分母为 0 或分子为 0，不显示 NaN/∞。邮件活动与 SMTP 账户筛选将在发送数据阶段启用。
    </p>
  </div>
</template>
