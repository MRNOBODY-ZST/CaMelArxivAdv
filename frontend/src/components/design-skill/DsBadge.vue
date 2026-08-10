<script setup lang="ts">
type BadgeTone = 'neutral' | 'positive' | 'warning' | 'danger' | 'info'
withDefaults(defineProps<{ tone?: BadgeTone; dot?: boolean }>(), { tone: 'neutral', dot: false })
</script>

<template>
  <span
    :class="[
      'inline-flex items-center gap-1.5 rounded-full px-2 py-1 text-xs font-medium ring-1 ring-inset',
      {
        'bg-slate-50 text-slate-700 ring-slate-600/20': tone === 'neutral',
        'bg-emerald-50 text-emerald-700 ring-emerald-600/20': tone === 'positive',
        'bg-amber-50 text-amber-700 ring-amber-600/20': tone === 'warning',
        'bg-red-50 text-red-700 ring-red-600/20': tone === 'danger',
        'bg-indigo-50 text-indigo-700 ring-indigo-600/20': tone === 'info',
      },
    ]"
  >
    <span
      v-if="dot"
      class="size-1.5 rounded-full bg-current"
      aria-hidden="true"
    />
    <slot />
  </span>
</template>
