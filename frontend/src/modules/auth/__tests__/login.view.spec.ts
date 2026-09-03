import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import LoginView from '@/modules/auth/LoginView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} }),
  useRouter: () => ({ replace: vi.fn() }),
}))

vi.mock('@/modules/auth/auth.store', () => ({
  useAuthStore: () => ({ busy: false, user: null, login: vi.fn() }),
}))

describe('LoginView', () => {
  it('describes the guarded production delivery workflow truthfully', () => {
    const wrapper = mount(LoginView)

    expect(wrapper.text()).toContain('审核、安全实流与正式发送均有独立门禁')
    expect(wrapper.text()).toContain('SMTP 接受不代表最终送达')
    expect(wrapper.text()).not.toContain('正式活动发送流程仍在开发中')
  })
})
