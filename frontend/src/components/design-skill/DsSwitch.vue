<script setup lang="ts">
import { Switch, SwitchDescription, SwitchGroup, SwitchLabel } from '@headlessui/vue'

withDefaults(defineProps<{ modelValue: boolean; label: string; description?: string; disabled?: boolean }>(), { disabled: false })
defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <SwitchGroup
    as="div"
    class="flex items-center justify-between gap-4"
  >
    <span class="flex grow flex-col"><SwitchLabel
      as="span"
      class="text-sm font-medium text-slate-900"
    >{{ label }}</SwitchLabel><SwitchDescription
      v-if="description"
      as="span"
      class="mt-0.5 text-xs/5 text-slate-500"
    >{{ description }}</SwitchDescription></span>
    <Switch
      :model-value="modelValue"
      :disabled="disabled"
      :class="[modelValue ? 'bg-brand-500' : 'bg-slate-200', 'relative inline-flex h-7 w-12 shrink-0 cursor-pointer rounded-full p-0.5 ring-1 ring-transparent transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-brand-500 disabled:cursor-not-allowed disabled:opacity-50']"
      @update:model-value="$emit('update:modelValue', $event)"
    >
      <span
        aria-hidden="true"
        :class="[modelValue ? 'translate-x-5' : 'translate-x-0', 'pointer-events-none inline-block size-6 rounded-full bg-white shadow-sm ring-1 ring-slate-900/5 transition']"
      />
    </Switch>
  </SwitchGroup>
</template>
