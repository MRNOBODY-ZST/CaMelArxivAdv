<script setup lang="ts">
import { ChevronLeftIcon, ChevronRightIcon } from '@heroicons/vue/20/solid'

const props = defineProps<{ page: number; totalPages: number }>()
const emit = defineEmits<{ change: [page: number] }>()
const go = (page: number) => {
  if (page >= 1 && page <= props.totalPages) emit('change', page)
}
</script>

<template>
  <nav
    aria-label="分页"
    class="flex items-center justify-between gap-3 border-t border-slate-200 pt-4"
  >
    <button
      type="button"
      class="inline-flex min-h-11 items-center gap-1 rounded-md px-3 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
      :disabled="page <= 1"
      @click="go(page - 1)"
    >
      <ChevronLeftIcon
        class="size-4"
        aria-hidden="true"
      />上一页
    </button>
    <span class="text-sm text-slate-500">第 {{ page }} / {{ totalPages }} 页</span>
    <button
      type="button"
      class="inline-flex min-h-11 items-center gap-1 rounded-md px-3 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-40"
      :disabled="page >= totalPages"
      @click="go(page + 1)"
    >
      下一页<ChevronRightIcon
        class="size-4"
        aria-hidden="true"
      />
    </button>
  </nav>
</template>
