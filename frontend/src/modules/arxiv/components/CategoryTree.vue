<script setup lang="ts">
import { Disclosure, DisclosureButton, DisclosurePanel } from '@headlessui/vue'
import { ChevronRightIcon } from '@heroicons/vue/20/solid'
import type { TaxonomyGroup } from '../arxiv.types'

defineProps<{ groups: TaxonomyGroup[]; modelValue: string[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

function toggle(current: string[], category: string, checked: boolean): void {
  emit('update:modelValue', checked
    ? [...new Set([...current, category])].sort()
    : current.filter((item) => item !== category))
}
</script>

<template>
  <div class="max-h-80 space-y-1 overflow-y-auto rounded-md border border-slate-200 bg-slate-50/60 p-2">
    <Disclosure
      v-for="group in groups"
      :key="group.groupId"
      v-slot="{ open }"
      :default-open="false"
    >
      <DisclosureButton class="flex min-h-10 w-full items-center gap-2 rounded px-2 text-left text-sm font-semibold text-slate-800 hover:bg-white">
        <ChevronRightIcon :class="['size-4 text-slate-400 transition-transform', open && 'rotate-90']" />
        {{ group.groupName }}
        <span class="ml-auto text-xs font-normal text-slate-400">{{ group.archives.reduce((sum, item) => sum + item.categories.length, 0) }}</span>
      </DisclosureButton>
      <DisclosurePanel class="space-y-3 py-2 pl-7 pr-2">
        <fieldset
          v-for="archive in group.archives"
          :key="archive.archiveId"
        >
          <legend class="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
            {{ archive.archiveName }}
          </legend>
          <label
            v-for="category in archive.categories"
            :key="category.categoryId"
            class="flex min-h-9 cursor-pointer items-start gap-2 rounded px-2 py-1.5 text-sm hover:bg-white"
          >
            <input
              class="mt-0.5 size-4 rounded border-slate-300 text-brand-500"
              type="checkbox"
              :checked="modelValue.includes(category.categoryId)"
              @change="toggle(modelValue, category.categoryId, ($event.target as HTMLInputElement).checked)"
            >
            <span><span class="font-medium text-slate-800">{{ category.categoryId }}</span> <span class="text-slate-500">{{ category.categoryName }}</span></span>
          </label>
        </fieldset>
      </DisclosurePanel>
    </Disclosure>
  </div>
</template>
