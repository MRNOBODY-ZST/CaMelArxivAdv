export interface RefreshCoordinator {
  refresh: () => Promise<string>
}

export function createRefreshCoordinator(
  requestRefresh: () => Promise<string>,
): RefreshCoordinator {
  let pending: Promise<string> | undefined

  return {
    refresh() {
      if (!pending) {
        pending = requestRefresh().finally(() => {
          pending = undefined
        })
      }
      return pending
    },
  }
}
