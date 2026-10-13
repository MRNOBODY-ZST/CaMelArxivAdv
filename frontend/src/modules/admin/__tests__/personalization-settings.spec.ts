import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { apiClient } from '@/api/client'
import PersonalizationSettingsPanel from '@/modules/admin/PersonalizationSettingsPanel.vue'
import SystemSettingsView from '@/modules/admin/SystemSettingsView.vue'
import {
  adminApi,
  type PersonalizationSettings,
} from '@/modules/admin/admin.api'
import { useAuthStore } from '@/modules/auth/auth.store'
import { campaignsApi } from '@/modules/campaigns/campaigns.api'

vi.mock('@/api/client', () => ({ apiClient: { get: vi.fn(), post: vi.fn(), put: vi.fn() } }))
vi.mock('@/modules/campaigns/campaigns.api', () => ({
  campaignsApi: { runtimeStatus: vi.fn() },
  campaignErrorMessage: (_error: unknown, fallback: string) => fallback,
}))

const saved: PersonalizationSettings = {
  version: 4,
  enabled: true,
  provider: 'openai',
  model: 'gpt-5.6-luna',
  apiOrigin: 'https://api.openai.com/v1',
  authScheme: 'bearer',
  keyConfigured: true,
  source: 'DATABASE',
  updatedAt: '2026-09-22T08:30:00Z',
}

describe('personalization settings API', () => {
  beforeEach(() => vi.clearAllMocks())

  it('uses the safe admin endpoints without adding a secret to reads', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: saved })
    vi.mocked(apiClient.put).mockResolvedValue({ data: { ...saved, version: 5 } })
    vi.mocked(apiClient.post).mockResolvedValue({ data: {
      success: true, message: '连接成功', provider: 'openai', model: saved.model, latencyMs: 37,
    } })

    expect(await adminApi.getPersonalizationSettings()).toEqual(saved)
    await adminApi.updatePersonalizationSettings({
      version: 4, enabled: true, provider: 'openai', model: saved.model,
      apiOrigin: saved.apiOrigin, authScheme: 'bearer',
    })
    await adminApi.testPersonalizationSettings({
      version: 4, enabled: true, provider: 'openai', model: saved.model,
      apiOrigin: saved.apiOrigin, authScheme: 'bearer',
    })

    expect(apiClient.get).toHaveBeenCalledWith('/system/personalization')
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.not.objectContaining({ apiKey: expect.anything() }))
    expect(apiClient.post).toHaveBeenCalledWith('/system/personalization/test', expect.not.objectContaining({ apiKey: expect.anything() }))
  })
})

