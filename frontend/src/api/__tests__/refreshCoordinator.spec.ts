import { describe, expect, it, vi } from 'vitest'

import { createRefreshCoordinator } from '@/api/refreshCoordinator'

describe('refresh coordinator', () => {
  it('shares one refresh request between concurrent callers', async () => {
    let release: ((token: string) => void) | undefined
    const refresh = vi.fn(
      () => new Promise<string>((resolve) => {
        release = resolve
      }),
    )
    const coordinator = createRefreshCoordinator(refresh)

    const first = coordinator.refresh()
    const second = coordinator.refresh()
    release?.('new-access-token')

    await expect(first).resolves.toBe('new-access-token')
    await expect(second).resolves.toBe('new-access-token')
    expect(first).toBe(second)
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('clears the shared request after rejection', async () => {
    const refresh = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('refresh failed'))
      .mockResolvedValueOnce('recovered')
    const coordinator = createRefreshCoordinator(refresh)

    await expect(coordinator.refresh()).rejects.toThrow('refresh failed')
    await expect(coordinator.refresh()).resolves.toBe('recovered')
    expect(refresh).toHaveBeenCalledTimes(2)
  })
})
