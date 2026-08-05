import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { authApi } from '@/modules/auth/auth.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import type { AuthSessionResponse, CurrentUser } from '@/modules/auth/auth.types'

vi.mock('@/modules/auth/auth.api', () => ({
  authApi: {
    login: vi.fn(),
    logout: vi.fn(),
    me: vi.fn(),
    refresh: vi.fn(),
  },
}))

const user: CurrentUser = {
  id: '5d3a9802-375f-42ee-9739-d419299bc4a8',
  username: 'admin',
  displayName: 'Administrator',
  roles: ['SUPER_ADMIN'],
  permissions: ['user:read', 'system:manage'],
  mustChangePassword: false,
}

const session: AuthSessionResponse = {
  accessToken: 'memory-access-token',
  tokenType: 'Bearer',
  expiresInSeconds: 600,
  user,
}

describe('auth store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('logs in and keeps the access token in memory only', async () => {
    vi.mocked(authApi.login).mockResolvedValue(session)
    const localWrite = vi.spyOn(Storage.prototype, 'setItem')
    const store = useAuthStore()

    await store.login('admin', 'Maple!Orbit92')

    expect(store.accessToken).toBe('memory-access-token')
    expect(store.user).toEqual(user)
    expect(localWrite).not.toHaveBeenCalled()
    expect(localStorage.getItem('accessToken')).toBeNull()
    expect(sessionStorage.getItem('accessToken')).toBeNull()
  })

  it('bootstraps through the refresh cookie and then reads the live user', async () => {
    vi.mocked(authApi.refresh).mockResolvedValue(session)
    vi.mocked(authApi.me).mockResolvedValue({ ...user, displayName: 'Live Administrator' })
    const store = useAuthStore()

    await store.bootstrap()

    expect(authApi.refresh).toHaveBeenCalledTimes(1)
    expect(authApi.me).toHaveBeenCalledWith('memory-access-token')
    expect(store.user?.displayName).toBe('Live Administrator')
    expect(store.initialized).toBe(true)
  })

  it('clears every in-memory identity field when refresh fails', async () => {
    vi.mocked(authApi.login).mockResolvedValue(session)
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('expired session'))
    const store = useAuthStore()
    await store.login('admin', 'Maple!Orbit92')

    await expect(store.refresh()).rejects.toThrow('expired session')

    expect(store.accessToken).toBeNull()
    expect(store.user).toBeNull()
    expect(store.authenticated).toBe(false)
  })
})