describe('personalization settings panel', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(apiClient.get).mockResolvedValue({ data: saved })
    vi.mocked(apiClient.put).mockResolvedValue({ data: { ...saved, version: 5 } })
    vi.mocked(apiClient.post).mockResolvedValue({ data: {
      success: true, message: '模型响应正常', provider: 'openai', model: saved.model, latencyMs: 41,
    } })
  })

  it('loads safe persisted values and never renders or stores an existing key', async () => {
    localStorage.setItem('unrelated', 'keep')
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()

    expect(wrapper.get<HTMLInputElement>('#personalization-model').element.value).toBe('gpt-5.6-luna')
    expect(wrapper.text()).toContain('已配置')
    expect(wrapper.text()).toContain('数据库')
    expect(wrapper.get<HTMLInputElement>('#personalization-api-key').element.value).toBe('')
    expect(JSON.stringify(localStorage)).not.toContain('gpt-5.6-luna')
    expect(localStorage.getItem('unrelated')).toBe('keep')
  })

  it('omits a blank key for an unchanged normalized destination and clears a new key after save', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-api-origin').setValue('https://api.openai.com/v1/')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenLastCalledWith('/system/personalization', {
      version: 4, enabled: true, provider: 'openai', model: 'gpt-5.6-luna',
      apiOrigin: 'https://api.openai.com/v1', authScheme: 'bearer',
    })

    await wrapper.get('#personalization-api-key').setValue('sk-new-secret')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenLastCalledWith('/system/personalization', expect.objectContaining({ apiKey: 'sk-new-secret', version: 5 }))
    expect(wrapper.get<HTMLInputElement>('#personalization-api-key').element.value).toBe('')
  })

  it('requires a new key after provider or origin changes and rejects unsafe origins', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()

    await wrapper.get('#personalization-provider').setValue('anthropic')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('更换服务商或 API Origin 时必须输入新密钥')
    expect(apiClient.put).not.toHaveBeenCalled()

    await wrapper.get('#personalization-api-origin').setValue('http://127.0.0.1:8080/v1')
    await wrapper.get('#personalization-api-key').setValue('new-key')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入公网 HTTPS API Origin')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it.each([
    'https://fca.example.com/v1',
    'https://fda.example.com/v1',
    'https://feedback.example.com/v1',
    'https://fe80.example.com/v1',
    'https://feb.example.com/v1',
    'https://8.8.8.8/v1',
    'https://[2606:4700:4700::1111]/v1',
  ])('accepts public DNS and IP destination %s', async (origin) => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, apiOrigin: origin } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).not.toContain('请输入公网 HTTPS API Origin')
    expect(apiClient.put).toHaveBeenCalledOnce()
  })

  it.each([
    'https://user:password@example.com/v1',
    'https://example.com/v1?debug=true',
    'https://example.com/v1#fragment',
    'https://localhost/v1',
    'https://api.localhost/v1',
    'https://api.local/v1',
    'https://intranet/v1',
    'https://example.com:0/v1',
    'https://example.com/v1?',
    'https://example.com/v1#',
    'https://example.com/a/../v1',
    'https://example.com/%76%31',
    'https://0.0.0.0/v1',
    'https://127.0.0.1/v1',
    'https://10.0.0.1/v1',
    'https://100.64.0.1/v1',
    'https://172.16.0.1/v1',
    'https://192.168.0.1/v1',
    'https://169.254.1.2/v1',
    'https://192.88.99.1/v1',
    'https://192.0.2.1/v1',
    'https://198.18.0.1/v1',
    'https://198.19.0.1/v1',
    'https://198.51.100.1/v1',
    'https://203.0.113.1/v1',
    'https://224.0.0.1/v1',
    'https://240.0.0.1/v1',
    'https://[::]/v1',
    'https://[::1]/v1',
    'https://[fc00::1]/v1',
    'https://[fd00::1]/v1',
    'https://[fe80::1]/v1',
    'https://[fec0::1]/v1',
    'https://[::ffff:192.168.0.1]/v1',
    'https://[64:ff9b::1]/v1',
    'https://[100::1]/v1',
    'https://[2001::1]/v1',
    'https://[2001:20::1]/v1',
    'https://[2001:db8::1]/v1',
    'https://[2002::1]/v1',
    'https://[3fff::1]/v1',
    'https://[ff00::1]/v1',
  ])('rejects unsafe destination %s', async (origin) => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, apiOrigin: origin } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入公网 HTTPS API Origin')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('treats a root origin as /v1 for key reuse while retaining explicit prefixes', async () => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, apiOrigin: 'https://models.example.com/v1' } })
    const root = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await root.get('#personalization-api-origin').setValue('https://models.example.com')
    await root.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.not.objectContaining({ apiKey: expect.anything() }))

    vi.clearAllMocks()
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, apiOrigin: 'https://models.example.com/proxy/openai' } })
    vi.mocked(apiClient.put).mockResolvedValue({ data: saved })
    const prefix = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await prefix.get('#personalization-api-origin').setValue('https://models.example.com/proxy/openai/')
    await prefix.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.not.objectContaining({ apiKey: expect.anything() }))
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.objectContaining({ apiOrigin: 'https://models.example.com/proxy/openai' }))
  })

  it('allows an unchanged disabled unconfigured destination to save but requires a key to test it', async () => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, keyConfigured: false, source: 'ENVIRONMENT' } })
    vi.mocked(apiClient.put).mockResolvedValueOnce({ data: { ...saved, enabled: false, keyConfigured: false } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('[role="switch"]').trigger('click')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.not.objectContaining({ apiKey: expect.anything() }))

    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    expect(wrapper.text()).toContain('连接测试需要 API 密钥')
    expect(apiClient.post).not.toHaveBeenCalled()
  })

  it('requires a model before saving', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-model').setValue('  ')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.text()).toContain('请输入模型名称')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('normalizes default HTTPS ports and root paths in requests without changing key ownership', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-api-origin').setValue(' HTTPS://API.OPENAI.COM:443/ ')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.objectContaining({ apiOrigin: 'https://api.openai.com/v1' }))
    expect(apiClient.put).toHaveBeenCalledWith('/system/personalization', expect.not.objectContaining({ apiKey: expect.anything() }))
  })

  it.each(['provider', 'api-origin'])('requires a new key when disabled settings change %s', async (field) => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, enabled: false, keyConfigured: false } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get(`#personalization-${field}`).setValue(field === 'provider' ? 'anthropic' : 'https://models.example.com/v1')
    await wrapper.get('form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('必须输入新密钥')
  })

  it('requires a configured or new key when generation is enabled', async () => {
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, keyConfigured: false } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    expect(apiClient.put).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('当前目标尚未配置密钥')
  })

  it('tests the current draft with a small call and keeps unsaved fields intact', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-model').setValue('gpt-5.6-sol')
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()

    expect(apiClient.post).toHaveBeenCalledWith('/system/personalization/test', expect.objectContaining({ model: 'gpt-5.6-sol' }))
    expect(wrapper.text()).toContain('模型响应正常')
    expect(wrapper.text()).toContain('41 ms')
    expect(wrapper.get<HTMLInputElement>('#personalization-model').element.value).toBe('gpt-5.6-sol')
    expect(wrapper.text()).toContain('不会发送邮件')
  })

  it('clears save and connection-test feedback when their payload becomes stale', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('模型响应正常')

    await wrapper.get('#personalization-model').setValue('edited-model')
    expect(wrapper.text()).not.toContain('模型响应正常')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('设置已保存')
    expect(wrapper.text()).not.toContain('模型响应正常')

    await wrapper.get('#personalization-auth-scheme').setValue('x-api-key')
    expect(wrapper.text()).not.toContain('设置已保存')
  })

  it.each([
    ['model', 'another-model'], ['provider', 'anthropic'], ['api-origin', 'https://models.example.com'],
    ['auth-scheme', 'x-api-key'], ['api-key', 'new-secret'], ['enabled', ''],
  ])('invalidates successful save and test feedback after editing %s', async (field, value) => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('设置已保存')
    expect(wrapper.text()).toContain('模型响应正常')
    if (field === 'enabled') await wrapper.get('[role="switch"]').trigger('click')
    else await wrapper.get(`#personalization-${field}`).setValue(value)
    expect(wrapper.text()).not.toContain('设置已保存')
    expect(wrapper.text()).not.toContain('模型响应正常')
  })

  it('clears an earlier connection result when saving even an unchanged draft', async () => {
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('模型响应正常')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('设置已保存')
    expect(wrapper.text()).not.toContain('模型响应正常')
  })

  it('renders sanitized unsuccessful test results and ordinary operation errors', async () => {
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: {
      success: false, message: '提供商暂时不可用', provider: 'openai', model: saved.model, latencyMs: 52,
    } })
    const warning = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await warning.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(warning.text()).toContain('提供商暂时不可用')
    expect(warning.text()).not.toContain('raw provider body')

    vi.mocked(apiClient.put).mockRejectedValueOnce({ isAxiosError: true, response: { status: 500, data: { detail: '保存服务不可用' } } })
    await warning.get('form').trigger('submit')
    await flushPromises()
    expect(warning.text()).toContain('保存服务不可用')

    vi.mocked(apiClient.post).mockRejectedValueOnce({ isAxiosError: true, response: { status: 502, data: { detail: '连接测试不可用' } } })
    await warning.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(warning.text()).toContain('连接测试不可用')
  })

  it('locks mutations after a conflict until explicit reload restores persisted values', async () => {
    vi.mocked(apiClient.put).mockRejectedValueOnce({ isAxiosError: true, response: { status: 409, data: { detail: '版本冲突' } } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-model').setValue('stale-model')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('设置已被其他管理员修改')
    expect(wrapper.get('[data-testid="save-personalization"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('[data-testid="test-personalization"]').attributes()).toHaveProperty('disabled')

    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: { ...saved, version: 8, model: 'fresh-model' } })
    await wrapper.get('[data-testid="reload-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('#personalization-model').element.value).toBe('fresh-model')
    expect(wrapper.get('[data-testid="save-personalization"]').attributes()).not.toHaveProperty('disabled')
  })

  it('locks both actions after a test conflict and keeps the lock if explicit reload fails', async () => {
    vi.mocked(apiClient.post).mockRejectedValueOnce({ isAxiosError: true, response: { status: 409, data: { detail: '版本冲突' } } })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="test-personalization"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('[data-testid="save-personalization"]').attributes()).toHaveProperty('disabled')
    expect(apiClient.get).toHaveBeenCalledOnce()
    expect(apiClient.post).toHaveBeenCalledOnce()
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('offline'))
    await wrapper.get('[data-testid="reload-personalization"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('配置加载失败')
    expect(wrapper.get('[data-testid="save-personalization"]').attributes()).toHaveProperty('disabled')
    expect(wrapper.get('[data-testid="test-personalization"]').attributes()).toHaveProperty('disabled')
    expect(apiClient.put).not.toHaveBeenCalled()
  })

  it('disables mutations after initial-load failure and prevents double submit while saving', async () => {
    vi.mocked(apiClient.get).mockRejectedValueOnce(new Error('offline'))
    const failed = mount(PersonalizationSettingsPanel)
    await flushPromises()
    expect(failed.text()).toContain('配置加载失败')
    expect(failed.get('[data-testid="save-personalization"]').attributes()).toHaveProperty('disabled')
    expect(failed.get('[data-testid="test-personalization"]').attributes()).toHaveProperty('disabled')

    let resolveSave: ((value: { data: PersonalizationSettings }) => void) | undefined
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: saved })
    vi.mocked(apiClient.put).mockReturnValueOnce(new Promise((resolve) => { resolveSave = resolve }))
    const saving = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await saving.get('form').trigger('submit')
    await saving.get('form').trigger('submit')
    expect(apiClient.put).toHaveBeenCalledTimes(1)
    expect(saving.get('[data-testid="save-personalization"]').attributes('aria-busy')).toBe('true')
    resolveSave?.({ data: { ...saved, version: 5 } })
    await flushPromises()
  })

  it('prevents duplicate connection tests and reloads while each action is busy', async () => {
    let resolveTest: ((value: { data: { success: boolean; message: string; provider: string; model: string; latencyMs: number } }) => void) | undefined
    vi.mocked(apiClient.post).mockReturnValueOnce(new Promise((resolve) => { resolveTest = resolve }))
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    await wrapper.get('[data-testid="test-personalization"]').trigger('click')
    expect(apiClient.post).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="test-personalization"]').attributes('aria-busy')).toBe('true')
    resolveTest?.({ data: { success: true, message: 'ok', provider: 'openai', model: saved.model, latencyMs: 1 } })
    await flushPromises()

    let resolveReload: ((value: { data: PersonalizationSettings }) => void) | undefined
    vi.mocked(apiClient.get).mockReturnValueOnce(new Promise((resolve) => { resolveReload = resolve }))
    await wrapper.get('[data-testid="reload-personalization"]').trigger('click')
    await wrapper.get('[data-testid="reload-personalization"]').trigger('click')
    expect(apiClient.get).toHaveBeenCalledTimes(2)
    expect(wrapper.get('[data-testid="reload-personalization"]').attributes('aria-busy')).toBe('true')
    resolveReload?.({ data: saved })
    await flushPromises()
  })

  it('reloads the server-persisted result after a successful save', async () => {
    let server = saved
    vi.mocked(apiClient.get).mockImplementation(async () => ({ data: server }))
    vi.mocked(apiClient.put).mockImplementation(async (_url, request) => {
      server = { ...server, ...(request as object), version: server.version + 1, keyConfigured: true }
      return { data: server }
    })
    const wrapper = mount(PersonalizationSettingsPanel)
    await flushPromises()
    await wrapper.get('#personalization-model').setValue('persisted-model')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    wrapper.unmount()

    const remounted = mount(PersonalizationSettingsPanel)
    await flushPromises()
    expect(remounted.get<HTMLInputElement>('#personalization-model').element.value).toBe('persisted-model')
  })

  it('shows the form and accurate key-storage copy to a system manager', async () => {
    vi.mocked(campaignsApi.runtimeStatus).mockResolvedValue({
      personalizationEnabled: true, generationReady: true, provider: 'openai', model: saved.model,
      rayConfigured: true, kafkaConfigured: true, liveSmtpAllowed: false, publicMailboxAllowed: false,
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: { id: 'admin', username: 'admin', displayName: 'Admin', roles: ['ADMIN'], permissions: ['system:manage'], mustChangePassword: false },
    })
    const wrapper = mount(SystemSettingsView, { global: { plugins: [pinia] } })
    await flushPromises()
    expect(wrapper.find('#personalization-model').exists()).toBe(true)
    expect(wrapper.text()).toContain('加密数据库存储')
    expect(wrapper.text()).not.toContain('密钥只通过运行环境注入')

    let resolveRuntime: ((value: Awaited<ReturnType<typeof campaignsApi.runtimeStatus>>) => void) | undefined
    vi.mocked(campaignsApi.runtimeStatus).mockReturnValueOnce(new Promise((resolve) => { resolveRuntime = resolve }))
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(campaignsApi.runtimeStatus).toHaveBeenCalledTimes(2)
    await wrapper.get('#personalization-model').setValue('unsaved-after-save')
    resolveRuntime?.({
      personalizationEnabled: true, generationReady: true, provider: 'openai', model: 'refreshed-runtime-model',
      rayConfigured: true, kafkaConfigured: true, liveSmtpAllowed: false, publicMailboxAllowed: false,
    })
    await flushPromises()
    expect(wrapper.text()).toContain('refreshed-runtime-model')
    expect(wrapper.get<HTMLInputElement>('#personalization-model').element.value).toBe('unsaved-after-save')
    expect(apiClient.get).toHaveBeenCalledOnce()
    expect(wrapper.text()).not.toContain('设置已保存')
  })

  it('does not read or expose editable settings to a user without system:manage', async () => {
    vi.mocked(campaignsApi.runtimeStatus).mockResolvedValue({
      personalizationEnabled: true, generationReady: true, provider: 'openai', model: saved.model,
      rayConfigured: true, kafkaConfigured: true, liveSmtpAllowed: false, publicMailboxAllowed: false,
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    useAuthStore().acceptSession({
      accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
      user: { id: 'reader', username: 'reader', displayName: 'Reader', roles: ['DATA_ANALYST'], permissions: [], mustChangePassword: false },
    })
    const wrapper = mount(SystemSettingsView, { global: { plugins: [pinia] } })
    await flushPromises()
    expect(wrapper.find('#personalization-model').exists()).toBe(false)
    expect(apiClient.get).not.toHaveBeenCalled()
  })
})
