<script setup lang="ts">
import { BarChart, FunnelChart, LineChart, PieChart, TreemapChart } from 'echarts/charts'
import {
  AriaComponent,
  GridComponent,
  LegendComponent,
  TooltipComponent,
} from 'echarts/components'
import { init, use, type EChartsCoreOption, type EChartsType } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { ArrowDownTrayIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

use([
  BarChart, FunnelChart, LineChart, PieChart, TreemapChart, AriaComponent, GridComponent,
  LegendComponent, TooltipComponent, CanvasRenderer,
])

const props = withDefaults(defineProps<{
  option: EChartsCoreOption
  title: string
  description?: string
  loading?: boolean
  empty?: boolean
  error?: string | null
  height?: number
  filename?: string
}>(), { description: '', loading: false, empty: false, error: null, height: 300, filename: 'chart' })

const chartElement = ref<InstanceType<typeof globalThis.HTMLElement> | null>(null)
let chart: EChartsType | null = null
let observer: InstanceType<typeof globalThis.ResizeObserver> | null = null
let reducedMotionQuery: ReturnType<typeof globalThis.matchMedia> | null = null

function render(): void {
  if (!chart || props.loading || props.empty || props.error) return
  const reducedMotion = reducedMotionQuery?.matches
    ?? globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    ?? false
  chart.setOption({ ...props.option, animation: !reducedMotion }, { notMerge: true })
}

function downloadPng(): void {
  if (!chart) return
  const anchor = globalThis.document.createElement('a')
  anchor.href = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  anchor.download = `${props.filename}.png`
  anchor.click()
}

onMounted(async () => {
  await nextTick()
  if (!chartElement.value) return
  chart = init(chartElement.value, undefined, { renderer: 'canvas' })
  reducedMotionQuery = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)') ?? null
  reducedMotionQuery?.addEventListener('change', render)
  observer = new globalThis.ResizeObserver(() => chart?.resize())
  observer.observe(chartElement.value)
  render()
})

watch(() => [props.option, props.loading, props.empty, props.error], render, { deep: true })

onBeforeUnmount(() => {
  reducedMotionQuery?.removeEventListener('change', render)
  observer?.disconnect()
  chart?.dispose()
})
</script>

<template>
  <section class="rounded-lg bg-white p-5 shadow-xs ring-1 ring-slate-200">
    <div class="flex items-start justify-between gap-3">
      <div>
        <h2 class="text-sm font-semibold text-slate-900">
          {{ title }}
        </h2>
        <p
          v-if="description"
          class="mt-1 text-xs/5 text-slate-500"
        >
          {{ description }}
        </p>
      </div>
      <button
        v-if="!loading && !empty && !error"
        type="button"
        class="inline-flex min-h-9 shrink-0 items-center gap-1.5 rounded-md px-2.5 text-xs font-medium text-slate-600 ring-1 ring-slate-200 ring-inset hover:bg-slate-50"
        :aria-label="`下载 ${title} PNG`"
        @click="downloadPng"
      >
        <ArrowDownTrayIcon class="size-4" />PNG
      </button>
    </div>
    <div
      v-if="loading"
      class="mt-4 animate-pulse rounded-md bg-slate-100"
      :style="{ height: `${height}px` }"
      aria-label="图表加载中"
    />
    <div
      v-else-if="error"
      class="mt-4 grid place-items-center rounded-md bg-rose-50 px-6 text-center text-sm text-rose-700"
      :style="{ height: `${height}px` }"
    >
      <div>
        <ExclamationTriangleIcon class="mx-auto size-7" /><p class="mt-2">
          {{ error }}
        </p>
      </div>
    </div>
    <div
      v-else-if="empty"
      class="mt-4 grid place-items-center rounded-md bg-slate-50 px-6 text-center text-sm text-slate-500"
      :style="{ height: `${height}px` }"
    >
      当前筛选范围暂无数据
    </div>
    <div
      v-show="!loading && !empty && !error"
      ref="chartElement"
      class="mt-4 w-full"
      :style="{ height: `${height}px` }"
      role="img"
      :aria-label="`${title}。${description}`"
    />
  </section>
</template>
