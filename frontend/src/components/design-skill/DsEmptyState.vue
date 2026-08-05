<script setup lang="ts">
import { InboxIcon } from '@heroicons/vue/24/outline'

defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="flex min-h-40 flex-col items-center justify-center px-4 py-8 text-center">
    <slot name="icon">
      <InboxIcon
        class="size-8 text-slate-400"
        aria-hidden="true"
      />
    </slot>
    <h3 class="mt-3 text-sm font-semibold text-slate-900">
      {{ title }}
    </h3>
    <p
      v-if="description"
      class="mt-1 max-w-sm text-xs/5 text-slate-500"
    >
      {{ description }}
    </p>
    <div
      v-if="$slots.actions"
      class="mt-4"
    >
      <slot name="actions" />
    </div>
  </div>
</template>
