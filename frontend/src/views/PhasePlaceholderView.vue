<script setup lang="ts">
defineProps<{ title: string; description: string }>()
</script>

<template>
  <div class="rounded-xl bg-white p-8 shadow-sm ring-1 ring-slate-200">
    <p class="text-xs font-semibold uppercase tracking-wider text-brand-600">
      系统管理
    </p>
    <h1 class="mt-2 text-2xl font-semibold text-slate-950">
      {{ title }}
    </h1>
    <p class="mt-3 max-w-2xl text-sm/6 text-slate-500">
      {{ description }}
    </p>
  </div>
</template>
