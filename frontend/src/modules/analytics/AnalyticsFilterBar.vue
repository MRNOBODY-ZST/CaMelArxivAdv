<script setup lang="ts">
import { ArrowDownTrayIcon, ArrowPathIcon, FunnelIcon } from '@heroicons/vue/24/outline'

import type { AnalyticsQuery, FilterOptionsResponse } from '@/modules/analytics/analytics.types'

defineProps<{
  filter: AnalyticsQuery
  options: FilterOptionsResponse | null
  loading?: boolean
  exporting?: boolean
}>()

const emit = defineEmits<{
  apply: []
  reset: []
  export: []
  update: [key: keyof AnalyticsQuery, value: string | undefined]
}>()

function value(event: { target: unknown }): string | undefined {
  const current = (event.target as { value: string }).value
  return current || undefined
}
</script>

<template>
  <section
    class="rounded-lg bg-white p-4 shadow-xs ring-1 ring-slate-200"
    aria-label="分析筛选条件"
  >
    <div class="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
      <label class="text-xs font-medium text-slate-600">
        导入开始日期（UTC）
        <input
          type="date"
          :value="filter.from"
          class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm text-slate-900 ring-1 ring-slate-300 ring-inset focus:ring-2 focus:ring-brand-500"
          @input="emit('update', 'from', value($event))"
        >
      </label>
      <label class="text-xs font-medium text-slate-600">
        导入结束日期（UTC）
        <input
          type="date"
          :value="filter.to"
          class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm text-slate-900 ring-1 ring-slate-300 ring-inset focus:ring-2 focus:ring-brand-500"
          @input="emit('update', 'to', value($event))"
        >
      </label>
      <label class="text-xs font-medium text-slate-600">
        arXiv 分类
        <select
          :value="filter.categoryId ?? ''"
          class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm text-slate-900 ring-1 ring-slate-300 ring-inset focus:ring-2 focus:ring-brand-500"
          @change="emit('update', 'categoryId', value($event))"
        >
          <option value="">全部分类</option>
          <option
            v-for="item in options?.categories ?? []"
            :key="item.id"
            :value="item.id"
          >{{ item.label }}</option>
        </select>
      </label>
      <label class="text-xs font-medium text-slate-600">
        分类关系
        <select
          :value="filter.relation ?? 'ALL'"
          class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm text-slate-900 ring-1 ring-slate-300 ring-inset focus:ring-2 focus:ring-brand-500"
          @change="emit('update', 'relation', value($event))"
        >
          <option
            v-for="item in options?.relationTypes ?? [{ id: 'ALL', label: 'Primary + Cross-list' }]"
            :key="item.id"
            :value="item.id"
          >{{ item.label }}</option>
        </select>
      </label>
    </div>

    <details class="mt-3 border-t border-slate-100 pt-3">
      <summary class="flex min-h-9 cursor-pointer list-none items-center gap-2 text-xs font-semibold text-slate-600">
        <FunnelIcon class="size-4 text-slate-400" />更多筛选
      </summary>
      <div class="mt-3 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <label class="text-xs font-medium text-slate-600">
          任务
          <select
            :value="filter.jobId ?? ''"
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm ring-1 ring-slate-300 ring-inset"
            @change="emit('update', 'jobId', value($event))"
          >
            <option value="">全部任务</option>
            <option
              v-for="item in options?.jobs ?? []"
              :key="item.id"
              :value="item.id"
            >{{ item.label }}</option>
          </select>
        </label>
        <label class="text-xs font-medium text-slate-600">
          操作用户
          <select
            :value="filter.userId ?? ''"
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm ring-1 ring-slate-300 ring-inset"
            @change="emit('update', 'userId', value($event))"
          >
            <option value="">全部用户</option>
            <option
              v-for="item in options?.users ?? []"
              :key="item.id"
              :value="item.id"
            >{{ item.label }}</option>
          </select>
        </label>
        <label class="text-xs font-medium text-slate-600">
          邮箱域名
          <select
            :value="filter.domain ?? ''"
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm ring-1 ring-slate-300 ring-inset"
            @change="emit('update', 'domain', value($event))"
          >
            <option value="">全部域名</option>
            <option
              v-for="item in options?.domains ?? []"
              :key="item.id"
              :value="item.id"
            >{{ item.label }}</option>
          </select>
        </label>
        <label class="text-xs font-medium text-slate-600">
          联系人置信度
          <select
            :value="filter.confidence ?? ''"
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-white px-3 text-sm ring-1 ring-slate-300 ring-inset"
            @change="emit('update', 'confidence', value($event))"
          >
            <option value="">全部置信度</option>
            <option
              v-for="item in options?.confidenceLevels ?? []"
              :key="item.id"
              :value="item.id"
            >{{ item.label }}</option>
          </select>
        </label>
        <label class="text-xs font-medium text-slate-400">
          邮件活动
          <select
            disabled
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-slate-50 px-3 text-sm ring-1 ring-slate-200 ring-inset"
          ><option>发送阶段启用</option></select>
        </label>
        <label class="text-xs font-medium text-slate-400">
          SMTP 账户
          <select
            disabled
            class="mt-1.5 block min-h-10 w-full rounded-md border-0 bg-slate-50 px-3 text-sm ring-1 ring-slate-200 ring-inset"
          ><option>发送阶段启用</option></select>
        </label>
      </div>
    </details>

    <div class="mt-4 flex flex-wrap justify-end gap-2">
      <button
        type="button"
        class="inline-flex min-h-10 items-center gap-2 rounded-md px-3 text-sm font-medium text-slate-600 hover:bg-slate-50"
        @click="emit('reset')"
      >
        <ArrowPathIcon class="size-4" />重置
      </button>
      <button
        type="button"
        :disabled="exporting || loading"
        class="inline-flex min-h-10 items-center gap-2 rounded-md bg-white px-3 text-sm font-medium text-slate-700 ring-1 ring-slate-300 ring-inset hover:bg-slate-50 disabled:opacity-50"
        @click="emit('export')"
      >
        <ArrowDownTrayIcon class="size-4" />{{ exporting ? '导出中…' : '导出 CSV' }}
      </button>
      <button
        type="button"
        :disabled="loading"
        class="inline-flex min-h-10 items-center gap-2 rounded-md bg-brand-600 px-4 text-sm font-semibold text-white hover:bg-brand-700 disabled:opacity-50"
        @click="emit('apply')"
      >
        <FunnelIcon class="size-4" />{{ loading ? '查询中…' : '应用筛选' }}
      </button>
    </div>
  </section>
</template>
