<script setup lang="ts">
import { ArrowPathIcon, ArrowRightIcon, UserGroupIcon } from '@heroicons/vue/24/outline'
import { RouterLink } from 'vue-router'

import type { DashboardAction } from '@/views/dashboard/dashboard.model'

defineProps<{ action: DashboardAction }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <section
    aria-label="下一步行动"
    :class="[
      action.tone === 'warning'
        ? 'border-amber-200 bg-amber-50/70'
        : 'border-brand-100 bg-white',
      'relative overflow-hidden rounded-xl border p-5 shadow-xs sm:p-6',
    ]"
  >
    <div class="relative z-10 max-w-3xl">
      <p :class="[action.tone === 'warning' ? 'text-amber-700' : 'text-brand-600', 'text-xs font-semibold tracking-wide']">
        {{ action.eyebrow }}
      </p>
      <h2 class="mt-2 text-xl font-semibold tracking-tight text-slate-950 sm:text-2xl">
        {{ action.title }}
      </h2>
      <p class="mt-2 max-w-2xl text-sm/6 text-slate-600">
        {{ action.description }}
      </p>
      <div class="mt-5">
        <button
          v-if="action.kind === 'retry'"
          type="button"
          class="inline-flex min-h-11 items-center justify-center gap-2 rounded-md bg-amber-600 px-4 text-sm font-semibold text-white shadow-xs hover:bg-amber-700"
          @click="$emit('retry')"
        >
          <ArrowPathIcon
            class="size-4"
            aria-hidden="true"
          />{{ action.ctaLabel }}
        </button>
        <RouterLink
          v-else-if="action.href"
          :to="action.href"
          class="inline-flex min-h-11 items-center justify-center gap-2 rounded-md bg-brand-500 px-4 text-sm font-semibold text-white shadow-xs hover:bg-brand-600"
        >
          {{ action.ctaLabel }}<ArrowRightIcon
            class="size-4"
            aria-hidden="true"
          />
        </RouterLink>
      </div>
    </div>
    <div
      class="pointer-events-none absolute top-1/2 right-6 hidden size-28 -translate-y-1/2 place-items-center rounded-full bg-brand-50 text-brand-500 sm:grid"
      aria-hidden="true"
    >
      <UserGroupIcon class="size-12" />
    </div>
  </section>
</template>
