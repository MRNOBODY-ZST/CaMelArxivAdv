import { expect, test, type APIResponse, type Page } from '@playwright/test'

function requiredEnvironmentVariable(name: string): string {
  const value = globalThis.process.env[name]
  if (!value) throw new Error(`${name} is required`)
  return value
}

const qaUser = requiredEnvironmentVariable('E2E_USER')
const qaPassword = requiredEnvironmentVariable('E2E_PASSWORD')
let accessToken = ''
let campaignId = ''

async function requireOk(response: APIResponse): Promise<void> {
  if (!response.ok()) throw new Error(`${response.status()} ${response.url()}: ${await response.text()}`)
}

async function login(page: Page): Promise<void> {
  await page.goto('/login')
  await page.getByLabel('用户名或邮箱').fill(qaUser)
  await page.getByLabel('密码').fill(qaPassword)
  await page.getByRole('button', { name: '登录' }).click()
  await expect(page).toHaveURL(/\/$/)
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(async ({ request }) => {
  const loginResponse = await request.post('/api/v1/auth/login', {
    data: { principal: qaUser, password: qaPassword },
  })
  await requireOk(loginResponse)
  accessToken = ((await loginResponse.json()) as { accessToken: string }).accessToken
  const campaignsResponse = await request.get('/api/v1/campaigns?page=1&pageSize=1', {
    headers: { Authorization: `Bearer ${accessToken}` },
  })
  await requireOk(campaignsResponse)
  const campaignPage = (await campaignsResponse.json()) as { items: Array<{ id: string }> }
  campaignId = campaignPage.items[0]?.id ?? ''
})

test.beforeEach(async ({ page }) => login(page))

test('Edge exposes separate evidence tabs and truthful campaign operations', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/email/deliveries')
  await expect(page.getByRole('heading', { name: '发送记录', exact: true })).toBeVisible()
  await expect(page.getByRole('tab', { name: '测试邮件记录' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '安全实流' })).toBeVisible()
  await expect(page.getByRole('tab', { name: '活动发送记录' })).toBeVisible()
  await expect(page.getByText('SMTP 已接受不等于最终送达')).toBeVisible()
  await expect(page.getByText('回传不等于确认人工阅读')).toBeVisible()

  test.skip(!campaignId, 'This environment has no campaign fixture to inspect')
  await page.goto(`/email/campaigns/${campaignId}`)
  await expect(page.getByRole('heading', { name: '从内容到回传' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '正式发送预检' })).toBeVisible()
  await expect(page.getByRole('heading', { name: '安全实流', exact: true })).toBeVisible()
  await expect(page.getByText('SAFETY_REDIRECT')).toBeVisible()
  const body = await page.locator('body').innerText()
  expect(body).not.toMatch(/\/api\/v1\/(?:t|u)\//)
  expect(await page.evaluate(() => document.documentElement.scrollWidth > globalThis.innerWidth)).toBe(false)
})
