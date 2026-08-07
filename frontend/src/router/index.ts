import { createRouter, createWebHistory, type RouteRecordRaw, type Router } from 'vue-router'

import { useAuthStore } from '@/modules/auth/auth.store'
import type { Permission } from '@/modules/auth/auth.types'

declare module 'vue-router' {
  interface RouteMeta {
    publicLayout?: boolean
    requiresAuth?: boolean
    permissions?: readonly Permission[]
    pageTitle?: string
    pageSection?: string
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login', name: 'login', component: () => import('@/modules/auth/LoginView.vue'),
    meta: { publicLayout: true },
  },
  {
    path: '/', name: 'dashboard', component: () => import('@/views/DashboardView.vue'),
    meta: { requiresAuth: true, pageTitle: '数据总览', pageSection: '概览' },
  },
  {
    path: '/change-password', name: 'change-password',
    component: () => import('@/modules/auth/ChangePasswordView.vue'),
    meta: { requiresAuth: true, pageTitle: '修改密码', pageSection: '账号安全' },
  },
  {
    path: '/arxiv/discovery', name: 'arxiv-discovery',
    component: () => import('@/modules/arxiv/ArxivDiscoveryView.vue'),
    meta: { requiresAuth: true, permissions: ['paper:read'], pageTitle: '论文发现', pageSection: 'arXiv 数据' },
  },
  {
    path: '/jobs', name: 'import-jobs', component: () => import('@/modules/jobs/ImportJobsView.vue'),
    meta: { requiresAuth: true, permissions: ['paper:read'], pageTitle: '导入任务', pageSection: 'arXiv 数据' },
  },
  {
    path: '/jobs/:id', name: 'job-detail', component: () => import('@/modules/jobs/JobDetailView.vue'),
    meta: { requiresAuth: true, permissions: ['paper:read'], pageTitle: '任务详情', pageSection: 'arXiv 数据' },
  },
  {
    path: '/papers', name: 'papers', component: () => import('@/modules/papers/PapersView.vue'),
    meta: { requiresAuth: true, permissions: ['paper:read'], pageTitle: '论文库', pageSection: 'arXiv 数据' },
  },
  {
    path: '/papers/:id', name: 'paper-detail', component: () => import('@/modules/papers/PaperDetailView.vue'),
    meta: { requiresAuth: true, permissions: ['paper:read'], pageTitle: '论文详情', pageSection: 'arXiv 数据' },
  },
  {
    path: '/contacts', name: 'contacts', component: () => import('@/modules/contacts/ContactListView.vue'),
    meta: { requiresAuth: true, permissions: ['contact:read_masked'], pageTitle: '作者与联系人', pageSection: 'arXiv 数据' },
  },
  {
    path: '/email/templates', name: 'email-templates', component: () => import('@/modules/email/EmailTemplatesView.vue'),
    meta: { requiresAuth: true, permissions: ['template:read'], pageTitle: '邮件模板', pageSection: '邮件运营' },
  },
  {
    path: '/email/templates/new', name: 'email-template-new', component: () => import('@/modules/email/EmailTemplateEditorView.vue'),
    meta: { requiresAuth: true, permissions: ['template:manage'], pageTitle: '新建模板', pageSection: '邮件运营' },
  },
  {
    path: '/email/templates/:id', name: 'email-template-editor', component: () => import('@/modules/email/EmailTemplateEditorView.vue'),
    meta: { requiresAuth: true, permissions: ['template:read'], pageTitle: '模板编辑器', pageSection: '邮件运营' },
  },
  {
    path: '/analytics/ingestion', name: 'ingestion-analytics',
    component: () => import('@/modules/analytics/AnalyticsView.vue'), props: { view: 'ingestion' },
    meta: { requiresAuth: true, permissions: ['analytics:read'], pageTitle: '采集分析', pageSection: '数据分析' },
  },
  {
    path: '/analytics/papers', name: 'paper-analytics',
    component: () => import('@/modules/analytics/AnalyticsView.vue'), props: { view: 'papers' },
    meta: { requiresAuth: true, permissions: ['analytics:read'], pageTitle: '论文分析', pageSection: '数据分析' },
  },
  {
    path: '/analytics/contacts', name: 'contact-analytics',
    component: () => import('@/modules/analytics/AnalyticsView.vue'), props: { view: 'contacts' },
    meta: { requiresAuth: true, permissions: ['analytics:read'], pageTitle: '联系人分析', pageSection: '数据分析' },
  },
  {
    path: '/analytics/authors', name: 'author-analytics',
    component: () => import('@/modules/analytics/AuthorsAnalyticsView.vue'),
    meta: { requiresAuth: true, permissions: ['analytics:read'], pageTitle: '作者关系', pageSection: '数据分析' },
  },
  {
    path: '/admin/users', name: 'admin-users', component: () => import('@/modules/admin/UsersView.vue'),
    meta: {
      requiresAuth: true, permissions: ['user:read'], pageTitle: '用户管理', pageSection: '系统管理',
    },
  },
  {
    path: '/admin/roles', name: 'admin-roles', component: () => import('@/modules/admin/RolesView.vue'),
    meta: {
      requiresAuth: true, permissions: ['role:read'], pageTitle: '角色与权限', pageSection: '系统管理',
    },
  },
  {
    path: '/admin/audit', name: 'admin-audit', component: () => import('@/modules/admin/AuditLogsView.vue'),
    meta: {
      requiresAuth: true, permissions: ['audit:read'], pageTitle: '审计日志', pageSection: '系统管理',
    },
  },
  {
    path: '/admin/smtp-accounts', name: 'admin-smtp-accounts', component: () => import('@/modules/email/SmtpAccountsView.vue'),
    meta: { requiresAuth: true, permissions: ['smtp:read'], pageTitle: 'SMTP 账户', pageSection: '系统管理' },
  },
  {
    path: '/forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue'),
    meta: { requiresAuth: true, pageTitle: '无权访问', pageSection: '访问控制' },
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
