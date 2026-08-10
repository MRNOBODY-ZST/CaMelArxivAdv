<script setup lang="ts">
import { ChevronRightIcon, HomeIcon } from '@heroicons/vue/20/solid'

export interface BreadcrumbItem { label: string; href?: string }
defineProps<{ items: readonly BreadcrumbItem[] }>()
</script>

<template>
  <nav aria-label="面包屑">
    <ol class="flex items-center gap-2 text-sm text-slate-500">
      <li>
        <HomeIcon
          class="size-4"
          aria-hidden="true"
        /><span class="sr-only">首页</span>
      </li>
      <li
        v-for="item in items"
        :key="item.label"
        class="flex items-center gap-2"
      >
        <ChevronRightIcon
          class="size-4 text-slate-400"
          aria-hidden="true"
        />
        <a
          v-if="item.href"
          :href="item.href"
          class="hover:text-slate-700"
        >{{ item.label }}</a>
        <span
          v-else
          aria-current="page"
          class="font-medium text-slate-700"
        >{{ item.label }}</span>
      </li>
    </ol>
  </nav>
</template>
