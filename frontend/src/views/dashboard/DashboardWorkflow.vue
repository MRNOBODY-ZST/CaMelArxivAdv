<script setup lang="ts">
import {
  ArrowRightIcon,
  CheckIcon,
  DocumentMagnifyingGlassIcon,
  MagnifyingGlassIcon,
  PaperAirplaneIcon,
  UserGroupIcon,
} from '@heroicons/vue/24/outline'
import { computed, type Component } from 'vue'
import { RouterLink } from 'vue-router'

import type { DashboardWorkflowStage } from '@/views/dashboard/dashboard.model'

const props = defineProps<{ stages: DashboardWorkflowStage[] }>()

const icons: Record<DashboardWorkflowStage['key'], Component> = {
  discover: MagnifyingGlassIcon,
  parse: DocumentMagnifyingGlassIcon,
  contacts: UserGroupIcon,
  outreach: PaperAirplaneIcon,
}

const visibleStages = computed(() => props.stages.map((stage, index) => ({ ...stage, index: index + 1, icon: icons[stage.key] })))
</script>

<template>
  <section
    aria-label="工作流程"
    class="overflow-hidden rounded-xl border border-slate-200 bg-white"
  >
    <div class="border-b border-slate-200 px-5 py-4 sm:px-6">
      <h2 class="text-base font-semibold text-slate-950">
        研究触达流程
      </h2>
      <p class="mt-1 text-sm text-slate-500">
        按顺序完成每个阶段，下一步会根据实时数据自动调整。
      </p>
    </div>
    <ol class="grid grid-cols-1 divide-y divide-slate-200 md:grid-cols-4 md:divide-x md:divide-y-0">
      <li
        v-for="stage in visibleStages"
        :key="stage.key"
        class="relative min-w-0 p-5"
      >
        <div class="flex items-center gap-3">
          <span
            :class="[
              stage.tone === 'complete' ? 'bg-brand-500 text-white' : stage.tone === 'attention' ? 'bg-amber-500 text-white' : 'bg-slate-100 text-slate-500',
              'grid size-8 shrink-0 place-items-center rounded-full text-xs font-semibold',
            ]"
          >
            <CheckIcon
              v-if="stage.tone === 'complete'"
              class="size-4"
              aria-hidden="true"
            />
            <span v-else>{{ stage.index }}</span>
          </span>
          <component
            :is="stage.icon"
            class="size-5 shrink-0 text-slate-400"
            aria-hidden="true"
          />
          <h3 class="truncate text-sm font-semibold text-slate-900">
            {{ stage.title }}
          </h3>
        </div>
        <p :class="[stage.tone === 'attention' ? 'text-amber-700' : 'text-slate-700', 'mt-4 text-sm font-medium']">
          {{ stage.valueLabel }}
        </p>
        <p class="mt-1 text-xs/5 text-slate-500">
          {{ stage.description }}
        </p>
        <RouterLink
          :to="stage.href"
          class="mt-4 inline-flex min-h-10 items-center text-xs font-semibold text-brand-600 hover:text-brand-700"
        >
          {{ stage.actionLabel }}<ArrowRightIcon
            class="ml-1 size-3.5"
            aria-hidden="true"
          />
        </RouterLink>
      </li>
    </ol>
  </section>
</template>
