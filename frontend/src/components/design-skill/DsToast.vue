<script setup lang="ts">
import { CheckCircleIcon, XMarkIcon } from '@heroicons/vue/20/solid'

defineProps<{ open: boolean; title: string; message?: string }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <div
    aria-live="polite"
    class="pointer-events-none fixed inset-0 z-70 flex items-end px-4 py-6 sm:items-start sm:justify-end"
  >
    <Transition
      enter-active-class="transition duration-200"
      enter-from-class="translate-y-2 opacity-0"
      leave-active-class="transition duration-150"
      leave-to-class="opacity-0"
    >
      <div
        v-if="open"
        class="pointer-events-auto w-full max-w-sm rounded-lg bg-white p-4 shadow-lg ring-1 ring-slate-900/10"
      >
        <div class="flex gap-3">
          <CheckCircleIcon
            class="size-5 shrink-0 text-emerald-500"
            aria-hidden="true"
          />
          <div class="min-w-0 flex-1">
            <p class="text-sm font-medium text-slate-900">
              {{ title }}
            </p><p
              v-if="message"
              class="mt-1 text-sm text-slate-500"
            >
              {{ message }}
            </p>
          </div>
          <button
            type="button"
            class="-m-2 min-h-11 min-w-11 p-2 text-slate-400 hover:text-slate-600"
            aria-label="关闭通知"
            @click="$emit('close')"
          >
            <XMarkIcon class="size-5" />
          </button>
        </div>
      </div>
    </Transition>
  </div>
</template>
