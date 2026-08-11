<script setup lang="ts">
import { CheckCircleIcon, ClockIcon, ExclamationTriangleIcon, QueueListIcon } from '@heroicons/vue/24/outline'
import { computed } from 'vue'

const props = defineProps<{
  activeJobs: number
  dataThrough: string | null
  healthStatus: string | null
  healthError: boolean
}>()

const freshnessLabel = computed(() => {
  if (!props.dataThrough) return '暂无数据'
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    hour12: false, timeZone: 'UTC',
  }).format(new Date(props.dataThrough))
})

const healthLabel = computed(() => {
  if (props.healthError) return '健康检查不可用'
  if (props.healthStatus === 'UP') return '运行正常'
  if (props.healthStatus) return `状态：${props.healthStatus}`
  return '检查中'
})
</script>

<template>
  <aside
    aria-label="需要关注"
    class="overflow-hidden rounded-xl border border-slate-200 bg-white xl:self-start"
  >
    <div class="border-b border-slate-200 px-5 py-4">
      <h2 class="text-base font-semibold text-slate-950">
        需要关注
      </h2>
      <p class="mt-1 text-xs text-slate-500">
        只显示会影响下一步的运行信号。
      </p>
    </div>
    <dl class="divide-y divide-slate-200">
      <div class="flex items-center gap-4 p-5">
        <span :class="[activeJobs > 0 ? 'bg-amber-50 text-amber-600' : 'bg-slate-50 text-slate-500', 'grid size-10 shrink-0 place-items-center rounded-lg']">
          <QueueListIcon
            class="size-5"
            aria-hidden="true"
          />
        </span>
        <div class="min-w-0">
          <dt class="text-xs font-medium text-slate-500">
            运行任务
          </dt>
          <dd class="mt-1 text-xl font-semibold text-slate-950">
            {{ activeJobs }}
          </dd>
        </div>
      </div>
      <div class="flex items-center gap-4 p-5">
        <span class="grid size-10 shrink-0 place-items-center rounded-lg bg-slate-50 text-slate-500">
          <ClockIcon
            class="size-5"
            aria-hidden="true"
          />
        </span>
        <div class="min-w-0">
          <dt class="text-xs font-medium text-slate-500">
            数据最新
          </dt>
          <dd class="mt-1 truncate text-sm font-semibold text-slate-950">
            {{ freshnessLabel }}
          </dd>
          <p class="mt-1 text-[11px] text-slate-400">
            UTC
          </p>
        </div>
      </div>
      <div
        class="flex items-center gap-4 p-5"
        aria-label="系统状态"
      >
        <span :class="[healthError || (healthStatus && healthStatus !== 'UP') ? 'bg-amber-50 text-amber-600' : 'bg-emerald-50 text-emerald-600', 'grid size-10 shrink-0 place-items-center rounded-lg']">
          <ExclamationTriangleIcon
            v-if="healthError || (healthStatus && healthStatus !== 'UP')"
            class="size-5"
            aria-hidden="true"
          />
          <CheckCircleIcon
            v-else
            class="size-5"
            aria-hidden="true"
          />
        </span>
        <div class="min-w-0">
          <dt class="text-xs font-medium text-slate-500">
            系统状态
          </dt>
          <dd class="mt-1 text-sm font-semibold text-slate-950">
            {{ healthLabel }}
          </dd>
        </div>
      </div>
    </dl>
  </aside>
</template>
