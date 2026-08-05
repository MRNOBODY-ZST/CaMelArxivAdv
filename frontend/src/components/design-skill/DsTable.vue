<script setup lang="ts">
defineProps<{ label: string }>()
</script>

<template>
  <div class="overflow-x-auto">
    <table class="min-w-full divide-y divide-slate-200 text-left text-sm">
      <caption class="sr-only">
        {{ label }}
      </caption>
      <slot />
    </table>
  </div>
</template>
