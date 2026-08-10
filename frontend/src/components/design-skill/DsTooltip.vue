<script setup lang="ts">
defineProps<{ text: string }>()
</script>

<template>
  <span class="group relative inline-flex">
    <slot />
    <span
      role="tooltip"
      class="pointer-events-none absolute bottom-full left-1/2 z-50 mb-2 hidden w-max max-w-64 -translate-x-1/2 rounded bg-slate-900 px-2 py-1 text-xs text-white shadow-sm group-hover:block group-focus-within:block"
    >{{ text }}</span>
  </span>
</template>
