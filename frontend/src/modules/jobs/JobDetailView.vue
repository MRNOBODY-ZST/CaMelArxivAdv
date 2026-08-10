<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import DsButton from '@/components/design-skill/DsButton.vue'
import DsCard from '@/components/design-skill/DsCard.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { jobsApi } from './jobs.api'
import type { JobAction, JobEvent, JobView } from './jobs.types'
import { useJobProgress } from './useJobProgress'

const route = useRoute(); const auth = useAuthStore(); const id = computed(() => String(route.params.id || '') || undefined)
const job = ref<JobView>(); const events = ref<JobEvent[]>([]); const error = ref(''); const acting = ref(false)
onMounted(async () => { await refresh(); events.value = await jobsApi.events(id.value ?? '') })
useJobProgress(id, (value) => { job.value = value; void refreshEvents() })
async function refresh(): Promise<void> { if (!id.value) return; try { job.value = await jobsApi.get(id.value) } catch { error.value = '任务详情加载失败' } }
async function refreshEvents(): Promise<void> { if (!id.value) return; const after = events.value.at(-1)?.id ?? 0; const next = await jobsApi.events(id.value, after); if (next.length) events.value.push(...next) }
async function control(action: JobAction): Promise<void> { if (!id.value) return; acting.value = true; error.value = ''; try { job.value = await jobsApi.control(id.value, action); await refreshEvents() } catch { error.value = '任务操作失败，请刷新后重试' } finally { acting.value = false } }
const actionLabel: Record<JobAction, string> = { PAUSE: '暂停', RESUME: '继续', CANCEL: '取消', RETRY: '重试' }
</script>

<template>
  <div
    v-if="job"
    class="space-y-6"
  >
    <header class="flex flex-col gap-4 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <RouterLink
          to="/jobs"
          class="text-sm font-medium text-brand-600"
        >
          ← 返回任务
        </RouterLink><h1 class="mt-2 text-2xl font-semibold text-slate-950">
          {{ job.type }}
        </h1><p class="mt-1 font-mono text-xs text-slate-400">
          {{ job.id }}
        </p>
      </div><div
        v-if="auth.hasPermission('job:manage')"
        class="flex flex-wrap gap-2"
      >
        <DsButton
          v-for="action in job.allowedActions"
          :key="action"
          :variant="action === 'CANCEL' ? 'danger' : 'secondary'"
          :busy="acting"
          @click="control(action)"
        >
          {{ actionLabel[action] }}
        </DsButton>
      </div>
    </header>
    <p
      v-if="error"
      role="alert"
      class="rounded-md bg-red-50 p-3 text-sm text-red-700"
    >
      {{ error }}
    </p>
    <div class="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
      <DsCard>
        <p class="text-xs font-medium uppercase text-slate-400">
          状态
        </p><p class="mt-2 text-xl font-semibold text-slate-900">
          {{ job.status }}
        </p>
      </DsCard><DsCard>
        <p class="text-xs font-medium uppercase text-slate-400">
          进度
        </p><p class="mt-2 text-xl font-semibold text-slate-900">
          {{ job.progressPercent.toFixed(1) }}%
        </p>
      </DsCard><DsCard>
        <p class="text-xs font-medium uppercase text-slate-400">
          成功 / 失败
        </p><p class="mt-2 text-xl font-semibold text-slate-900">
          {{ job.successCount }} / <span class="text-red-600">{{ job.failedCount }}</span>
        </p>
      </DsCard><DsCard>
        <p class="text-xs font-medium uppercase text-slate-400">
          Worker
        </p><p
          class="mt-2 text-xl font-semibold"
          :class="job.workerStale ? 'text-amber-600' : 'text-emerald-600'"
        >
          {{ job.workerStale ? '心跳超时' : '在线' }}
        </p>
      </DsCard>
    </div>
    <DsCard>
      <div class="flex justify-between text-sm">
        <span class="font-medium text-slate-700">{{ job.currentStage }}</span><span class="text-slate-500">{{ job.processedCount }} / {{ job.totalCount || '未知' }}</span>
      </div><div class="mt-3 h-3 overflow-hidden rounded-full bg-slate-100">
        <div
          class="h-full bg-brand-500"
          :style="{ width: `${job.progressPercent}%` }"
        />
      </div><p
        v-if="job.errorSummary"
        class="mt-3 text-sm text-red-600"
      >
        {{ job.errorSummary }}
      </p>
    </DsCard>
    <DsCard>
      <h2 class="font-semibold text-slate-900">
        事件时间线
      </h2><ol class="mt-5 space-y-5">
        <li
          v-for="event in events"
          :key="event.id"
          class="relative border-l border-slate-200 pl-5"
        >
          <span class="absolute -left-1.5 top-1 size-3 rounded-full border-2 border-white bg-brand-500" /><div class="flex flex-col sm:flex-row sm:justify-between">
            <p class="text-sm font-semibold text-slate-800">
              {{ event.message }}
            </p><time class="text-xs text-slate-400">{{ new Date(event.occurredAt).toLocaleString('zh-CN') }}</time>
          </div><p class="mt-1 text-xs text-slate-500">
            {{ event.stage }} · {{ event.eventType }}
          </p>
        </li>
      </ol>
    </DsCard>
  </div>
</template>
