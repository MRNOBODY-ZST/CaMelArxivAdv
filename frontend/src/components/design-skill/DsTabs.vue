<script setup lang="ts">
import { Tab, TabGroup, TabList, TabPanel, TabPanels } from '@headlessui/vue'
import { computed, ref } from 'vue'

export interface TabItem { label: string; value: string }
const props = defineProps<{ tabs: readonly TabItem[]; selected?: string }>()
const emit = defineEmits<{ 'update:selected': [value: string] }>()
const localSelected = ref<string | null>(null)

const selectedIndex = computed(() => {
  const selected = props.selected === undefined ? localSelected.value : props.selected
  if (selected === null) return 0
  const index = props.tabs.findIndex((tab) => tab.value === selected)
  return index >= 0 ? index : 0
})

function select(index: number): void {
  const tab = props.tabs[index]
  if (!tab) return
  if (props.selected === undefined) localSelected.value = tab.value
  emit('update:selected', tab.value)
}
</script>

<template>
  <TabGroup
    :selected-index="selectedIndex"
    @change="select"
  >
    <TabList class="flex gap-1 border-b border-slate-200">
      <Tab
        v-for="tab in tabs"
        :key="tab.value"
        v-slot="{ selected: isSelected }"
        as="template"
      >
        <button
          type="button"
          :class="[isSelected ? 'border-brand-500 text-brand-600' : 'border-transparent text-slate-500 hover:text-slate-700', 'min-h-11 border-b-2 px-3 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500']"
        >
          {{ tab.label }}
        </button>
      </Tab>
    </TabList>
    <TabPanels class="mt-4">
      <TabPanel
        v-for="tab in tabs"
        :key="tab.value"
      >
        <slot :name="tab.value" />
      </TabPanel>
    </TabPanels>
  </TabGroup>
</template>
