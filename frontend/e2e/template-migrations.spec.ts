import { expect, test, type Page, type TestInfo } from '@playwright/test'
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createServer, type ViteDevServer } from 'vite'
import type { MigrationCommand, MigrationResult } from '../src/modules/campaigns/template-migrations.types'

// Real application/router/controls/reconciliation, with an isolated synthetic HTTP boundary.
// No login, SMTP, source, partner, approval or start endpoint is available in this fixture.
test.use({ video: 'off' })
test.setTimeout(90_000)
let server: ViteDevServer
let origin: string
const source = '20000000-0000-0000-0000-000000000001'
const secondSource = '20000000-0000-0000-0000-000000000002'
const thirdSource = '20000000-0000-0000-0000-000000000003'
const template = '30000000-0000-0000-0000-000000000001'
const version = '40000000-0000-0000-0000-000000000001'
const recoveryKey = 'camel.campaign-template-migration.reconciliation.v1'
const stamp = '2026-01-01T00:00:00Z'
const recipientId = (index: number) => `50000000-0000-0000-0000-${String(index).padStart(12, '0')}`
const html = '<!doctype html><html><body><h1>Fictional research invitation</h1><p>Dear Synthetic Researcher, this is an inert preview.</p></body></html>'

test.beforeAll(async () => {
  server = await createServer({ server: { host: '127.0.0.1', port: 0, strictPort: true } })
  await server.listen()
  const address = server.httpServer!.address()
  if (!address || typeof address === 'string') throw new Error('Owned fixture did not bind TCP')
  origin = `http://127.0.0.1:${address.port}`
})
test.afterAll(async () => { await server?.close() })

