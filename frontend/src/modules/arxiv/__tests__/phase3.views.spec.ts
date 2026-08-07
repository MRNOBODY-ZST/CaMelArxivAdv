import { createPinia, setActivePinia } from 'pinia'
import { DOMWrapper, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { arxivApi } from '@/modules/arxiv/arxiv.api'
import type { NormalizedSearchCriteria } from '@/modules/arxiv/arxiv.types'
import ArxivDiscoveryView from '@/modules/arxiv/ArxivDiscoveryView.vue'
import CategoryTree from '@/modules/arxiv/components/CategoryTree.vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import ImportJobsView from '@/modules/jobs/ImportJobsView.vue'
import { jobsApi } from '@/modules/jobs/jobs.api'
import PapersView from '@/modules/papers/PapersView.vue'
import { papersApi } from '@/modules/papers/papers.api'

vi.mock('@/modules/arxiv/arxiv.api', () => ({
  arxivApi: {
    taxonomy: vi.fn(), preview: vi.fn(), saveSearch: vi.fn(),
    importSelected: vi.fn(), importCriteria: vi.fn(),
  },
}))
vi.mock('@/modules/jobs/jobs.api', () => ({ jobsApi: { list: vi.fn() } }))
vi.mock('@/modules/papers/papers.api', () => ({ papersApi: { list: vi.fn() } }))

describe('Phase 3 arXiv workspace', () => {
  beforeEach(() => {
    vi.mocked(arxivApi.taxonomy).mockResolvedValue({
      snapshotVersion: 'taxonomy-2026-08', sourceType: 'OFFLINE_SNAPSHOT', sourceUrls: [],
      sourceUpdatedAt: '2026-08-01T00:00:00Z', syncedAt: '2026-08-05T00:00:00Z', groups: [],
    })
  })

  it('submits a normalized preview request and renders official results', async () => {
    vi.mocked(arxivApi.preview).mockResolvedValue({
      queryHash: 'hash', criteria: criteria(), officialTotal: 1, totalIsEstimate: false,
      page: 1, pageSize: 20, cacheStatus: 'MISS', filters: [],
      papers: [{
        arxivId: '2608.00001', title: 'Reliable Agents', abstractText: 'Summary',
        authors: [{ name: 'Ada Lovelace', affiliations: [] }], primaryCategory: 'cs.AI',
        categoryIds: ['cs.AI'], publishedAt: '2026-08-01T00:00:00Z',
        updatedAt: '2026-08-04T00:00:00Z', doi: null, journalReference: null,
        pdfUrl: 'https://arxiv.org/pdf/2608.00001v1', versionCount: 1,
      }],
    })
    const wrapper = mountWithSession(ArxivDiscoveryView)
    await flushPromises()
    await wrapper.get('#title-keywords').setValue('reliable agents')
    await button(wrapper, '预览结果').trigger('click')
    await flushPromises()

    expect(arxivApi.preview).toHaveBeenCalledWith(expect.objectContaining({
      titleKeywords: 'reliable agents', page: 1, pageSize: 20,
    }))
    expect(wrapper.text()).toContain('Reliable Agents')
    expect(wrapper.text()).toContain('实时查询')
  })

  it('selects and imports every paper on the current preview page', async () => {
    vi.mocked(arxivApi.preview).mockResolvedValue({
      queryHash: 'page-hash', criteria: criteria(), officialTotal: 24, totalIsEstimate: false,
      page: 1, pageSize: 20, cacheStatus: 'MISS', filters: [],
      papers: [previewPaper('2608.00001', 'Reliable Agents'), previewPaper('2608.00002', 'Safe Agents')],
    })
    vi.mocked(arxivApi.importSelected).mockResolvedValue({
      jobId: 'job-current-page', status: 'PENDING', created: true, idempotencyKey: 'page-import',
    })
    const wrapper = mountWithSession(ArxivDiscoveryView)
    await flushPromises()
    await wrapper.get('#title-keywords').setValue('agents')
    await button(wrapper, '预览结果').trigger('click')
    await flushPromises()

    await button(wrapper, '全选本页').trigger('click')
    expect(wrapper.findAll('tbody input[type="checkbox"]')).toHaveLength(2)
    expect(wrapper.findAll('tbody input[type="checkbox"]').every((item) => (
      item.element as HTMLInputElement
    ).checked)).toBe(true)
    expect(wrapper.text()).toContain('导入已选 2 篇')

    await button(wrapper, '清空选择').trigger('click')
    await button(wrapper, '一键导入本页 2 篇').trigger('click')
    await flushPromises()

    expect(arxivApi.importSelected).toHaveBeenCalledWith(['2608.00001', '2608.00002'])
  })

  it('navigates official preview pages without losing prior selections', async () => {
    vi.mocked(arxivApi.preview).mockImplementation(async (request) => ({
      queryHash: `page-${request.page}`,
      criteria: { ...criteria(), page: request.page },
      officialTotal: 30,
      totalIsEstimate: false,
      page: request.page,
      pageSize: 20,
      cacheStatus: 'MISS',
      filters: [],
      papers: request.page === 1
        ? [previewPaper('2608.00001', 'First Page Paper')]
        : [previewPaper('2608.00021', 'Second Page Paper')],
    }))
    const wrapper = mountWithSession(ArxivDiscoveryView)
    await flushPromises()
    await wrapper.get('#title-keywords').setValue('agents')
    await button(wrapper, '预览结果').trigger('click')
    await flushPromises()
    await wrapper.get('input[aria-label="选择 First Page Paper"]').setValue(true)

    await button(wrapper, '下一页').trigger('click')
    await flushPromises()

    expect(arxivApi.preview).toHaveBeenLastCalledWith(expect.objectContaining({
      titleKeywords: 'agents', page: 2, pageSize: 20,
    }))
    expect(wrapper.text()).toContain('Second Page Paper')
    expect(wrapper.text()).toContain('导入已选 1 篇')
    expect(wrapper.text()).toContain('第 2 / 2 页')
  })

  it('keeps wrapped category checkboxes at a fixed size', async () => {
    const wrapper = mount(CategoryTree, {
      props: {
        modelValue: [],
        groups: [{
          groupId: 'cs', groupName: 'Computer Science', archives: [{
            archiveId: 'cs', archiveName: 'Computer Science', categories: [{
              categoryId: 'cs.CE',
              categoryName: 'Computational Engineering, Finance, and Science',
              description: null, alias: false, aliasTarget: null,
            }],
          }],
        }],
      },
    })

    await wrapper.get('button').trigger('click')
    const checkbox = wrapper.get('input[type="checkbox"]')

    expect(checkbox.classes()).toEqual(expect.arrayContaining([
      'size-4', 'min-h-4', 'min-w-4', 'shrink-0',
    ]))
  })

  it('renders job progress and paper library server results', async () => {
    vi.mocked(jobsApi.list).mockResolvedValue({
      items: [{
        id: 'job-1', type: 'ARXIV_IMPORT_METADATA', status: 'RUNNING', createdBy: 'actor',
        parentJobId: null, rootJobId: null, version: 1, totalCount: 10, processedCount: 4,
        successCount: 4, skippedCount: 0, failedCount: 0, currentStage: 'FETCHING_METADATA',
        progressPercent: 40, startedAt: '2026-08-05T00:00:00Z', endedAt: null,
        heartbeatAt: '2026-08-05T00:00:00Z', createdAt: '2026-08-05T00:00:00Z',
        updatedAt: '2026-08-05T00:00:00Z', workerStale: false, errorSummary: null,
        allowedActions: ['PAUSE', 'CANCEL'],
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })
    vi.mocked(papersApi.list).mockResolvedValue({
      items: [{
        id: 'paper-1', arxivId: '2608.00001', title: 'Reliable Agents',
        primaryCategory: 'cs.AI', authors: ['Ada Lovelace'],
        submittedAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-04T00:00:00Z',
        doi: null, journalReference: null, sourceStatus: 'UNKNOWN', versionCount: 1,
      }], page: 1, pageSize: 20, total: 1, totalPages: 1,
    })

    const jobs = mountWithSession(ImportJobsView)
    const papers = mountWithSession(PapersView)
    await flushPromises()

    expect(jobs.text()).toContain('40.0%')
    expect(jobs.text()).toContain('FETCHING_METADATA')
    expect(papers.text()).toContain('Reliable Agents')
    expect(papers.text()).toContain('Ada Lovelace')
  })
})

function mountWithSession(component: Parameters<typeof mount>[0]): VueWrapper {
  const pinia = createPinia(); setActivePinia(pinia)
  useAuthStore().acceptSession({
    accessToken: 'token', tokenType: 'Bearer', expiresInSeconds: 600,
    user: {
      id: 'actor', username: 'user', displayName: 'User', roles: ['DATA_USER'],
      permissions: ['paper:read', 'paper:import', 'job:manage'], mustChangePassword: false,
    },
  })
  return mount(component, {
    global: { plugins: [pinia], stubs: { RouterLink: { template: '<a><slot /></a>' } } },
  })
}

function button(wrapper: VueWrapper, label: string): DOMWrapper<HTMLButtonElement> {
  const match = wrapper.findAll('button').find((item) => item.text().includes(label))
  if (!match) throw new Error(`Button ${label} was not found`)
  return match as DOMWrapper<HTMLButtonElement>
}

function criteria(): NormalizedSearchCriteria {
  return {
    categoryIds: [], categoryMode: 'ANY' as const, titleKeywords: 'reliable agents',
    abstractKeywords: null, authorKeywords: null, submittedFrom: null, submittedTo: null,
    updatedFrom: null, updatedTo: null, hasDoi: null, hasJournalReference: null,
    sourceAvailable: null, sortBy: 'RELEVANCE' as const,
    sortOrder: 'DESCENDING' as const, page: 1, pageSize: 20,
  }
}

function previewPaper(arxivId: string, title: string) {
  return {
    arxivId, title, abstractText: 'Summary',
    authors: [{ name: 'Ada Lovelace', affiliations: [] }], primaryCategory: 'cs.AI',
    categoryIds: ['cs.AI'], publishedAt: '2026-08-01T00:00:00Z',
    updatedAt: '2026-08-04T00:00:00Z', doi: null, journalReference: null,
    pdfUrl: `https://arxiv.org/pdf/${arxivId}v1`, versionCount: 1,
  }
}
