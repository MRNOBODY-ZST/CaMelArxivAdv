<script setup lang="ts">
import { CalendarDaysIcon, CheckCircleIcon, ExclamationTriangleIcon, ServerStackIcon } from '@heroicons/vue/24/outline'
import { computed, onMounted, ref } from 'vue'

import { getSystemHealth, type SystemHealth } from '@/api/system'
import DsBadge from '@/components/design-skill/DsBadge.vue'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsSkeleton from '@/components/design-skill/DsSkeleton.vue'

const metricLabels = ['已收录论文', '已解析论文', '唯一作者', '唯一邮箱', '高置信度邮箱', '邮箱发现率'] as const
const health = ref<SystemHealth | null>(null)
const healthError = ref(false)
const healthLoading = ref(true)

const healthIsUp = computed(() => health.value?.status === 'UP')
const healthLabel = computed(() => healthIsUp.value ? '运行正常' : health.value ? '状态异常' : '检查中')

async function loadHealth(): Promise<void> {
  healthLoading.value = true
  healthError.value = false
  try {
    health.value = await getSystemHealth()
  } catch {
    health.value = null
    healthError.value = true
  } finally {
    healthLoading.value = false
  }
}

onMounted(loadHealth)
</script>

<template>
  <div class="mx-auto max-w-[1600px]">
    <div class="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
      <div>
        <p class="text-sm text-slate-500">
          概览 / 数据总览
        </p><h1 class="mt-1 text-2xl font-bold tracking-tight text-slate-950 sm:text-[28px]">
          数据总览
        </h1><p class="mt-1 text-sm text-slate-500">
          科研数据采集、联系人解析与合规邮件活动的实时概况
        </p>
      </div>
      <button
        type="button"
        class="inline-flex min-h-11 items-center justify-center gap-2 self-start rounded-md bg-white px-3.5 text-sm font-medium text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset hover:bg-slate-50 sm:self-auto"
      >
        <CalendarDaysIcon class="size-5 text-slate-400" />最近 7 天
      </button>
    </div>

    <section
      aria-label="核心指标"
      class="-mx-4 mt-6 snap-x snap-mandatory overflow-x-auto px-4 pb-1 sm:mx-0 sm:px-0"
    >
      <div class="grid min-w-[900px] grid-cols-6 divide-x divide-slate-200 overflow-hidden rounded-lg bg-white shadow-xs ring-1 ring-slate-200 sm:min-w-0">
        <div
          v-for="label in metricLabels"
          :key="label"
          class="min-w-36 snap-start p-4 sm:min-w-0 sm:p-5"
        >
          <p class="text-xs font-medium text-slate-500">
            {{ label }}
          </p><p class="mt-2 text-2xl font-semibold tracking-tight text-slate-900">
            —
          </p><p class="mt-1 text-[11px] text-slate-400">
            尚无统计数据
          </p>
        </div>
      </div>
    </section>

    <section
      aria-label="任务和同步状态"
      class="mt-4 grid divide-y divide-slate-200 overflow-hidden rounded-lg bg-white shadow-xs ring-1 ring-slate-200 md:grid-cols-2 md:divide-x md:divide-y-0"
    >
      <div class="flex min-h-24 items-center justify-between gap-4 p-5">
        <div>
          <p class="text-xs font-medium text-slate-500">
            当前运行任务
          </p><p class="mt-2 text-xl font-semibold text-slate-900">
            —
          </p><p class="mt-1 text-xs text-slate-400">
            暂无运行中的异步任务
          </p>
        </div><span class="grid size-10 place-items-center rounded-md bg-slate-50 text-slate-400"><ServerStackIcon class="size-5" /></span>
      </div>
      <div class="flex min-h-24 items-center justify-between gap-4 p-5">
        <div>
          <p class="text-xs font-medium text-slate-500">
            最近一次同步
          </p><p class="mt-2 text-xl font-semibold text-slate-900">
            —
          </p><p class="mt-1 text-xs text-slate-400">
            尚未同步 arXiv 分类数据
          </p>
        </div><DsBadge
          tone="neutral"
          dot
        >
          未同步
        </DsBadge>
      </div>
    </section>

    <div
      data-design-skill="bento-grid"
      class="mt-4 grid grid-cols-1 gap-4 lg:grid-cols-6"
    >
      <DsCard class="min-h-72 lg:col-span-3">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">
              最近七天论文趋势
            </h2><p class="mt-1 text-xs text-slate-500">
              按导入日期统计
            </p>
          </div>
        </div><DsEmptyState
          class="mt-4"
          title="尚无统计数据"
          description="导入论文后将在此显示每日变化趋势。"
        />
      </DsCard>
      <DsCard class="min-h-72 lg:col-span-2">
        <h2 class="text-sm font-semibold text-slate-900">
          分类分布
        </h2><p class="mt-1 text-xs text-slate-500">
          按 Primary Category 统计
        </p><DsEmptyState
          class="mt-4"
          title="尚无分类数据"
          description="同步并导入 arXiv 论文后显示。"
        />
      </DsCard>
      <DsCard class="min-h-72 lg:col-span-1">
        <h2 class="text-sm font-semibold text-slate-900">
          Source 解析漏斗
        </h2><p class="mt-1 text-xs text-slate-500">
          下载到联系人提取
        </p><DsEmptyState
          class="mt-4"
          title="暂无数据"
        />
      </DsCard>

      <DsCard class="min-h-64 lg:col-span-3">
        <div class="flex items-center justify-between">
          <div>
            <h2 class="text-sm font-semibold text-slate-900">
              最近邮件活动
            </h2><p class="mt-1 text-xs text-slate-500">
              SMTP 已接受不代表最终送达
            </p>
          </div><DsBadge tone="neutral">
            暂无活动
          </DsBadge>
        </div><DsEmptyState
          class="mt-3"
          title="尚未创建邮件活动"
          description="活动经过审核且使用 Mailpit 验证后，发送结果会显示在这里。"
        />
      </DsCard>
      <DsCard class="min-h-64 lg:col-span-2">
        <h2 class="text-sm font-semibold text-slate-900">
          邮件互动指标
        </h2><p class="mt-1 text-xs text-slate-500">
          打开事件为估算值
        </p><dl class="mt-6 space-y-5">
          <div
            v-for="label in ['SMTP 接受率', '估算打开率', '点击率']"
            :key="label"
            class="flex items-end justify-between border-b border-slate-100 pb-3"
          >
            <dt class="text-sm text-slate-600">
              {{ label }}
            </dt><dd class="text-xl font-semibold text-slate-900">
              —
            </dd>
          </div>
        </dl>
      </DsCard>
      <DsCard class="min-h-64 lg:col-span-1">
        <h2 class="text-sm font-semibold text-slate-900">
          系统健康状态
        </h2><p class="mt-1 text-xs text-slate-500">
          来自实时健康接口
        </p>
        <div
          v-if="healthLoading"
          class="mt-7 space-y-3"
        >
          <DsSkeleton class="h-10 w-10" /><DsSkeleton class="h-5 w-24" /><DsSkeleton class="h-3 w-full" />
        </div>
        <div
          v-else-if="healthError"
          class="mt-7"
        >
          <ExclamationTriangleIcon class="size-8 text-amber-500" /><p class="mt-3 text-sm font-semibold text-slate-900">
            健康检查不可用
          </p><p class="mt-1 text-xs/5 text-slate-500">
            请确认后端服务已启动。
          </p><DsButton
            class="mt-4"
            variant="secondary"
            size="sm"
            @click="loadHealth"
          >
            重新检查
          </DsButton>
        </div>
        <div
          v-else
          class="mt-7"
        >
          <component
            :is="healthIsUp ? CheckCircleIcon : ExclamationTriangleIcon"
            :class="[healthIsUp ? 'text-emerald-500' : 'text-amber-500', 'size-8']"
          /><p class="mt-3 text-sm font-semibold text-slate-900">
            {{ healthLabel }}
          </p><p class="mt-1 text-xs/5 text-slate-500">
            API 状态：{{ health?.status }}
          </p><DsBadge
            class="mt-4"
            :tone="healthIsUp ? 'positive' : 'warning'"
            dot
          >
            {{ health?.status }}
          </DsBadge>
        </div>
      </DsCard>
    </div>
  </div>
</template>
