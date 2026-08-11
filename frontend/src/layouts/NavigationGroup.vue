<script setup lang="ts">
import { ChevronDownIcon } from '@heroicons/vue/20/solid'
import { computed, ref, watch, type Component } from 'vue'
import { RouterLink } from 'vue-router'

export interface NavigationGroupItem {
  label: string
  href: string
  icon: Component
}

const props = withDefaults(defineProps<{
  id: string
  label: string
  items: readonly NavigationGroupItem[]
  currentPath: string
  defaultOpen?: boolean
}>(), { defaultOpen: false })

const panelId = computed(() => `navigation-group-${props.id}`)
const containsActiveRoute = computed(() => props.items.some((item) => isCurrent(item.href)))
const open = ref(props.defaultOpen || containsActiveRoute.value)

watch(() => props.currentPath, () => {
  if (containsActiveRoute.value) open.value = true
})

function isCurrent(href: string): boolean {
  return props.currentPath === href || props.currentPath.startsWith(`${href}/`)
}
</script>

<template>
  <li>
    <button
      type="button"
      class="group flex min-h-10 w-full items-center gap-2 rounded-md px-2 text-left text-xs font-semibold text-slate-500 hover:bg-slate-50 hover:text-slate-900"
      :aria-controls="panelId"
      :aria-expanded="open"
      @click="open = !open"
    >
      <span class="flex-1">{{ label }}</span>
      <ChevronDownIcon
        :class="[open ? 'rotate-180 text-slate-600' : 'text-slate-400', 'size-4 transition-transform']"
        aria-hidden="true"
      />
    </button>
    <Transition
      enter-active-class="transition duration-150 ease-out"
      enter-from-class="-translate-y-1 opacity-0"
      leave-active-class="transition duration-100 ease-in"
      leave-to-class="-translate-y-1 opacity-0"
    >
      <ul
        v-if="open"
        :id="panelId"
        role="list"
        class="mt-1 space-y-0.5"
      >
        <li
          v-for="item in items"
          :key="item.href"
        >
          <RouterLink
            :to="item.href"
            :aria-current="isCurrent(item.href) ? 'page' : undefined"
            :class="[
              isCurrent(item.href)
                ? 'bg-brand-50 text-brand-700'
                : 'text-slate-600 hover:bg-slate-50 hover:text-slate-950',
              'group flex min-h-10 items-center gap-3 rounded-md px-2.5 text-sm font-medium',
            ]"
          >
            <component
              :is="item.icon"
              :class="[
                isCurrent(item.href) ? 'text-brand-500' : 'text-slate-400 group-hover:text-slate-600',
                'size-5 shrink-0',
              ]"
              aria-hidden="true"
            />
            <span class="truncate">{{ item.label }}</span>
          </RouterLink>
        </li>
      </ul>
    </Transition>
  </li>
</template>
