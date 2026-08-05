import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

import DashboardView from '@/views/DashboardView.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

declare module 'vue-router' {
  interface RouteMeta {
    publicLayout?: boolean
    requiresAuth?: boolean
    permissions?: readonly Permission[]
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login', name: 'login', component: () => import('@/modules/auth/LoginView.vue'),
    meta: { publicLayout: true },
  },
  { path: '/', name: 'dashboard', component: DashboardView, meta: { requiresAuth: true } },
  {
    path: '/change-password', name: 'change-password',
    component: () => import('@/modules/auth/ChangePasswordView.vue'), meta: { requiresAuth: true },
  },
  {
    path: '/admin/users', name: 'admin-users', component: () => import('@/views/PhasePlaceholderView.vue'),
    props: { title: '用户管理', description: '创建、启停、分配角色及重置用户密码。' },
    meta: { requiresAuth: true, permissions: ['user:read'] },
  },
  {
    path: '/admin/roles', name: 'admin-roles', component: () => import('@/views/PhasePlaceholderView.vue'),
    props: { title: '角色与权限', description: '维护自定义角色并查看权限目录。' },
    meta: { requiresAuth: true, permissions: ['role:read'] },
  },
  {
    path: '/admin/audit', name: 'admin-audit', component: () => import('@/views/PhasePlaceholderView.vue'),
    props: { title: '审计日志', description: '按操作者、动作、资源、结果和时间追溯敏感操作。' },
    meta: { requiresAuth: true, permissions: ['audit:read'] },
  },
  {
    path: '/forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue'),
    meta: { requiresAuth: true },
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export function installAuthGuards(target: Router): void {
  target.beforeEach(async (to) => {
    const auth = useAuthStore()
    if (!auth.initialized) await auth.bootstrap()

    if (to.name === 'login') {
      if (!auth.authenticated) return true
      return auth.user?.mustChangePassword ? { name: 'change-password' } : { name: 'dashboard' }
    }
    if (to.meta.requiresAuth && !auth.authenticated) {
      return { name: 'login', query: { redirect: to.fullPath } }
    }
    if (auth.authenticated && auth.user?.mustChangePassword && to.name !== 'change-password') {
      return { name: 'change-password' }
    }
    if (to.meta.permissions && !auth.hasEveryPermission(to.meta.permissions)) {
      return { name: 'forbidden' }
    }
    return true
  })
}

export const router = createRouter({ history: createWebHistory(), routes })
installAuthGuards(router)
