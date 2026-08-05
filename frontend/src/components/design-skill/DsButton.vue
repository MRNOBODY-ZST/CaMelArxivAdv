<script setup lang="ts">
import { computed } from 'vue'

type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger'
type ButtonSize = 'sm' | 'md'

const props = withDefaults(defineProps<{
  variant?: ButtonVariant
  size?: ButtonSize
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  busy?: boolean
}>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  disabled: false,
  busy: false,
})

defineEmits<{ click: [event: globalThis.MouseEvent] }>()

const classes = computed(() => [
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-3.5 text-sm font-semibold shadow-xs focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
  props.size === 'sm' ? 'sm:min-h-9 sm:px-3' : 'sm:min-h-10',
  {
    'bg-brand-500 text-white hover:bg-brand-600 focus-visible:outline-brand-500': props.variant === 'primary',
    'bg-white text-slate-800 ring-1 ring-slate-300 ring-inset hover:bg-slate-50': props.variant === 'secondary',
    'bg-transparent text-slate-600 shadow-none hover:bg-slate-100 hover:text-slate-900': props.variant === 'ghost',
    'bg-red-600 text-white hover:bg-red-700 focus-visible:outline-red-600': props.variant === 'danger',
  },
])
</script>

<template>
  <button
    :type="type"
    :class="classes"
    :disabled="disabled || busy"
    :aria-busy="busy || undefined"
    @click="$emit('click', $event)"
  >
    <svg
      v-if="busy"
      class="size-4 animate-spin"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle
        class="opacity-25"
        cx="12"
        cy="12"
        r="9"
        fill="none"
        stroke="currentColor"
        stroke-width="3"
      />
      <path
        class="opacity-75"
        fill="currentColor"
        d="M12 3a9 9 0 0 1 9 9h-3a6 6 0 0 0-6-6z"
      />
    </svg>
    <slot />
  </button>
</template>
