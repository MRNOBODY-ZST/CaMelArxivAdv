import { createPinia, setActivePinia } from 'pinia'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useAuthStore } from '@/modules/auth/auth.store'
import ContactListView from '@/modules/contacts/ContactListView.vue'
import { contactsApi } from '@/modules/contacts/contacts.api'
import PaperDetailView from '@/modules/papers/PaperDetailView.vue'
import PapersView from '@/modules/papers/PapersView.vue'
import { papersApi } from '@/modules/papers/papers.api'

vi.mock('@/modules/contacts/contacts.api', () => ({
  contactsApi: { list: vi.fn(), get: vi.fn(), verify: vi.fn(), batchVerify: vi.fn() },
}))
vi.mock('@/modules/papers/papers.api', () => ({
	  papersApi: { list: vi.fn(), get: vi.fn(), extract: vi.fn(), batchExtract: vi.fn() },
}))

describe('Phase 4 source extraction workspace', () => {
  beforeEach(() => {
    vi.mocked(contactsApi.list).mockResolvedValue({
      items: [contact()], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(contactsApi.get).mockImplementation(async (_id, full) => ({
      ...contact(), email: full ? 'alice@university.edu' : 'al***@university.edu', evidence: [{
        sourceRelativePath: 'paper/main.tex', ruleName: 'DIRECT_AUTHOR_EMAIL',
        lineNumber: 4, logicalLocation: 'AUTHOR_FRONT_MATTER',
        maskedContext: 'Corresponding author: al***@university.edu',
      }],
    }))
    vi.mocked(contactsApi.verify).mockResolvedValue({
      ...contact(), verificationStatus: 'CONFIRMED', humanVerified: true, version: 1, evidence: [],
    })
    vi.mocked(contactsApi.batchVerify).mockResolvedValue({ updatedCount: 2, status: 'CONFIRMED' })
    vi.mocked(papersApi.extract).mockResolvedValue({ jobId: 'job-source', status: 'PENDING' })
		vi.mocked(papersApi.batchExtract).mockResolvedValue({ jobId: 'job-batch', status: 'PENDING' })
		vi.mocked(papersApi.list).mockResolvedValue({
		  items: [{
		    id: 'paper-1', arxivId: '2608.00001', title: 'Source Paper', primaryCategory: 'cs.AI',
		    authors: ['Alice Example'], submittedAt: '2026-08-01T00:00:00Z',
		    updatedAt: '2026-08-06T00:00:00Z', doi: null, journalReference: null,
		    sourceStatus: 'UNKNOWN', versionCount: 1,
		  }], page: 1, pageSize: 20, total: 1, totalPages: 1,
		})
    vi.mocked(papersApi.get).mockResolvedValue(paper())
  })

  it('keeps the list masked and gates full disclosure and verification by permission', async () => {
    const wrapper = await mountView(ContactListView, '/contacts')
    expect(wrapper.text()).toContain('al***@university.edu')
    expect(wrapper.text()).not.toContain('alice@university.edu')

    await click(wrapper, '查看证据')
    await flushPromises()
    await click(wrapper, '查看完整邮箱')
    await flushPromises()
    expect(contactsApi.get).toHaveBeenCalledWith('contact-1', true)
    expect(wrapper.text()).toContain('alice@university.edu')

    await click(wrapper, '确认有效')
    await flushPromises()
    expect(contactsApi.verify).toHaveBeenCalledWith('contact-1', {
      mappingId: 'mapping-1', expectedVersion: 0, status: 'CONFIRMED',
    })
  })

  it('submits confirmed and rejected contact batches then refreshes visible versions', async () => {
    vi.mocked(contactsApi.list).mockResolvedValue({
      items: [contact(), contact('contact-2', 'mapping-2', 3)],
      page: 1, pageSize: 20, total: 2, totalPages: 1,
    })
    const wrapper = await mountView(ContactListView, '/contacts')

    await click(wrapper, '全选本页')
    await click(wrapper, '批量标记有效')
    await flushPromises()

    expect(contactsApi.batchVerify).toHaveBeenLastCalledWith([
      { contactId: 'contact-1', mappingId: 'mapping-1', expectedVersion: 0 },
      { contactId: 'contact-2', mappingId: 'mapping-2', expectedVersion: 3 },
    ], 'CONFIRMED')
    expect(wrapper.text()).toContain('已批量标记 2 个联系人为有效')
    expect(wrapper.text()).not.toContain('已选 2 个')

    await click(wrapper, '全选本页')
    await click(wrapper, '批量标记无效')
    await flushPromises()

    expect(contactsApi.batchVerify).toHaveBeenLastCalledWith([
      { contactId: 'contact-1', mappingId: 'mapping-1', expectedVersion: 0 },
      { contactId: 'contact-2', mappingId: 'mapping-2', expectedVersion: 3 },
    ], 'REJECTED')
    expect(wrapper.text()).toContain('已批量标记 2 个联系人为无效')
    expect(contactsApi.list).toHaveBeenCalledTimes(3)
  })

  it('shows all paper detail tabs and starts an asynchronous source job', async () => {
    const wrapper = await mountView(PaperDetailView, '/papers/paper-1')
    expect(wrapper.text()).toContain('开始 Source 解析')

    await click(wrapper, '开始 Source 解析')
    await flushPromises()
    expect(papersApi.extract).toHaveBeenCalledWith('paper-1')
    expect(wrapper.text()).toContain('job-source')

    expect(wrapper.text()).toContain('联系人')
    expect(wrapper.text()).toContain('提取记录')
    await click(wrapper, '提取记录')
    expect(wrapper.text()).toContain('SUCCEEDED')
    expect(wrapper.text()).toContain('临时文件已清理')
  })

	  it('submits selected papers as one bounded batch extraction job', async () => {
	    const wrapper = await mountView(PapersView, '/papers')
	    await wrapper.get('#select-paper-paper-1').setValue(true)
	    await click(wrapper, '批量解析')
	    await flushPromises()
	    expect(papersApi.batchExtract).toHaveBeenCalledWith(['paper-1'])
	    expect(wrapper.text()).toContain('job-batch')
	  })

  it('selects and clears every visible paper before one batch extraction job', async () => {
    vi.mocked(papersApi.list).mockResolvedValue({
      items: [paperSummary('paper-1', '2608.00001'), paperSummary('paper-2', '2608.00002')],
      page: 1, pageSize: 20, total: 2, totalPages: 1,
    })
    const wrapper = await mountView(PapersView, '/papers')

    await click(wrapper, '全选本页')
    expect(wrapper.findAll('input[type="checkbox"]')).toHaveLength(3)
    expect(wrapper.findAll('input[type="checkbox"]').every((item) => (
      item.element as HTMLInputElement
    ).checked)).toBe(true)

    await click(wrapper, '清空本页')
    expect(wrapper.findAll('input[type="checkbox"]').every((item) => !(
      item.element as HTMLInputElement
    ).checked)).toBe(true)

    await click(wrapper, '全选本页')
    await click(wrapper, '批量解析')
    await flushPromises()

    expect(papersApi.batchExtract).toHaveBeenCalledWith(['paper-1', 'paper-2'])
  })
})

async function mountView(component: Parameters<typeof mount>[0], path: string): Promise<VueWrapper> {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: {
      id: 'actor', username: 'admin', displayName: 'Admin', roles: ['SUPER_ADMIN'],
      permissions: ['paper:read', 'paper:import', 'contact:read_masked', 'contact:read_full', 'contact:verify'],
      mustChangePassword: false,
    },
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/contacts', component: ContactListView },
      { path: '/papers/:id', component: PaperDetailView },
      { path: '/papers', component: { template: '<div />' } },
      { path: '/jobs/:id', component: { template: '<div />' } },
    ],
  })
  await router.push(path); await router.isReady()
  const wrapper = mount(component, {
    global: {
      plugins: [pinia, router],
      stubs: {
        DsModal: {
          props: ['open'],
          template: '<section v-if="open"><slot /></section>',
        },
      },
    },
  })
  await flushPromises()
  return wrapper
}

