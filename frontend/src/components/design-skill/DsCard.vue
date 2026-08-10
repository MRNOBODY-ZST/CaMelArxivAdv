<script setup lang="ts">
withDefaults(defineProps<{ padding?: 'none' | 'sm' | 'md' }>(), { padding: 'md' })
</script>

<template>
  <section
    :class="[
      'overflow-hidden rounded-lg bg-white shadow-xs ring-1 ring-slate-200',
      padding === 'sm' ? 'p-4' : padding === 'md' ? 'p-5 sm:p-6' : '',
    ]"
  >
    <slot />
  </section>
</template>
