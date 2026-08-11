<script setup lang="ts">
import { InformationCircleIcon } from '@heroicons/vue/24/outline'

import type { Metric } from '@/modules/analytics/analytics.types'

defineProps<{ metrics: Metric[]; loading?: boolean }>()

function format(metric: Metric): string {
  if (metric.unit === 'rate') return `${(metric.value * 100).toFixed(1)}%`
  if (metric.unit === 'milliseconds') return metric.value >= 1_000 ? `${(metric.value / 1_000).toFixed(2)} s` : `${Math.round(metric.value)} ms`
  if (metric.unit === 'average') return metric.value.toFixed(2)
  return new Intl.NumberFormat('zh-CN').format(metric.value)
}
</script>

<template>
  <section aria-label="核心指标">
    <div class="grid grid-cols-2 overflow-hidden rounded-lg border border-slate-200 bg-white sm:grid-cols-3">
      <template v-if="loading">
        <div
          v-for="index in 3"
          :key="index"
          class="animate-pulse border-r border-slate-200 p-4 last:border-r-0 sm:p-5"
        >
          <div class="h-3 w-20 rounded bg-slate-100" />
          <div class="mt-3 h-7 w-24 rounded bg-slate-100" />
        </div>
      </template>
      <article
        v-for="(metric, index) in metrics"
        v-else
        :key="metric.key"
        :class="[
          index % 2 === 0 ? 'border-r' : '',
          index < 2 ? 'border-b sm:border-b-0' : '',
          index < metrics.length - 1 ? 'sm:border-r' : '',
          'border-slate-200 p-4 sm:p-5',
        ]"
      >
        <div class="flex items-center gap-1.5">
          <p class="truncate text-xs font-medium text-slate-500">
            {{ metric.label }}
          </p>
          <InformationCircleIcon
            class="size-4 shrink-0 text-slate-300"
            :title="metric.definition"
          />
        </div>
        <p class="mt-2 text-2xl font-semibold tracking-tight text-slate-950">
          {{ format(metric) }}
        </p>
        <p class="mt-1 text-[11px] text-slate-400">
          {{ metric.unit === 'rate' ? `${metric.numerator} / ${metric.denominator}` : '当前分析窗口' }}
        </p>
      </article>
      <div
        v-if="!loading && metrics.length === 0"
        class="col-span-2 p-5 text-sm text-slate-500 sm:col-span-3"
      >
        当前范围暂无指标
      </div>
    </div>
  </section>
</template>
