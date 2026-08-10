import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const vis = vi.hoisted(() => ({
  networkOptions: null as Record<string, unknown> | null,
  handlers: new Map<string, (payload?: unknown) => void>(),
  fit: vi.fn(),
  focus: vi.fn(),
  selectNodes: vi.fn(),
  stabilize: vi.fn(),
  startSimulation: vi.fn(),
  stopSimulation: vi.fn(),
  setOptions: vi.fn(),
  destroy: vi.fn(),
  Network: vi.fn(),
}))

vi.mock('vis-network/standalone', () => ({
  DataSet: class DataSet {
    constructor(public readonly values: unknown[]) {}
  },
  Network: class Network {
    constructor(_element: HTMLElement, _data: unknown, options: Record<string, unknown>) {
      vis.Network(_element, _data, options)
      vis.networkOptions = options
      return {
        fit: vis.fit,
        focus: vis.focus,
        selectNodes: vis.selectNodes,
        stabilize: vis.stabilize,
        startSimulation: vis.startSimulation,
        stopSimulation: vis.stopSimulation,
        setOptions: vis.setOptions,
        destroy: vis.destroy,
        on: (event: string, handler: (payload?: unknown) => void) => vis.handlers.set(event, handler),
      }
    }
  },
}))

import AuthorNetworkGraph from '@/modules/analytics/AuthorNetworkGraph.vue'

const nodes = [
  { id: 'author-a', label: 'Alice Zhang', paperCount: 4, collaboratorCount: 1, contactCount: 2 },
  { id: 'author-b', label: 'Bob Li', paperCount: 3, collaboratorCount: 1, contactCount: 0 },
]
const edges = [{ source: 'author-a', target: 'author-b', sharedPaperCount: 2 }]

describe('AuthorNetworkGraph', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vis.handlers.clear()
    vis.networkOptions = null
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: false,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })))
  })

  afterEach(() => vi.unstubAllGlobals())

  it('runs force physics and exposes selection, layout, and lifecycle controls', async () => {
    const wrapper = mount(AuthorNetworkGraph, { props: { nodes, edges } })

    await vi.waitFor(() => expect(vis.Network).toHaveBeenCalledOnce())
    expect((vis.networkOptions?.physics as { solver: string }).solver).toBe('forceAtlas2Based')

    await wrapper.get('[aria-label="暂停物理计算"]').trigger('click')
    expect(vis.stopSimulation).toHaveBeenCalledOnce()

    await wrapper.get('select[aria-label="搜索作者"]').setValue('author-a')
    expect(vis.selectNodes).toHaveBeenCalledWith(['author-a'])
    expect(vis.focus).toHaveBeenCalledWith('author-a', expect.objectContaining({ animation: expect.any(Object) }))
    expect(wrapper.text()).toContain('Alice Zhang')
    expect(wrapper.text()).toContain('共同论文 2')

    await wrapper.get('[aria-label="重新计算关系布局"]').trigger('click')
    expect(vis.stabilize).toHaveBeenCalledWith(500)
    await wrapper.get('[aria-label="适应画布"]').trigger('click')
    expect(vis.fit).toHaveBeenCalled()

    wrapper.unmount()
    expect(vis.destroy).toHaveBeenCalledOnce()
  })

  it('renders an explicit empty state without creating a network', () => {
    const wrapper = mount(AuthorNetworkGraph, { props: { nodes: [], edges: [] } })

    expect(wrapper.text()).toContain('当前筛选范围没有可展示的作者关系')
    expect(vis.Network).not.toHaveBeenCalled()
  })

  it('honors reduced motion until the user explicitly enables physics', async () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })))
    const wrapper = mount(AuthorNetworkGraph, { props: { nodes, edges } })

    await vi.waitFor(() => expect(vis.Network).toHaveBeenCalledOnce())
    expect((vis.networkOptions?.physics as { enabled: boolean }).enabled).toBe(false)
    await wrapper.get('[aria-label="启用物理计算"]').trigger('click')
    expect(vis.setOptions).toHaveBeenCalledWith({ physics: { enabled: true } })
    expect(vis.startSimulation).toHaveBeenCalledOnce()
  })
})
