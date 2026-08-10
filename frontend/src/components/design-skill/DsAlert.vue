<script setup lang="ts">
import { CheckCircleIcon, ExclamationTriangleIcon, InformationCircleIcon, XCircleIcon } from '@heroicons/vue/20/solid'
import { computed } from 'vue'

type AlertTone = 'info' | 'success' | 'warning' | 'danger'
const props = withDefaults(defineProps<{ tone?: AlertTone; title?: string }>(), { tone: 'info' })
const icon = computed(() => ({
  info: InformationCircleIcon,
  success: CheckCircleIcon,
  warning: ExclamationTriangleIcon,
  danger: XCircleIcon,
})[props.tone])
</script>

<template>
  <div
    role="alert"
    :class="[
      'flex gap-3 rounded-md p-4 text-sm ring-1 ring-inset',
      {
        'bg-blue-50 text-blue-800 ring-blue-200': tone === 'info',
        'bg-emerald-50 text-emerald-800 ring-emerald-200': tone === 'success',
        'bg-amber-50 text-amber-800 ring-amber-200': tone === 'warning',
        'bg-red-50 text-red-800 ring-red-200': tone === 'danger',
      },
    ]"
  >
    <component
      :is="icon"
      class="mt-0.5 size-5 shrink-0"
      aria-hidden="true"
    />
    <div>
      <p
        v-if="title"
        class="font-semibold"
      >
        {{ title }}
      </p>
      <div :class="title ? 'mt-1' : ''">
        <slot />
      </div>
    </div>
  </div>
</template>
