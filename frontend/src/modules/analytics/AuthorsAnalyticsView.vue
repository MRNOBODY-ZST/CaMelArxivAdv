<script setup lang="ts">
import {
  ExclamationTriangleIcon,
  LinkIcon,
  UserGroupIcon,
} from '@heroicons/vue/24/outline'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import AnalyticsFilterBar from '@/modules/analytics/AnalyticsFilterBar.vue'
import AuthorNetworkGraph from '@/modules/analytics/AuthorNetworkGraph.vue'
import { analyticsApi } from '@/modules/analytics/analytics.api'
import type {
  AnalyticsQuery,
  AuthorsResponse,
  FilterOptionsResponse,
} from '@/modules/analytics/analytics.types'
import { useAnalyticsFilters } from '@/modules/analytics/useAnalyticsFilters'

const route = useRoute()
const { filter, query, reset, sync } = useAnalyticsFilters()
const payload = ref<AuthorsResponse | null>(null)
const options = ref<FilterOptionsResponse | null>(null)
const loading = ref(true)
const error = ref<string | null>(null)
let requestSerial = 0

const freshness = computed(() => {
  const dataThrough = payload.value?.freshness.dataThrough
  return dataThrough
    ? new Intl.DateTimeFormat('zh-CN', {
        dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC',
      }).format(new Date(dataThrough))
    : '暂无数据'
})

const summaries = computed(() => [
  {
    key: 'authors', label: '筛选作者', value: payload.value?.summary.totalAuthors ?? 0,
    hint: '当前论文范围内的唯一作者', tone: 'bg-indigo-50 text-indigo-600', icon: UserGroupIcon,
  },
  {
    key: 'edges', label: '协作关系', value: payload.value?.summary.totalCollaborations ?? 0,
    hint: '至少共同发表一篇论文', tone: 'bg-cyan-50 text-cyan-600', icon: LinkIcon,
  },
  {
    key: 'papers', label: '论文范围', value: payload.value?.summary.totalPapers ?? 0,
    hint: '参与本次图谱计算的论文', tone: 'bg-amber-50 text-amber-600', icon: UserGroupIcon,
  },
  {
    key: 'visible', label: '可见图谱', value: payload.value?.nodes.length ?? 0,
    hint: payload.value ? `${payload.value.edges.length} 条可见连线` : '等待图谱数据',
    tone: 'bg-emerald-50 text-emerald-600', icon: LinkIcon,
  },
])

function updateFilter(key: keyof AnalyticsQuery, value: string | undefined): void {
  Object.assign(filter, { [key]: value })
}

async function load(): Promise<void> {
  const serial = ++requestSerial
  const requestedQuery = { ...query.value }
  loading.value = true
  error.value = null
  payload.value = null
  try {
    const [result, available] = await Promise.all([
      analyticsApi.authors(requestedQuery),
      analyticsApi.filters(requestedQuery),
    ])
    if (serial !== requestSerial) return
    payload.value = result
    options.value = available
  } catch {
    if (serial !== requestSerial) return
    payload.value = null
    error.value = '作者关系加载失败，请检查筛选条件或稍后重试。'
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

onMounted(load)
watch(() => route.fullPath, load)
</script>

<template>
  <div class="mx-auto max-w-[1600px] space-y-5">
    <header class="flex flex-col justify-between gap-3 sm:flex-row sm:items-end">
      <div>
        <p class="text-sm text-slate-500">
          数据分析 / 作者关系
        </p>
        <h1 class="mt-1 text-2xl font-bold tracking-tight text-slate-950 sm:text-[28px]">
          作者关系
        </h1>
        <p class="mt-1 max-w-3xl text-sm text-slate-500">
          基于已导入论文的共同作者关系；使用物理引擎计算节点位置，支持拖拽、缩放与聚焦。
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
      :exportable="false"
      @update="updateFilter"
      @apply="apply"
      @reset="clear"
    />

    <div
      v-if="error"
      class="flex items-start gap-3 rounded-lg bg-rose-50 p-4 text-sm text-rose-700 ring-1 ring-rose-200"
    >
      <ExclamationTriangleIcon class="mt-0.5 size-5 shrink-0" />
      <p>{{ error }}</p>
    </div>

    <section
      aria-label="作者关系概览"
      class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4"
    >
      <article
        v-for="item in summaries"
        :key="item.key"
        class="relative overflow-hidden rounded-xl bg-white p-5 shadow-xs ring-1 ring-slate-200"
      >
        <div class="flex items-start justify-between gap-3">
          <div>
            <p class="text-xs font-medium text-slate-500">
              {{ item.label }}
            </p>
            <p
              v-if="loading"
              class="mt-3 h-8 w-20 animate-pulse rounded bg-slate-100"
            />
            <p
              v-else
              class="mt-2 text-3xl font-semibold tracking-tight text-slate-950"
            >
              {{ item.value.toLocaleString('zh-CN') }}
            </p>
            <p class="mt-2 text-[11px]/4 text-slate-500">
              {{ item.hint }}
            </p>
          </div>
          <span :class="['grid size-9 shrink-0 place-items-center rounded-lg', item.tone]">
            <component
              :is="item.icon"
              class="size-4"
            />
          </span>
        </div>
      </article>
    </section>

    <div
      v-if="payload?.summary.truncated"
      class="rounded-lg bg-amber-50 px-4 py-3 text-xs/5 text-amber-800 ring-1 ring-amber-200"
    >
      当前关系规模较大：画布展示排名最高的 {{ payload.nodes.length }} 位作者和 {{ payload.edges.length }} 条关系。使用日期或分类筛选可查看更聚焦的完整网络。
    </div>

    <AuthorNetworkGraph
      :nodes="payload?.nodes ?? []"
      :edges="payload?.edges ?? []"
      :loading="loading"
      :error="error"
    />

    <p class="pb-2 text-xs/5 text-slate-400">
      关系口径：两位作者在当前筛选论文中共同署名即形成一条边；节点身份使用平台规范化作者记录，不推断机构或同名身份合并。
    </p>
  </div>
</template>
