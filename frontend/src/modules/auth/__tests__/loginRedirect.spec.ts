import { describe, expect, it } from 'vitest'

import { safePostLoginRedirect } from '@/modules/auth/loginRedirect'

describe('safePostLoginRedirect', () => {
  it('keeps a valid protected destination', () => {
    expect(safePostLoginRedirect('/admin/users')).toBe('/admin/users')
  })

  it('drops public, forced-password and external destinations', () => {
    expect(safePostLoginRedirect('/login')).toBe('/')
    expect(safePostLoginRedirect('/change-password')).toBe('/')
    expect(safePostLoginRedirect('/change-password?next=/admin/users')).toBe('/')
    expect(safePostLoginRedirect('//example.test')).toBe('/')
    expect(safePostLoginRedirect('https://example.test')).toBe('/')
  })
})
