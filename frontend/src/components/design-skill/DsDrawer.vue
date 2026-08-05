<script setup lang="ts">
import { Dialog, DialogPanel, DialogTitle, TransitionChild, TransitionRoot } from '@headlessui/vue'
import { XMarkIcon } from '@heroicons/vue/24/outline'

defineProps<{ open: boolean; title: string }>()
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
        enter="ease-in-out duration-200"
        enter-from="opacity-0"
        enter-to="opacity-100"
        leave="ease-in-out duration-200"
        leave-from="opacity-100"
        leave-to="opacity-0"
      >
        <div class="fixed inset-0 bg-slate-900/50" />
      </TransitionChild>
      <div class="fixed inset-0 overflow-hidden">
        <div class="absolute inset-0 overflow-hidden">
          <div class="pointer-events-none fixed inset-y-0 right-0 flex max-w-full pl-10">
            <TransitionChild
              as="template"
              enter="transform transition ease-in-out duration-200"
              enter-from="translate-x-full"
              enter-to="translate-x-0"
              leave="transform transition ease-in-out duration-200"
              leave-from="translate-x-0"
              leave-to="translate-x-full"
            >
              <DialogPanel class="pointer-events-auto w-screen max-w-md bg-white shadow-xl">
                <div class="flex h-full flex-col overflow-y-auto p-5 sm:p-6">
                  <div class="flex items-center justify-between">
                    <DialogTitle class="text-base font-semibold text-slate-900">
                      {{ title }}
                    </DialogTitle><button
                      type="button"
                      class="-m-2 min-h-11 min-w-11 p-2 text-slate-400 hover:text-slate-600"
                      aria-label="关闭"
                      @click="$emit('close')"
                    >
                      <XMarkIcon class="size-5" />
                    </button>
                  </div><div class="mt-6 grow">
                    <slot />
                  </div>
                </div>
              </DialogPanel>
            </TransitionChild>
          </div>
        </div>
      </div>
    </Dialog>
  </TransitionRoot>
</template>
