<script setup lang="ts">
withDefaults(defineProps<{ id: string; modelValue: boolean; label: string; description?: string; disabled?: boolean }>(), { disabled: false })
defineEmits<{ 'update:modelValue': [value: boolean] }>()
</script>

<template>
  <div class="flex gap-3">
    <input
      :id="id"
      type="checkbox"
      :checked="modelValue"
      :disabled="disabled"
      class="mt-1 size-4 min-h-4 min-w-4 shrink-0 rounded border-slate-300 text-brand-500 focus:ring-brand-500 disabled:opacity-50"
      @change="$emit('update:modelValue', ($event.target as HTMLInputElement).checked)"
    >
    <div>
      <label
        :for="id"
        class="text-sm font-medium text-slate-900"
      >{{ label }}</label><p
        v-if="description"
        class="mt-0.5 text-xs/5 text-slate-500"
      >
        {{ description }}
      </p>
    </div>
  </div>
</template>
