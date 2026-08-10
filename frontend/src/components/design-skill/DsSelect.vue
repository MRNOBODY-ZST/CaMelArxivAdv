<script setup lang="ts">
export interface SelectOption { label: string; value: string; disabled?: boolean }

withDefaults(defineProps<{
  id: string
  modelValue: string
  options: readonly SelectOption[]
  label?: string
  error?: string
  disabled?: boolean
}>(), { disabled: false })
defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div>
    <label
      v-if="label"
      :for="id"
      class="block text-sm/6 font-medium text-slate-900"
    >{{ label }}</label>
    <select
      :id="id"
      :value="modelValue"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? `${id}-error` : undefined"
      :class="[
        'block min-h-11 w-full rounded-md bg-white py-2 pr-8 pl-3 text-sm text-slate-900 shadow-xs outline-1 -outline-offset-1 focus:outline-2 focus:-outline-offset-2 disabled:bg-slate-50',
        label ? 'mt-2' : '',
        error ? 'outline-red-500 focus:outline-red-600' : 'outline-slate-300 focus:outline-brand-500',
      ]"
      @change="$emit('update:modelValue', ($event.target as HTMLSelectElement).value)"
    >
      <option
        v-for="option in options"
        :key="option.value"
        :value="option.value"
        :disabled="option.disabled"
      >
        {{ option.label }}
      </option>
    </select>
    <p
      v-if="error"
      :id="`${id}-error`"
      class="mt-1 text-xs/5 text-red-600"
    >
      {{ error }}
    </p>
  </div>
</template>
