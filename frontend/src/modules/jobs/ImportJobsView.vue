<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowPathIcon, ClockIcon } from '@heroicons/vue/24/outline'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import DsEmptyState from '@/components/design-skill/DsEmptyState.vue'
import DsPagination from '@/components/design-skill/DsPagination.vue'
import { jobsApi } from './jobs.api'
import type { JobView } from './jobs.types'

const jobs = ref<JobView[]>([]); const page = ref(1); const totalPages = ref(0); const loading = ref(true); const error = ref('')
onMounted(load)
async function load(next = page.value): Promise<void> {
  loading.value = true; error.value = ''
  try { const data = await jobsApi.list(next); jobs.value = data.items; page.value = data.page; totalPages.value = data.totalPages }
  catch { error.value = '任务列表加载失败' } finally { loading.value = false }
}
const statusClass = (status: string) => status === 'SUCCEEDED' ? 'bg-emerald-50 text-emerald-700' : status === 'FAILED' ? 'bg-red-50 text-red-700' : status === 'RUNNING' ? 'bg-blue-50 text-blue-700' : 'bg-slate-100 text-slate-700'
</script>

<template>
  <div class="space-y-6">
    <header class="flex items-end justify-between">
      <div>
        <p class="text-sm font-medium text-brand-600">
          可恢复采集
        </p><h1 class="mt-1 text-2xl font-semibold text-slate-950">
          导入任务
        </h1><p class="mt-2 text-sm text-slate-500">
          查看官方接口采集进度、Worker 心跳和失败明细。
        </p>
      </div><DsButton
        variant="secondary"
        :busy="loading"
        @click="load()"
      >
        <ArrowPathIcon class="size-4" />刷新
      </DsButton>
    </header>
    <p
      v-if="error"
      role="alert"
      class="rounded-md bg-red-50 p-3 text-sm text-red-700"
    >
      {{ error }}
    </p>
    <DsCard
      v-if="jobs.length"
      padding="none"
    >
      <ul class="divide-y divide-slate-200">
        <li
          v-for="job in jobs"
          :key="job.id"
          class="p-5 sm:p-6"
        >
          <RouterLink
            :to="`/jobs/${job.id}`"
            class="block rounded focus:outline-none"
          >
            <div class="flex flex-col gap-3 sm:flex-row sm:items-center">
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-center gap-2">
                  <p class="truncate font-semibold text-slate-900">
                    {{ job.type.replace('ARXIV_', '').replaceAll('_', ' ') }}
                  </p><span :class="['rounded-full px-2.5 py-1 text-xs font-semibold', statusClass(job.status)]">{{ job.status }}</span><span
                    v-if="job.workerStale && ['RUNNING','QUEUED'].includes(job.status)"
                    class="text-xs text-amber-600"
                  >Worker 心跳超时</span>
                </div><p class="mt-1 font-mono text-xs text-slate-400">
                  {{ job.id }}
                </p>
              </div><p class="text-sm font-semibold text-slate-700">
                {{ job.progressPercent.toFixed(1) }}%
              </p>
            </div><div class="mt-4 h-2 overflow-hidden rounded-full bg-slate-100">
              <div
                class="h-full rounded-full bg-brand-500"
                :style="{ width: `${job.progressPercent}%` }"
              />
            </div><div class="mt-3 flex flex-wrap gap-x-5 gap-y-1 text-xs text-slate-500">
              <span>{{ job.currentStage }}</span><span>已处理 {{ job.processedCount }} / {{ job.totalCount || '未知' }}</span><span>成功 {{ job.successCount }}</span><span
                v-if="job.failedCount"
                class="text-red-600"
              >失败 {{ job.failedCount }}</span><span class="ml-auto inline-flex items-center gap-1"><ClockIcon class="size-4" />{{ new Date(job.updatedAt).toLocaleString('zh-CN') }}</span>
            </div>
          </RouterLink>
        </li>
      </ul>
      <div class="px-5 pb-5">
        <DsPagination
          v-if="totalPages > 1"
          :page="page"
          :total-pages="totalPages"
          @change="load"
        />
      </div>
    </DsCard>
    <DsEmptyState
      v-else-if="!loading"
      title="暂无导入任务"
      description="从论文发现页创建第一个选中导入或条件导入任务。"
    >
      <ClockIcon class="size-8" />
    </DsEmptyState>
  </div>
</template>
