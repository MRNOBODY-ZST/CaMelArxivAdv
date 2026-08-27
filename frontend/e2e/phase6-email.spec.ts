import { expect, test, type APIResponse, type Page } from '@playwright/test'
import { fileURLToPath } from 'node:url'

function requiredEnvironmentVariable(name: string): string {
  const value = globalThis.process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

const qaUser = requiredEnvironmentVariable('E2E_USER')
const qaPassword = requiredEnvironmentVariable('E2E_PASSWORD')
const runId = Date.now().toString(36)
const qaTemplateName = `Edge E2E Template ${runId}`
const qaCopiedTemplateName = `Edge E2E Copy ${runId}`
const qaSmtpName = `Edge E2E Mailpit ${runId}`
const qaImagePath = fileURLToPath(new URL('../../docs/design/dashboard-concept-mobile.png', import.meta.url))
let accessToken = ''
let qaTemplateId = ''
let qaCopiedTemplateId = ''
let qaSmtpId = ''

function authorizedHeaders(): Record<string, string> {
  return { Authorization: `Bearer ${accessToken}` }
}

async function requireOk(response: APIResponse): Promise<void> {
  if (!response.ok()) {
    throw new Error(`${response.status()} ${response.url()}: ${await response.text()}`)
  }
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ request }) => {
  const loginResponse = await request.post('/api/v1/auth/login', {
    data: { principal: qaUser, password: qaPassword },
  })
  await requireOk(loginResponse)
  accessToken = ((await loginResponse.json()) as { accessToken: string }).accessToken

  const smtpResponse = await request.post('/api/v1/smtp-accounts', {
    headers: authorizedHeaders(),
    data: {
      name: qaSmtpName,
      host: 'mailpit',
      port: 1025,
      tlsMode: 'PLAIN_LOCAL_ONLY',
      username: null,
      password: 'not-a-secret-mailpit-only',
      fromEmail: 'research@example.org',
      defaultFromName: 'Edge QA',
      replyTo: 'reply@example.org',
      perMinuteLimit: 10,
      perHourLimit: 100,
      perDayLimit: 1_000,
      perDomainHourLimit: 50,
      enabled: true,
    },
  })
  await requireOk(smtpResponse)
  qaSmtpId = ((await smtpResponse.json()) as { id: string }).id

  const templateResponse = await request.post('/api/v1/templates', {
    headers: authorizedHeaders(),
    data: {
      name: qaTemplateName,
      description: 'Disposable Edge acceptance fixture',
      status: 'DRAFT',
      content: {
        subjectTemplate: '研究邀请：{{paper_title}}',
        fromNameTemplate: '{{organization}} 团队',
        replyTo: 'reply@example.org',
        htmlContent: '<script>alert(1)</script><p>Phase6-AUTO-TEXT {{author_name}}，论文《{{paper_title}}》。</p><p><a href="{{paper_url}}">查看论文</a></p><p><a href="{{unsubscribe_url}}">退订</a></p>',
        textContent: 'stale text must be replaced',
        autoGenerateText: true,
      },
    },
  })
  await requireOk(templateResponse)
  qaTemplateId = ((await templateResponse.json()) as { id: string }).id
})

test.afterAll(async ({ request }) => {
  for (const templateId of [qaCopiedTemplateId, qaTemplateId]) {
    if (!templateId) continue
    const templateResponse = await request.get(`/api/v1/templates/${templateId}`, { headers: authorizedHeaders() })
    if (templateResponse.ok()) {
      const template = (await templateResponse.json()) as { lockVersion: number }
      const deleteResponse = await request.delete(`/api/v1/templates/${templateId}`, {
        headers: authorizedHeaders(),
        params: { expectedLockVersion: template.lockVersion },
      })
      expect(deleteResponse.ok()).toBe(true)
    }
  }
  if (qaSmtpId) {
    const smtpResponse = await request.get(`/api/v1/smtp-accounts/${qaSmtpId}`, { headers: authorizedHeaders() })
    if (smtpResponse.ok()) {
      const smtp = (await smtpResponse.json()) as { lockVersion: number }
      const deleteResponse = await request.delete(`/api/v1/smtp-accounts/${qaSmtpId}`, {
        headers: authorizedHeaders(),
        params: { expectedLockVersion: smtp.lockVersion },
      })
      expect(deleteResponse.ok()).toBe(true)
    }
  }
})

