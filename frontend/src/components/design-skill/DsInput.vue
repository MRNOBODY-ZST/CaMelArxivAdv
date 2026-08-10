<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  id: string
  modelValue: string
  label?: string
  description?: string
  error?: string
  type?: 'text' | 'email' | 'password' | 'search' | 'url' | 'number'
  placeholder?: string
  disabled?: boolean
  autocomplete?: string
}>(), {
  type: 'text',
  disabled: false,
})

defineEmits<{ 'update:modelValue': [value: string] }>()

const describedBy = computed(() => [
  props.description ? `${props.id}-description` : undefined,
  props.error ? `${props.id}-error` : undefined,
].filter(Boolean).join(' ') || undefined)
</script>

<template>
  <div>
    <label
      v-if="label"
      :for="id"
      class="block text-sm/6 font-medium text-slate-900"
    >{{ label }}</label>
    <p
      v-if="description"
      :id="`${id}-description`"
      class="mt-1 text-xs/5 text-slate-500"
    >
      {{ description }}
    </p>
    <div :class="label || description ? 'mt-2' : ''">
      <input
        :id="id"
        :value="modelValue"
        :type="type"
        :placeholder="placeholder"
        :disabled="disabled"
        :autocomplete="autocomplete"
        :aria-describedby="describedBy"
        :aria-invalid="error ? 'true' : undefined"
        :class="[
          'block min-h-11 w-full rounded-md bg-white px-3 py-2 text-sm text-slate-900 shadow-xs outline-1 -outline-offset-1 placeholder:text-slate-400 focus:outline-2 focus:-outline-offset-2 disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500',
          error ? 'outline-red-500 focus:outline-red-600' : 'outline-slate-300 focus:outline-brand-500',
        ]"
        @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
      >
    </div>
    <p
      v-if="error"
      :id="`${id}-error`"
      class="mt-1 text-xs/5 text-red-600"
    >
      {{ error }}
    </p>
  </div>
</template>
