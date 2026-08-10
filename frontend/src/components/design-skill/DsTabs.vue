<script setup lang="ts">
import { Tab, TabGroup, TabList, TabPanel, TabPanels } from '@headlessui/vue'

export interface TabItem { label: string; value: string }
defineProps<{ tabs: readonly TabItem[] }>()
</script>

<template>
  <TabGroup>
    <TabList class="flex gap-1 border-b border-slate-200">
      <Tab
        v-for="tab in tabs"
        :key="tab.value"
        v-slot="{ selected }"
        as="template"
      >
        <button
          type="button"
          :class="[selected ? 'border-brand-500 text-brand-600' : 'border-transparent text-slate-500 hover:text-slate-700', 'min-h-11 border-b-2 px-3 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-500']"
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
