<script setup lang="ts">
import { Dialog, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { XMarkIcon } from '@heroicons/vue/24/outline'

defineProps<{ open: boolean; title: string; description?: string }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <TransitionRoot
    as="template"
    :show="open"
  >
    <Dialog
      class="relative z-60"
      @close="$emit('close')"
    >
      <TransitionChild
        as="template"
        enter="ease-out duration-200"
        enter-from="opacity-0"
        enter-to="opacity-100"
        leave="ease-in duration-150"
        leave-from="opacity-100"
        leave-to="opacity-0"
      >
        <div class="fixed inset-0 bg-slate-900/50" />
      </TransitionChild>
      <div class="fixed inset-0 overflow-y-auto p-4 sm:p-6">
        <div class="flex min-h-full items-center justify-center">
          <TransitionChild
            as="template"
            enter="ease-out duration-200"
            enter-from="translate-y-4 opacity-0 sm:scale-95"
            enter-to="translate-y-0 opacity-100 sm:scale-100"
            leave="ease-in duration-150"
            leave-from="translate-y-0 opacity-100 sm:scale-100"
            leave-to="translate-y-4 opacity-0 sm:scale-95"
          >
            <DialogPanel class="w-full max-w-lg rounded-xl bg-white p-5 shadow-xl ring-1 ring-slate-900/10 sm:p-6">
              <div class="flex items-start justify-between gap-4">
                <div>
                  <DialogTitle class="text-base font-semibold text-slate-900">
                    {{ title }}
                  </DialogTitle><p
                    v-if="description"
                    class="mt-1 text-sm text-slate-500"
                  >
                    {{ description }}
                  </p>
                </div><button
                  type="button"
                  class="-m-2 min-h-11 min-w-11 p-2 text-slate-400 hover:text-slate-600"
                  aria-label="关闭"
                  @click="$emit('close')"
                >
                  <XMarkIcon class="size-5" />
                </button>
              </div>
              <div class="mt-5">
                <slot />
              </div><div
                v-if="$slots.actions"
                class="mt-6 flex justify-end gap-3"
              >
                <slot name="actions" />
              </div>
            </DialogPanel>
          </TransitionChild>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