test('actual curated outreach HTML renders safely at desktop and mobile widths', async ({ page, browser }, info) => {
  const relativePath = 'docs/email/camel-hub-auto-outreach-preview.html'
  const bytes = readFileSync(resolve(process.cwd(), '..', relativePath))
  const url = `${origin}/curated-outreach-preview`
  const requests: string[] = [], unexpected: string[] = [], consoleIssues: string[] = []
  page.on('pageerror', error => consoleIssues.push(error.message))
  page.on('console', message => {
    if (['error', 'warning'].includes(message.type())) consoleIssues.push(message.text())
  })
  await page.context().route('**/*', async route => {
    const request = route.request()
    requests.push(`${request.method()} ${request.url()}`)
    if (request.method() === 'GET' && request.url() === url && request.isNavigationRequest()) {
      await route.fulfill({ contentType: 'text/html; charset=utf-8', body: bytes })
    } else {
      unexpected.push(`${request.method()} ${request.url()}`)
      await route.abort()
    }
  })
  const views = []
  for (const viewport of [{ width: 1280, height: 900 }, { width: 390, height: 844 }]) {
    await page.setViewportSize(viewport)
    await page.goto(url)
    expect(page.url()).toBe(url)
    await expect(page).toHaveTitle('Synthetic outreach layout preview')
    await expect(page.getByRole('heading', { name: 'A research conversation, together.' })).toBeVisible()
    await expect(page.getByText('Hello Alex Example,', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Fictional Study: Reliable Research Tools' })).toBeVisible()
    await expect(page.locator('script, iframe, form, img, vite-error-overlay')).toHaveCount(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
    await screenshot(page, info, `curated-${viewport.width}-top`)
    await page.getByRole('link', { name: 'Unsubscribe', exact: true }).scrollIntoViewIfNeeded()
    await expect(page.getByRole('heading', { name: 'A short reply is a good start' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Camel Hub website' })).toBeVisible()
    await screenshot(page, info, `curated-${viewport.width}-footer`)
    views.push({ viewport, title: await page.title(), text: await page.locator('body').innerText(),
      links: await page.locator('a').evaluateAll(links => links.map(link => ({ text: link.textContent, href: link.getAttribute('href') }))) })
  }
  expect(requests).toEqual([`GET ${url}`, `GET ${url}`])
  expect(unexpected).toEqual([])
  expect(consoleIssues).toEqual([])
  const evidencePath = info.outputPath('curated-html-evidence.json')
  writeFileSync(evidencePath, JSON.stringify({
    relativePath, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex'),
    browser: browser.version(), url, requests, unexpected, consoleIssues, views,
  }, null, 2))
  await info.attach('curated-html-evidence', { contentType: 'application/json', path: evidencePath })
})

async function fixture(page: Page, options: { reader?: boolean; outcome?: 'timeout' | 'conflict' | 'forbidden' | 'blocked' } = {}) {
  const posts: { source: string; command: MigrationCommand }[] = []
  const previews: { source: string; recipientIds: string[] }[] = []
  const readbacks: string[] = []
  const unexpected: string[] = []
  const consoleErrors: string[] = []
  const drafts: MigrationResult[] = []
  let readbackStatus = 404
  const actor = { id: '10000000-0000-0000-0000-000000000001', username: 'synthetic', displayName: 'Synthetic reviewer', roles: [], permissions: options.reader ? ['campaign:read'] : ['campaign:read', 'campaign:create', 'template:read'], mustChangePassword: false }
  const history = { status: 'PAUSED', attempts: ['SMTP_ACCEPTED', 'OUTCOME_UNKNOWN'], trial: 'COMPLETED', feedback: ['synthetic-bounce'], body: 'immutable original body' }
  const originalHistory = JSON.stringify(history)
  page.on('pageerror', error => consoleErrors.push(error.message))
  page.on('console', message => {
    if (message.type() === 'error' && !message.text().includes('Failed to load resource')) consoleErrors.push(message.text())
    if (message.type() === 'warning') consoleErrors.push(`warning: ${message.text()}`)
  })
  const result = (id: string, command: MigrationCommand): MigrationResult => ({ id: id.replace('20000000', '60000000'), sourceCampaignId: id, targetCampaignId: id.replace('20000000', '70000000'), targetTemplateId: template, targetTemplateVersionId: version, selectedCount: command.recipientIds.length, migratedCount: command.recipientIds.length, status: 'MIGRATED', reasons: [] })
  await page.context().route('**/*', async route => {
    const request = route.request(), url = new URL(request.url())
    if (url.origin !== origin) { unexpected.push('external request'); await route.abort(); return }
    if (!url.pathname.startsWith('/api/')) { await route.continue(); return }
    const respond = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
    const path = url.pathname.replace('/api/v1', '')
    if (path === '/auth/refresh') return respond({ accessToken: 'synthetic-memory-only', tokenType: 'Bearer', expiresInSeconds: 600, user: actor })
    if (path === '/auth/me') return respond(actor)
    if (path === '/system/runtime') return respond({ personalizationEnabled: false, provider: 'disabled', model: 'disabled', rayConfigured: false, kafkaConfigured: false, liveSmtpAllowed: false, publicMailboxAllowed: false, generationReady: false })
    if (path === '/deliveries') return respond({ items: [], page: 1, pageSize: 10, total: 0, totalPages: 0 })
    if (path === '/templates') return respond({ items: [{ id: template, name: 'Synthetic immutable template', status: 'ACTIVE', lockVersion: 4, currentVersion: 1, htmlContent: html }], page: 1, pageSize: 100, total: 1, totalPages: 1 })
    if (path === `/templates/${template}/versions`) return respond([{ id: version, versionNumber: 1, htmlContent: html, createdAt: stamp }])
    const match = path.match(/^\/campaigns\/([^/]+)(.*)$/)
    if (!match) { unexpected.push(`${request.method()} ${path}`); return respond({ detail: 'Fixture route unavailable' }, 501) }
    const id = match[1]!, suffix = match[2]!, total = id === source ? 40 : id === secondSource ? 70 : 27
    if (!suffix && request.method() === 'GET') return respond({
      id, name: id === source ? 'Synthetic paused source A' : 'Synthetic paused source B', purpose: 'Isolated migration proof', status: history.status,
      templateId: template, templateName: 'Original frozen template', templateVersion: 1, segmentId: 'segment', segmentName: 'Synthetic selection', smtpAccountId: 'inactive', smtpName: 'Inactive fixture', mailboxAccountId: null,
      fromName: 'Synthetic', fromEmail: 'sender@example.invalid', replyTo: 'reply@example.invalid', trackingOpensEnabled: false, trackingClicksEnabled: false,
      generationStatus: 'COMPLETED', generationProvider: null, generationModel: null, generationJobId: null,
      recipientCounts: { queued: 0, running: 0, generated: total, failed: 0, total }, deliveryCounts: { queued: total, connecting: 0, smtpAccepted: 1, temporaryFailure: 0, permanentFailure: 0, bounced: 1, suppressed: 0, unsubscribed: 0, canceled: 0, outcomeUnknown: 1, total: total + 2 }, lockVersion: 3,
      submittedForReviewAt: null, approvedAt: null, approvedBy: null, rejectedAt: null, rejectedBy: null, rejectionReason: null, scheduledAt: null, startedAt: null, completedAt: null, canceledAt: null, statusChangedAt: stamp, statusChangedBy: null, createdAt: stamp, updatedAt: stamp,
    })
    if (suffix === '/recipients') {
      const pageNumber = Number(url.searchParams.get('page') ?? 1), first = (pageNumber - 1) * 20
      return respond({ items: Array.from({ length: Math.min(20, total - first) }, (_, offset) => {
        const index = first + offset + (id === source ? 1 : id === secondSource ? 41 : 111)
        return { id: recipientId(index), authorName: `Synthetic Researcher ${index}`, paperTitle: `Fictional paper ${index}`, category: 'cs.AI', organization: null, personalizationStatus: 'COMPLETED', subject: 'Fictional research invitation', html, text: 'Inert synthetic preview', rationale: null, errorCode: null, errorMessage: null, personalizedAt: stamp, createdAt: stamp, trackingArtifactsRedacted: true }
      }), page: pageNumber, pageSize: 20, total, totalPages: Math.ceil(total / 20) })
    }
    if (suffix === '/preflight') return respond({ ready: false, checks: {}, counts: { TOTAL: total, ELIGIBLE: 0 }, estimatedMinutes: 0, digest: 'synthetic' })
    if (suffix === '/safety-runs' && request.method() === 'GET') return respond([])
    if (suffix === '/template-migrations/preview' && request.method() === 'POST') {
      const command = request.postDataJSON()
      previews.push({ source: id, recipientIds: command.recipientIds })
      return respond({ ...command, sourceCampaignId: id, expectedLockVersion: 3, expectedTemplateLockVersion: 4, expectedMembershipDigest: 'a'.repeat(64), counts: { historicalSelected: command.recipientIds.length, actionableQueued: command.recipientIds.length, transferred: 0, migrationBlocked: options.outcome === 'blocked' ? 1 : 0 }, ready: options.outcome !== 'blocked', reasons: options.outcome === 'blocked' ? [{ code: 'ACTIVE_SAFETY', count: 1 }] : [] })
    }
    if (suffix === '/template-migrations' && request.method() === 'POST') {
      const command = request.postDataJSON() as MigrationCommand
      posts.push({ source: id, command })
      if (options.outcome === 'conflict') return respond({ detail: 'Synthetic conflict' }, 409)
      if (options.outcome === 'forbidden') return respond({ detail: 'Synthetic forbidden' }, 403)
      const draft = result(id, command); drafts.push(draft)
      if (options.outcome === 'timeout') { await new Promise(resolve => setTimeout(resolve, 15_500)); await route.abort('timedout'); return }
      return respond(draft)
    }
    if (suffix === '/template-migrations/by-key' && request.method() === 'GET') {
      readbacks.push(url.searchParams.get('key')!)
      return readbackStatus === 200 ? respond(drafts[0]) : respond({ detail: 'Synthetic unknown' }, readbackStatus)
    }
    unexpected.push(`${request.method()} ${path}`)
    return respond({ detail: 'No workflow execution is available' }, 501)
  })
  return { actor, posts, previews, readbacks, drafts, unexpected, consoleErrors, originalHistory, history, reconcile: () => { readbackStatus = 200 } }
}

async function openSelection(page: Page, id = source) {
  await page.goto(`${origin}/email/campaigns/${id}`)
  await expect(page.locator('#campaign-detail-title')).toContainText('Synthetic paused source')
  await page.getByTestId('migration-open').click()
  await page.getByLabel('目标模板', { exact: true }).selectOption(template)
  await page.getByLabel('冻结模板版本', { exact: true }).selectOption(version)
}
async function selectPage(page: Page) {
  for (const checkbox of await page.locator('[data-testid^="migration-select-"]').all()) await checkbox.check()
}
async function screenshot(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: false })
  await info.attach(name, { path, contentType: 'image/png' })
}

test('actual controls preserve exact 137 across three sources and pages, frozen preview and no start', async ({ page }, info) => {
  const state = await fixture(page)
  await page.setViewportSize({ width: 1280, height: 900 })
  for (const [id, pages, count] of [[source, 2, 40], [secondSource, 4, 70], [thirdSource, 2, 27]] as const) {
    await openSelection(page, id)
    for (let n = 1; n <= pages; n++) {
      await selectPage(page)
      if (n < pages) { await page.getByRole('button', { name: '下一页' }).click(); await expect(page.getByText(`第 ${n + 1} / ${pages} 页`)).toBeVisible() }
    }
    await expect(page.getByText(`跨页已选择 ${count} 位`, { exact: false })).toBeVisible()
    await page.getByTestId('migration-preview').click()
    await expect(page.getByTestId('migration-preview-result')).toContainText(version)
    await screenshot(page, info, `source-${count}-preview`)
    await page.getByTestId('migration-commit').click()
    await expect(page.getByTestId('migration-panel')).toContainText('独立草稿已创建；未提交审核、未排期、未发送。')
  }
  expect(state.posts.map(post => post.command.recipientIds)).toEqual([Array.from({ length: 40 }, (_, i) => recipientId(i + 1)), Array.from({ length: 70 }, (_, i) => recipientId(i + 41)), Array.from({ length: 27 }, (_, i) => recipientId(i + 111))])
  expect(state.previews.map(preview => preview.recipientIds)).toEqual(state.posts.map(post => post.command.recipientIds))
  expect(state.drafts.reduce((sum, draft) => sum + draft.migratedCount, 0)).toBe(137)
  expect(JSON.stringify(state.history)).toBe(state.originalHistory)
  expect(state.unexpected).toEqual([]); expect(state.consoleErrors).toEqual([])
  expect(page.url()).toBe(`${origin}/email/campaigns/${thirdSource}`)
  expect(await page.title()).toBe('CaMel Arxiv')
  await expect(page.locator('vite-error-overlay')).toHaveCount(0)
  await page.setViewportSize({ width: 390, height: 844 })
  await screenshot(page, info, 'mobile-draft-only')
  expect(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth)).toBe(false)
  await page.locator('iframe').first().scrollIntoViewIfNeeded()
  await expect(page.frameLocator('iframe').first().getByRole('heading', { name: 'Fictional research invitation' })).toBeVisible()
  await screenshot(page, info, 'fictional-html-preview')
})

test('real timeout survives reload; unknown readback never resets, original key reconciles without replay', async ({ page }, info) => {
  const state = await fixture(page, { outcome: 'timeout' })
  await openSelection(page)
  await page.getByTestId(`migration-select-${recipientId(1)}`).check()
  await page.getByTestId('migration-preview').click(); await page.getByTestId('migration-commit').click()
  await expect(page.getByText('提交结果未知，仍待对账。只可按原键读回，不可重复提交。')).toBeVisible({ timeout: 20_000 })
  const before = await page.evaluate(() => JSON.stringify(localStorage))
  await page.reload()
  await page.getByTestId('migration-readback').click()
  await expect(page.getByText('原键暂未取得结果，仍待对账；请稍后读回，不要重复提交。')).toBeVisible()
  expect(await page.evaluate(() => JSON.stringify(localStorage))).toBe(before)
  await expect(page.getByTestId('migration-open')).toHaveCount(0)
  await screenshot(page, info, 'unknown-held')
  state.reconcile(); await page.getByTestId('migration-readback').click()
  await expect(page.getByTestId('migration-panel')).toContainText('独立草稿已创建')
  expect(state.posts).toHaveLength(1)
  expect(state.readbacks).toEqual([state.posts[0]!.command.idempotencyKey, state.posts[0]!.command.idempotencyKey])
  expect(state.unexpected).toEqual([]); expect(state.consoleErrors).toEqual([])
})

for (const outcome of ['conflict', 'forbidden', 'blocked'] as const) {
  test(`${outcome} keeps original history and never starts or creates a target`, async ({ page }) => {
    const state = await fixture(page, { outcome })
    await openSelection(page); await page.getByTestId(`migration-select-${recipientId(1)}`).check()
    await page.getByTestId('migration-preview').click()
    if (outcome === 'blocked') await expect(page.getByText('ACTIVE_SAFETY：1')).toBeVisible()
    else {
      await page.getByTestId('migration-commit').click()
      await expect(page.getByText(outcome === 'conflict' ? '版本或名单已变化，旧预检已失效；请刷新并重新预检。' : '迁移被拒绝，未创建新草稿；请检查权限与请求后重新预检。')).toBeVisible()
    }
    await expect(page.getByTestId('migration-commit')).toHaveCount(0)
    expect(state.drafts).toEqual([]); expect(state.unexpected).toEqual([])
    expect(JSON.stringify(state.history)).toBe(state.originalHistory)
  })
}

test('reader and corrupt journal fail closed before any preflight or mutation', async ({ page }) => {
  const state = await fixture(page, { reader: true })
  await page.goto(`${origin}/email/campaigns/${source}`)
  await expect(page.getByText('缺少活动创建权限或活动查看权限', { exact: false })).toBeVisible()
  await expect(page.getByTestId('migration-open')).toHaveCount(0)
  await page.evaluate(key => localStorage.setItem(key, '{broken'), recoveryKey)
  await page.reload()
  await expect(page.getByText('持久恢复记录或跨标签页锁不可用、损坏或已变化', { exact: false })).toBeVisible()
  expect(await page.evaluate(key => localStorage.getItem(key), recoveryKey)).toBe('{broken')
  expect(state.posts).toEqual([]); expect(state.previews).toEqual([])
  expect(state.unexpected).toEqual([])
})

test('another real tab adopts only recovery metadata; changed actor cannot read back or replay', async ({ page }) => {
  const state = await fixture(page, { outcome: 'timeout' })
  await openSelection(page)
  await page.getByTestId(`migration-select-${recipientId(1)}`).check()
  await page.getByTestId('migration-preview').click()
  const other = await page.context().newPage()
  await openSelection(other)
  await other.getByTestId(`migration-select-${recipientId(2)}`).check()
  await other.getByTestId('migration-preview').click()
  await page.getByTestId('migration-commit').click()
  await expect(other.getByTestId('migration-readback')).toBeVisible()
  await expect(other.getByTestId('migration-commit')).toHaveCount(0)
  const actor = state.actor.id
  state.actor.id = '10000000-0000-0000-0000-000000000002'
  await other.reload()
  await expect(other.getByTestId('migration-readback')).toBeDisabled()
  await expect(other.getByText('当前账号与原提交账号不同', { exact: false })).toBeVisible()
  expect(state.readbacks).toEqual([])
  await expect(page.getByText('提交结果未知，仍待对账。只可按原键读回，不可重复提交。')).toBeVisible({ timeout: 20_000 })
  state.actor.id = actor; state.reconcile()
  await other.reload(); await other.getByTestId('migration-readback').click()
  await expect(other.getByTestId('migration-panel')).toContainText('独立草稿已创建')
  expect(state.posts).toHaveLength(1)
  expect(state.readbacks).toEqual([state.posts[0]!.command.idempotencyKey])
  expect(state.unexpected).toEqual([])
})
