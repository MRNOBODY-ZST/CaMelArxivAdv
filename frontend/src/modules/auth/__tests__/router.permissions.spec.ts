import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authApi } from '@/modules/auth/auth.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import { installAuthGuards, routes } from '@/router'

vi.mock('@/modules/auth/auth.api', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    refresh: vi.fn(),
  },
}))

describe('router permissions', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('no refresh cookie'))
  })

  it('redirects anonymous users to login and preserves the intended route', async () => {
    const router = guardedRouter()

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('login')
    expect(router.currentRoute.value.query.redirect).toBe('/admin/users')
  })

  it('denies a route when the live user lacks its declared permission', async () => {
    const store = useAuthStore()
    store.acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: {
        id: '5d3a9802-375f-42ee-9739-d419299bc4a8', username: 'viewer',
        displayName: 'Viewer', roles: ['VIEWER'], permissions: ['paper:read'],
        mustChangePassword: false,
      },
    })
    const router = guardedRouter()

    await router.push('/admin/users')

    expect(router.currentRoute.value.name).toBe('forbidden')
  })

  it('forces the initial password-change route before all other protected pages', async () => {
    const store = useAuthStore()
    store.acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: {
        id: '5d3a9802-375f-42ee-9739-d419299bc4a8', username: 'admin',
        displayName: 'Administrator', roles: ['SUPER_ADMIN'], permissions: ['user:read'],
        mustChangePassword: true,
      },
    })
    const router = guardedRouter()

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('change-password')
  })
})

function guardedRouter() {
  const router = createRouter({ history: createMemoryHistory(), routes })
  installAuthGuards(router)
  return router
}
