<script setup lang="ts">
import { InformationCircleIcon } from '@heroicons/vue/24/outline'

import type { Metric } from '@/modules/analytics/analytics.types'

defineProps<{ metrics: Metric[]; loading?: boolean }>()

function format(metric: Metric): string {
  if (metric.unit === 'rate') return `${(metric.value * 100).toFixed(1)}%`
  if (metric.unit === 'milliseconds') return metric.value >= 1000 ? `${(metric.value / 1000).toFixed(2)} s` : `${Math.round(metric.value)} ms`
  if (metric.unit === 'average') return metric.value.toFixed(2)
  return new Intl.NumberFormat('zh-CN').format(metric.value)
}
</script>

<template>
  <section
    aria-label="核心指标"
    class="-mx-4 snap-x snap-mandatory overflow-x-auto px-4 pb-1 sm:mx-0 sm:px-0"
  >
    <div
      class="flex min-w-max divide-x divide-slate-200 overflow-hidden rounded-lg bg-white shadow-xs ring-1 ring-slate-200 sm:grid sm:min-w-0"
      :style="{ gridTemplateColumns: `repeat(${Math.max(metrics.length, 1)}, minmax(0, 1fr))` }"
    >
      <template v-if="loading">
        <div
          v-for="index in 3"
          :key="index"
          class="w-48 animate-pulse p-5 sm:w-auto"
        >
          <div class="h-3 w-20 rounded bg-slate-100" /><div class="mt-3 h-8 w-24 rounded bg-slate-100" />
        </div>
      </template>
      <article
        v-for="metric in metrics"
        v-else
        :key="metric.key"
        class="w-48 snap-start p-5 sm:w-auto"
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
        <p class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">
          {{ format(metric) }}
        </p>
        <p
          v-if="metric.unit === 'rate'"
          class="mt-1 text-[11px] text-slate-400"
        >
          {{ metric.numerator }} / {{ metric.denominator }}
        </p>
        <p
          v-else
          class="mt-1 truncate text-[11px] text-slate-400"
        >
          UTC 导入日期队列
        </p>
      </article>
      <div
        v-if="!loading && metrics.length === 0"
        class="w-full p-5 text-sm text-slate-500"
      >
        当前范围暂无指标
      </div>
    </div>
  </section>
</template>
