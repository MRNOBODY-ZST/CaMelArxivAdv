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
  ShareIcon,
  ShieldCheckIcon,
  UserGroupIcon,
  UsersIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import { computed, defineComponent, h, type Component, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import { useFocusReturningDisclosure } from '@/composables/useFocusReturningDisclosure'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

interface NavigationItem {
  label: string
  href: string
  icon: Component
  permission?: Permission
  anyPermissions?: readonly Permission[]
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

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const navigation: readonly NavigationGroup[] = [
  { label: '概览', items: [{ label: '数据总览', href: '/', icon: HomeIcon }] },
  { label: 'arXiv 数据', items: [
    { label: '论文发现', href: '/arxiv/discovery', icon: MagnifyingGlassIcon, permission: 'paper:read' },
    { label: '导入任务', href: '/jobs', icon: ArrowDownTrayIcon, permission: 'paper:read' },
    { label: '论文库', href: '/papers', icon: ArchiveBoxIcon, permission: 'paper:read' },
    { label: '作者与联系人', href: '/contacts', icon: UsersIcon, permission: 'contact:read_masked' },
  ] },
  { label: '邮件运营', items: [
    { label: '邮件模板', href: '/email/templates', icon: DocumentTextIcon, permission: 'template:read' },
    { label: '收件人分组', href: '/email/segments', icon: FolderIcon, permission: 'campaign:read' },
    { label: '邮件活动', href: '/email/campaigns', icon: PaperAirplaneIcon, permission: 'campaign:read' },
    { label: '发送记录', href: '/email/deliveries', icon: EnvelopeIcon, permission: 'campaign:read' },
  ] },
  { label: '数据分析', items: [
    { label: '采集分析', href: '/analytics/ingestion', icon: ChartBarIcon, permission: 'analytics:read' },
    { label: '论文分析', href: '/analytics/papers', icon: DocumentChartBarIcon, permission: 'analytics:read' },
    { label: '联系人分析', href: '/analytics/contacts', icon: ChartPieIcon, permission: 'analytics:read' },
    { label: '作者关系', href: '/analytics/authors', icon: ShareIcon, permission: 'analytics:read' },
    { label: '活动分析', href: '/analytics/campaigns', icon: DocumentChartBarIcon, permission: 'analytics:read' },
    { label: '链接分析', href: '/analytics/links', icon: LinkIcon, permission: 'analytics:read' },
  ] },
  { label: '系统管理', items: [
    { label: '邮件账户', href: '/admin/mail-accounts', icon: ServerStackIcon, anyPermissions: ['smtp:read', 'mailbox:read'] },
    { label: '用户管理', href: '/admin/users', icon: UserGroupIcon, permission: 'user:read' },
    { label: '角色与权限', href: '/admin/roles', icon: ShieldCheckIcon, permission: 'role:read' },
    { label: '审计日志', href: '/admin/audit', icon: ClipboardDocumentListIcon, permission: 'audit:read' },
    { label: '系统设置', href: '/admin/settings', icon: Cog6ToothIcon, permission: 'system:manage' },
  ] },
]

const visibleNavigation = computed(() => navigation
  .map((group) => ({
    ...group,
    items: group.items.filter((item) => (
      (!item.permission || auth.hasPermission(item.permission))
      && (!item.anyPermissions || item.anyPermissions.some(auth.hasPermission))
    )),
  }))
  .filter((group) => group.items.length > 0))

const pageTitle = computed(() => route.meta.pageTitle ?? '数据总览')
const pageSection = computed(() => route.meta.pageSection ?? '概览')

watch(() => route.fullPath, () => {
  if (sidebarOpen.value) void closeSidebar()
})

async function logout(): Promise<void> {
  await auth.logout()
  await router.replace({ name: 'login' })
}

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
          ...visibleNavigation.value.map((group) => h('li', { key: group.label }, [
            h('p', { class: 'mb-1.5 px-2 text-[11px] font-semibold uppercase tracking-wider text-slate-400' }, group.label),
            h('ul', { class: 'space-y-0.5', role: 'list' }, group.items.map((item) => h('li', { key: item.label }, [
              h(RouterLink, {
                to: item.href,
                'aria-current': route.path === item.href ? 'page' : undefined,
                class: [
                  'group flex min-h-10 items-center gap-3 rounded-md px-2.5 text-sm font-medium',
                  route.path === item.href ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-50 hover:text-slate-900',
                ],
              }, { default: () => [h(item.icon, { class: ['size-5 shrink-0', route.path === item.href ? 'text-brand-500' : 'text-slate-400 group-hover:text-slate-600'], 'aria-hidden': true }), item.label] }),
            ]))),
          ])),
          h('li', { class: 'mt-auto pt-2' }, [h('div', { class: 'rounded-md bg-slate-50 p-3 text-xs/5 text-slate-500' }, [h('p', { class: 'font-medium text-slate-700' }, '邮件协议安全'), h('p', '公网连接强制 TLS · 草稿不会自动发送')])]),
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
          <p
            data-testid="page-title"
            class="truncate text-sm font-semibold text-slate-900"
          >
            {{ pageTitle }}
          </p><p
            data-testid="page-breadcrumb"
            class="hidden truncate text-xs text-slate-500 sm:block"
          >
            {{ pageSection }} / {{ pageTitle }}
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
            <span class="flex size-8 items-center justify-center rounded-full bg-brand-100 text-xs font-semibold text-brand-700">{{ auth.user?.displayName.slice(0, 1) ?? '用' }}</span><span class="hidden text-sm font-medium text-slate-700 xl:block">{{ auth.user?.displayName ?? '用户' }}</span><ChevronDownIcon class="hidden size-4 text-slate-400 xl:block" />
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
                  @click="logout"
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