async function click(wrapper: VueWrapper, label: string): Promise<void> {
  const button = wrapper.findAll('button').find((item) => item.text().includes(label))
  if (!button) throw new Error(`Button ${label} was not found`)
  await button.trigger('click')
}

function contact(id = 'contact-1', mappingId = 'mapping-1', version = 0) {
  return {
    id, email: 'al***@university.edu', domain: 'university.edu',
    exampleAddress: false, suppressionStatus: 'ACTIVE', mappingId, version,
    confidence: 'HIGH', corresponding: true, verificationStatus: 'UNVERIFIED',
    humanVerified: false, paperId: 'paper-1', arxivId: '2608.00001', paperTitle: 'Source Paper',
    authorName: 'Alice Example', categoryId: 'cs.AI', ruleName: 'DIRECT_AUTHOR_EMAIL',
    lastExtractedAt: '2026-08-06T01:00:00Z',
  }
}

function paper() {
  return {
    id: 'paper-1', arxivId: '2608.00001', title: 'Source Paper', primaryCategory: 'cs.AI',
    submittedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-06T00:00:00Z',
    doi: null, journalReference: null, sourceStatus: 'PARSED', versionCount: 1,
    abstractText: 'Abstract', comment: null, licenseUrl: null,
    pdfUrl: 'https://arxiv.org/pdf/2608.00001', sourceFormat: 'TAR_GZIP',
    authors: [{ order: 1, name: 'Alice Example', corresponding: true, affiliations: ['Example Lab'] }],
    categories: [{ categoryId: 'cs.AI', categoryName: 'Artificial Intelligence', relationType: 'PRIMARY' }],
    versions: [{ version: 1, submittedAt: '2026-08-01T00:00:00Z', sizeBytes: 1200, sourceFormat: 'TAR_GZIP' }],
    imports: [], rawMetadata: {},
    extractionRuns: [{
      id: 'run-1', jobId: 'job-old', parserVersion: '0.1.0', status: 'SUCCEEDED',
      documentClass: 'article', sourceFormat: 'TAR_GZIP', filesInspected: 2, contactsFound: 1,
      durationMs: 85, archiveSizeBytes: 1200, extractedSizeBytes: 4000, cleanupConfirmed: true,
      startedAt: '2026-08-06T01:00:00Z', completedAt: '2026-08-06T01:00:01Z',
      errorCode: null, errorSummary: null,
    }],
  }
}

function paperSummary(id: string, arxivId: string) {
  return {
    id, arxivId, title: `Source Paper ${arxivId}`, primaryCategory: 'cs.AI',
    authors: ['Alice Example'], submittedAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-06T00:00:00Z', doi: null, journalReference: null,
    sourceStatus: 'UNKNOWN', versionCount: 1,
  }
}
