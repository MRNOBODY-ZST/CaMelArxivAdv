<script setup lang="ts">
import { Menu, MenuButton, MenuItem, MenuItems } from '@headlessui/vue'

export interface DropdownItem { label: string; value: string; disabled?: boolean }
defineProps<{ label: string; items: readonly DropdownItem[] }>()
defineEmits<{ select: [value: string] }>()
</script>

<template>
  <Menu
    as="div"
    class="relative inline-block text-left"
  >
    <MenuButton class="inline-flex min-h-11 items-center rounded-md px-3 text-sm font-medium text-slate-700 hover:bg-slate-100">
      <slot name="trigger">
        {{ label }}
      </slot>
    </MenuButton>
    <Transition
      enter-active-class="transition ease-out duration-100"
      enter-from-class="scale-95 opacity-0"
      enter-to-class="scale-100 opacity-100"
      leave-active-class="transition ease-in duration-75"
      leave-from-class="scale-100 opacity-100"
      leave-to-class="scale-95 opacity-0"
    >
      <MenuItems class="absolute right-0 z-50 mt-2 w-48 origin-top-right rounded-md bg-white py-1 shadow-lg ring-1 ring-slate-900/10 focus:outline-none">
        <MenuItem
          v-for="item in items"
          :key="item.value"
          v-slot="{ active }"
          :disabled="item.disabled ?? false"
        >
          <button
            type="button"
            :disabled="item.disabled"
            :class="[active ? 'bg-slate-50 text-slate-900' : 'text-slate-700', 'block w-full px-3 py-2 text-left text-sm disabled:opacity-40']"
            @click="$emit('select', item.value)"
          >
            {{ item.label }}
          </button>
        </MenuItem>
      </MenuItems>
    </Transition>
  </Menu>
</template>
