import { config } from '@vue/test-utils'

config.global.stubs = {
  teleport: true,
}

class TestResizeObserver implements ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

globalThis.ResizeObserver = TestResizeObserver
