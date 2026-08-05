<script setup lang="ts">
import {
  Dialog,
  DialogPanel,
  Menu,
  MenuButton,
  MenuItem,
  MenuItems,
  TransitionChild,
  TransitionRoot,
} from '@headlessui/vue'
import {
  ArchiveBoxIcon,
  ArrowDownTrayIcon,
  Bars3Icon,
  BellIcon,
  ChartBarIcon,
  ChartPieIcon,
  ChevronDownIcon,
  ClipboardDocumentListIcon,
  Cog6ToothIcon,
  DocumentChartBarIcon,
  DocumentTextIcon,
  EnvelopeIcon,
  FolderIcon,
  HomeIcon,
  LinkIcon,
  MagnifyingGlassIcon,
  PaperAirplaneIcon,
  ServerStackIcon,
  ShieldCheckIcon,
  UserGroupIcon,
  UsersIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import { defineComponent, h, type Component } from 'vue'

import { useFocusReturningDisclosure } from '@/composables/useFocusReturningDisclosure'

interface NavigationItem {
  label: string
  href: string
  icon: Component
  current?: boolean
}

interface NavigationGroup {
  label: string
  items: readonly NavigationItem[]
}

const {
  close: closeSidebar,
  open: sidebarOpen,
  restoreFocus: restoreSidebarFocus,
  show: openSidebar,
  trigger: mobileNavigationTrigger,
} = useFocusReturningDisclosure()

const navigation: readonly NavigationGroup[] = [
  { label: '概览', items: [{ label: '数据总览', href: '/', icon: HomeIcon, current: true }] },
  { label: 'arXiv 数据', items: [
    { label: '论文发现', href: '#paper-discovery', icon: MagnifyingGlassIcon },
    { label: '导入任务', href: '#import-jobs', icon: ArrowDownTrayIcon },
    { label: '论文库', href: '#papers', icon: ArchiveBoxIcon },
    { label: '作者与联系人', href: '#contacts', icon: UsersIcon },
  ] },
  { label: '邮件运营', items: [
    { label: '邮件模板', href: '#templates', icon: DocumentTextIcon },
    { label: '收件人分组', href: '#segments', icon: FolderIcon },
    { label: '邮件活动', href: '#campaigns', icon: PaperAirplaneIcon },
    { label: '发送记录', href: '#deliveries', icon: EnvelopeIcon },
  ] },
  { label: '数据分析', items: [
    { label: '采集分析', href: '#ingestion-analytics', icon: ChartBarIcon },
    { label: '联系人分析', href: '#contact-analytics', icon: ChartPieIcon },
    { label: '活动分析', href: '#campaign-analytics', icon: DocumentChartBarIcon },
    { label: '链接分析', href: '#link-analytics', icon: LinkIcon },
  ] },
  { label: '系统管理', items: [
    { label: 'SMTP 账户', href: '#smtp', icon: ServerStackIcon },
    { label: '用户管理', href: '#users', icon: UserGroupIcon },
    { label: '角色与权限', href: '#roles', icon: ShieldCheckIcon },
    { label: '审计日志', href: '#audit', icon: ClipboardDocumentListIcon },
    { label: '系统设置', href: '#settings', icon: Cog6ToothIcon },
  ] },
]

const SidebarContent = defineComponent({
  name: 'SidebarContent',
  setup() {
    return () => h('div', { class: 'flex min-h-full flex-col' }, [
      h('div', { class: 'flex h-16 shrink-0 items-center gap-3' }, [
        h('span', { class: 'grid size-8 place-items-center rounded-lg bg-brand-500 text-sm font-bold text-white' }, 'C'),
        h('span', { class: 'text-base font-semibold tracking-tight text-slate-900' }, 'CaMel Arxiv'),
      ]),
      h('nav', { class: 'flex flex-1 flex-col', 'aria-label': '主导航' }, [
        h('ul', { class: 'flex flex-1 flex-col gap-y-5', role: 'list' }, [
          ...navigation.map((group) => h('li', { key: group.label }, [
            h('p', { class: 'mb-1.5 px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400' }, group.label),
            h('ul', { class: 'space-y-0.5', role: 'list' }, group.items.map((item) => h('li', { key: item.label }, [
              h('a', {
                href: item.href,
                'aria-current': item.current ? 'page' : undefined,
                class: [
                  'group flex min-h-10 items-center gap-3 rounded-md px-2.5 text-sm font-medium',
                  item.current ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900',
                ],
              }, [h(item.icon, { class: ['size-5 shrink-0', item.current ? 'text-brand-500' : 'text-slate-400 group-hover:text-slate-600'], 'aria-hidden': true }), item.label]),
            ]))),
          ])),
          h('li', { class: 'mt-auto pt-2' }, [h('div', { class: 'rounded-md bg-slate-50 p-3 text-xs/5 text-slate-500' }, [h('p', { class: 'font-medium text-slate-700' }, '安全发送默认开启'), h('p', '真实 SMTP 当前默认禁用')])]),
        ]),
      ]),
    ])
  },
})
</script>

<template>
  <div
    data-design-skill="sidebar-with-header"
    class="min-h-screen bg-slate-50"
  >
    <TransitionRoot
      as="template"
      :show="sidebarOpen"
      @after-leave="restoreSidebarFocus"
    >
      <Dialog
        class="relative z-50 lg:hidden"
        @close="closeSidebar"
      >
        <TransitionChild
          as="template"
          enter="transition-opacity ease-linear duration-200"
          enter-from="opacity-0"
          enter-to="opacity-100"
          leave="transition-opacity ease-linear duration-200"
          leave-from="opacity-100"
          leave-to="opacity-0"
        >
          <div class="fixed inset-0 bg-slate-900/70" />
        </TransitionChild>
        <div class="fixed inset-0 flex">
          <TransitionChild
            as="template"
            enter="transition ease-in-out duration-200 transform"
            enter-from="-translate-x-full"
            enter-to="translate-x-0"
            leave="transition ease-in-out duration-200 transform"
            leave-from="translate-x-0"
            leave-to="-translate-x-full"
          >
            <DialogPanel class="relative mr-12 flex w-full max-w-xs flex-1 bg-white">
              <div class="absolute top-0 left-full flex w-12 justify-center pt-3">
                <button
                  type="button"
                  class="min-h-11 min-w-11 p-2.5 text-white"
                  aria-label="关闭侧边栏"
                  @click="closeSidebar"
                >
                  <XMarkIcon class="size-6" />
                </button>
              </div>
              <div class="flex grow flex-col overflow-y-auto px-5 pb-5">
                <SidebarContent />
              </div>
            </DialogPanel>
          </TransitionChild>
        </div>
      </Dialog>
    </TransitionRoot>

    <aside
      data-testid="desktop-sidebar"
      class="hidden border-r border-slate-200 bg-white lg:fixed lg:inset-y-0 lg:z-50 lg:flex lg:w-64 lg:flex-col"
    >
      <div class="flex grow flex-col overflow-y-auto px-5 pb-5">
        <SidebarContent />
      </div>
    </aside>

    <div class="lg:pl-64">
      <header class="sticky top-0 z-40 flex h-16 items-center gap-3 border-b border-slate-200 bg-white px-4 shadow-xs sm:px-6 lg:px-8">
        <button
          ref="mobileNavigationTrigger"
          data-testid="mobile-navigation"
          type="button"
          class="-ml-2 min-h-11 min-w-11 p-2 text-slate-600 hover:text-slate-900 lg:hidden"
          aria-label="打开侧边栏"
          @click="openSidebar"
        >
          <Bars3Icon class="size-6" />
        </button>
        <div
          class="hidden h-6 w-px bg-slate-200 sm:block lg:hidden"
          aria-hidden="true"
        />
        <div class="min-w-0 flex-1">
          <p class="truncate text-sm font-semibold text-slate-900">
            数据总览
          </p><p class="hidden truncate text-xs text-slate-500 sm:block">
            概览 / 数据总览
          </p>
        </div>
        <button
          type="button"
          class="hidden min-h-11 items-center gap-2 rounded-md px-3 text-sm text-slate-500 hover:bg-slate-50 sm:flex"
          aria-label="打开全局搜索"
        >
          <MagnifyingGlassIcon class="size-5" /><span class="hidden xl:inline">全局搜索</span><kbd class="hidden rounded border border-slate-200 px-1.5 py-0.5 text-[10px] text-slate-400 xl:inline">⌘K</kbd>
        </button>
        <span class="hidden items-center gap-2 text-xs font-medium text-slate-600 md:flex"><span class="size-2 rounded-full bg-slate-300" />运行中任务 —</span>
        <button
          data-testid="mobile-task-status"
          type="button"
          class="grid min-h-11 min-w-11 place-items-center rounded-md hover:bg-slate-50 md:hidden"
          aria-label="运行中任务 —"
        >
          <span
            class="size-2 rounded-full bg-slate-300"
            aria-hidden="true"
          />
        </button>
        <button
          type="button"
          class="min-h-11 min-w-11 p-2 text-slate-400 hover:text-slate-600"
          aria-label="查看通知"
        >
          <BellIcon class="size-5" />
        </button>
        <Menu
          as="div"
          class="relative"
        >
          <MenuButton class="flex min-h-11 items-center gap-2 rounded-md px-1.5 hover:bg-slate-50">
            <span class="flex size-8 items-center justify-center rounded-full bg-brand-100 text-xs font-semibold text-brand-700">管</span><span class="hidden text-sm font-medium text-slate-700 xl:block">管理员</span><ChevronDownIcon class="hidden size-4 text-slate-400 xl:block" />
          </MenuButton>
          <Transition
            enter-active-class="transition duration-100"
            enter-from-class="scale-95 opacity-0"
            leave-active-class="transition duration-75"
            leave-to-class="scale-95 opacity-0"
          >
            <MenuItems class="absolute right-0 z-50 mt-2 w-40 origin-top-right rounded-md bg-white py-1 shadow-lg ring-1 ring-slate-900/10 focus:outline-none">
              <MenuItem v-slot="{ active }">
                <button
                  type="button"
                  :class="[active ? 'bg-slate-50' : '', 'block w-full px-3 py-2 text-left text-sm text-slate-700']"
                >
                  个人设置
                </button>
              </MenuItem><MenuItem v-slot="{ active }">
                <button
                  type="button"
                  :class="[active ? 'bg-slate-50' : '', 'block w-full px-3 py-2 text-left text-sm text-slate-700']"
                >
                  退出登录
                </button>
              </MenuItem>
            </MenuItems>
          </Transition>
        </Menu>
      </header>
      <main class="px-4 py-6 sm:px-6 lg:px-8">
        <slot />
      </main>
    </div>
  </div>
</template>