async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('用户名或邮箱').fill(qaUser)
  await page.getByLabel('密码').fill(qaPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = []
  page.on('console', (message) => {
    const text = message.text()
    const isTraceRecorderSandboxNoise = text.startsWith("Blocked script execution in 'about:srcdoc'")
      && text.includes("'allow-scripts' permission is not set")
    if (message.type() === 'error' && !isTraceRecorderSandboxNoise) errors.push(text)
  })
  page.on('pageerror', (error) => errors.push(error.message))
  return errors
}

test.beforeEach(async ({ page }) => login(page))

test('Edge desktop completes template preview, autosave, versions and Mailpit test send', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  const consoleErrors = collectConsoleErrors(page)

  await page.goto('/email/templates')
  await expect(page.getByRole('heading', { name: '邮件模板', exact: true })).toBeVisible()
  await page.getByText(qaTemplateName, { exact: true }).click()
  await expect(page).toHaveURL(new RegExp(`/email/templates/${qaTemplateId}$`))
  await expect(page.getByRole('heading', { name: qaTemplateName })).toBeVisible()

  const previewBody = page.frameLocator('iframe[title="邮件正文预览"]').locator('body')
  await expect(previewBody).toContainText('Ada Lovelace')
  await expect(page.locator('iframe[title="邮件正文预览"]')).toHaveAttribute('srcdoc', /^(?![\s\S]*(?:<script|javascript:|on\w+\s*=))[\s\S]*$/i)
  await page.getByRole('button', { name: '纯文本' }).click()
  await expect(page.getByRole('switch', { name: '自动从 HTML 生成纯文本' })).toBeChecked()
  await expect(page.locator('textarea:disabled')).toHaveValue(/Phase6-AUTO-TEXT/)
  await page.getByRole('button', { name: '富文本' }).click()
  await page.locator('input[type="file"]').setInputFiles(qaImagePath)
  await expect(page.getByText('图片已上传到私有资产库并插入正文。')).toBeVisible()
  const editorImage = page.getByLabel('邮件 HTML 正文编辑器').locator('img')
  await expect(editorImage).toHaveAttribute('src', /\/api\/v1\/template-assets\/.+signature=/)
  await expect.poll(() => editorImage.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true)
  await expect(page.getByText('已自动保存')).toBeVisible({ timeout: 10_000 })
  const previewImage = page.frameLocator('iframe[title="邮件正文预览"]').locator('img')
  await expect.poll(() => previewImage.evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBe(true)
  await page.getByRole('button', { name: '移动预览' }).click()
  await page.getByRole('button', { name: '深色背景预览' }).click()

  await page.getByRole('button', { name: '机构名称' }).click()
  await expect(page.getByText('已自动保存')).toBeVisible({ timeout: 10_000 })

  await page.getByRole('button', { name: '版本' }).click()
  await expect(page.getByRole('heading', { name: '版本历史' })).toBeVisible()
  await expect(page.getByRole('listitem').filter({ hasText: /版本 \d+/ }).first()).toBeVisible()
  await page.getByRole('button', { name: '关闭' }).click()

  await page.getByRole('button', { name: '测试发送' }).click()
  await expect(page.getByRole('heading', { name: '发送测试邮件', exact: true })).toBeVisible()
  await expect(page.getByText(/公网账户会真实发信/)).toBeVisible()
  await expect(page.getByLabel('SMTP 账户')).toHaveValue('')
  await expect(page.getByLabel('测试收件地址')).toHaveValue('')
  await expect(page.getByRole('button', { name: '发送测试', exact: true })).toBeDisabled()
  await page.getByLabel('SMTP 账户').selectOption(qaSmtpId)
  await expect(page.getByRole('button', { name: '发送测试', exact: true })).toBeDisabled()
  await page.getByLabel('测试收件地址').fill('qa@example.org')
  await page.getByRole('button', { name: '发送测试' }).click()
  await expect(page.getByText(/SMTP 已接受测试邮件/)).toBeVisible({ timeout: 10_000 })

  await page.getByRole('button', { name: '复制' }).click()
  await page.getByLabel('副本名称').fill(qaCopiedTemplateName)
  await page.getByRole('button', { name: '创建副本' }).click()
  await page.waitForURL((url) => url.pathname.startsWith('/email/templates/')
    && !url.pathname.endsWith(qaTemplateId))
  qaCopiedTemplateId = page.url().split('/').at(-1) ?? ''
  expect(qaCopiedTemplateId).not.toBe(qaTemplateId)
  await expect(page.getByRole('heading', { name: qaCopiedTemplateName })).toBeVisible()
  const copiedEditorImage = page.getByLabel('邮件 HTML 正文编辑器').locator('img')
  await expect(copiedEditorImage).toHaveAttribute(
    'src', new RegExp(`/api/v1/template-assets/${qaCopiedTemplateId}/.+signature=`),
  )
  await expect.poll(() => copiedEditorImage.evaluate(
    (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
  )).toBe(true)

  const sourceResponse = await page.request.get(`/api/v1/templates/${qaTemplateId}`, {
    headers: authorizedHeaders(),
  })
  await requireOk(sourceResponse)
  const source = (await sourceResponse.json()) as { lockVersion: number }
  const archiveSourceResponse = await page.request.delete(`/api/v1/templates/${qaTemplateId}`, {
    headers: authorizedHeaders(),
    params: { expectedLockVersion: source.lockVersion },
  })
  await requireOk(archiveSourceResponse)
  await page.reload()
  await expect(page.getByRole('heading', { name: qaCopiedTemplateName })).toBeVisible()
  await expect.poll(() => page.getByLabel('邮件 HTML 正文编辑器').locator('img').evaluate(
    (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
  )).toBe(true)
  await page.getByRole('button', { name: '机构名称' }).click()
  await expect(page.getByText('已自动保存')).toBeVisible({ timeout: 10_000 })

  const hasOverflow = await page.evaluate(() => document.documentElement.scrollWidth > globalThis.innerWidth)
  expect(hasOverflow).toBe(false)
  expect(consoleErrors).toEqual([])
})

test('Edge desktop manages the local-only SMTP account without exposing its password', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 })
  const consoleErrors = collectConsoleErrors(page)

  await page.goto('/admin/smtp-accounts')
  await expect(page).toHaveURL(/\/admin\/mail-accounts$/)
  await expect(page.getByRole('heading', { name: '邮件账户', exact: true })).toBeVisible()
  await expect(page.getByText('公网 SMTP 已启用安全策略')).toBeVisible()
  const smtpCard = page.getByTestId(`smtp-account-${qaSmtpId}`)
  await expect(smtpCard.getByRole('heading', { name: qaSmtpName })).toBeVisible()
  await expect(smtpCard.getByText(/密码已安全配置/)).toBeVisible()

  await smtpCard.getByRole('button', { name: '测试连接' }).click()
  await expect(page.getByText(/连接测试成功/)).toBeVisible({ timeout: 10_000 })
  await smtpCard.getByRole('button', { name: '编辑' }).click()
  await expect(page.getByPlaceholder('留空以保留原密码')).toHaveValue('')
  await page.getByRole('button', { name: '取消' }).click()

  expect(consoleErrors).toEqual([])
})

test('Edge mobile editor and navigation remain usable without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const consoleErrors = collectConsoleErrors(page)

  await page.goto('/email/templates')
  await page.getByText(qaCopiedTemplateName, { exact: true }).click()
  await expect(page.getByRole('heading', { name: qaCopiedTemplateName })).toBeVisible()
  await expect(page.getByRole('button', { name: '桌面预览' })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > globalThis.innerWidth)).toBe(false)

  await page.getByTestId('mobile-navigation').click()
  await expect(page.getByRole('navigation', { name: '主导航' })).toBeVisible()
  await page.getByRole('button', { name: '系统管理' }).click()
  await page.getByRole('link', { name: '邮件账户' }).click()
  await expect(page.getByRole('heading', { name: '邮件账户', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth > globalThis.innerWidth)).toBe(false)
  expect(consoleErrors).toEqual([])
})
