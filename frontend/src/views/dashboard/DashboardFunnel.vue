<script setup lang="ts">
import type { DashboardFunnelRow } from '@/views/dashboard/dashboard.model'

defineProps<{ rows: DashboardFunnelRow[]; loading?: boolean; error?: string | null }>()
</script>

<template>
  <section
    class="rounded-xl border border-slate-200 bg-white p-5 sm:p-6"
    aria-label="来源处理情况"
  >
    <div>
      <h2 class="text-base font-semibold text-slate-950">
        来源处理情况
      </h2>
      <p class="mt-1 text-xs text-slate-500">
        各来源文献的处理进度，不累计重复阶段。
      </p>
    </div>
    <div
      v-if="loading"
      class="mt-6 space-y-4"
      aria-label="正在加载来源处理情况"
    >
      <div
        v-for="index in 6"
        :key="index"
        class="animate-pulse"
      >
        <div class="h-3 w-20 rounded bg-slate-100" /><div
          class="mt-2 h-2 rounded bg-slate-100"
          :style="{ width: `${100 - index * 8}%` }"
        />
      </div>
    </div>
    <div
      v-else-if="error"
      class="mt-6 rounded-lg bg-amber-50 p-4 text-sm text-amber-800"
    >
      {{ error }}
    </div>
    <ul
      v-else-if="rows.length"
      role="list"
      class="mt-6 space-y-3.5"
    >
      <li
        v-for="row in rows"
        :key="row.key"
        data-testid="funnel-row"
        :aria-label="`${row.label}：${row.count}`"
      >
        <div class="flex items-center justify-between gap-4 text-xs">
          <span class="truncate font-medium text-slate-600">{{ row.label }}</span>
          <span class="shrink-0 tabular-nums text-slate-900">{{ row.count }}</span>
        </div>
        <div class="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100">
          <div
            data-testid="funnel-bar"
            class="h-full rounded-full bg-brand-500"
            :style="{ width: `${row.widthPercent}%` }"
          />
        </div>
      </li>
    </ul>
    <p
      v-else
      class="mt-6 text-sm text-slate-500"
    >
      当前范围暂无来源处理数据
    </p>
  </section>
</template>
