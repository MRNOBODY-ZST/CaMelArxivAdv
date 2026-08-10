<script setup lang="ts">
withDefaults(defineProps<{ rounded?: 'sm' | 'md' | 'full' }>(), { rounded: 'md' })
</script>

<template>
  <span
    aria-hidden="true"
    :class="[
      'block animate-pulse bg-slate-200',
      rounded === 'full' ? 'rounded-full' : rounded === 'sm' ? 'rounded-sm' : 'rounded-md',
    ]"
  />
</template>
