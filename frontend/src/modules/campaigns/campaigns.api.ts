import axios from 'axios'

import { apiClient } from '@/api/client'
import type {
  CampaignAnalyticsPage,
  CampaignPreflight,
  CampaignPage,
  CampaignView,
  DeliveryPage,
  GenerationStart,
  LinkAnalyticsPage,
  RecipientPage,
  RuntimeStatus,
  SafetyRunView,
  SegmentPage,
  SegmentPreview,
  SegmentRule,
  SegmentView,
} from '@/modules/campaigns/campaigns.types'

export const campaignsApi = {
  async listSegments(page = 1, pageSize = 20): Promise<SegmentPage> {
    return (await apiClient.get<SegmentPage>('/segments', { params: { page, pageSize } })).data
  },
  async previewSegment(rules: SegmentRule[]): Promise<SegmentPreview> {
    return (await apiClient.post<SegmentPreview>('/segments/preview', { rules })).data
  },
  async createSegment(payload: { name: string; description: string; rules: SegmentRule[] }): Promise<SegmentView> {
    return (await apiClient.post<SegmentView>('/segments', payload)).data
  },
  async listCampaigns(page = 1, pageSize = 20): Promise<CampaignPage> {
    return (await apiClient.get<CampaignPage>('/campaigns', { params: { page, pageSize } })).data
  },
  async createCampaign(payload: {
    name: string; purpose: string; templateId: string; segmentId: string; smtpAccountId: string
  }): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>('/campaigns', payload)).data
  },
  async getCampaign(id: string): Promise<CampaignView> {
    return (await apiClient.get<CampaignView>(`/campaigns/${id}`)).data
  },
  async listRecipients(id: string, page = 1, pageSize = 20): Promise<RecipientPage> {
    return (await apiClient.get<RecipientPage>(`/campaigns/${id}/recipients`, { params: { page, pageSize } })).data
  },
  async startPersonalization(id: string): Promise<GenerationStart> {
    return (await apiClient.post<GenerationStart>(`/campaigns/${id}/personalizations`)).data
  },
  async preflightCampaign(id: string): Promise<CampaignPreflight> {
    return (await apiClient.get<CampaignPreflight>(`/campaigns/${id}/preflight`)).data
  },
  async submitCampaignForReview(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/submit-review`, { expectedLockVersion })).data
  },
  async approveCampaign(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/approve`, { expectedLockVersion })).data
  },
  async rejectCampaign(id: string, expectedLockVersion: number, reason: string): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/reject`, { expectedLockVersion, reason })).data
  },
  async scheduleCampaign(id: string, expectedLockVersion: number, scheduledAt: string): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/schedule`, { expectedLockVersion, scheduledAt })).data
  },
  async startCampaign(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/start`, { expectedLockVersion })).data
  },
  async pauseCampaign(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/pause`, { expectedLockVersion })).data
  },
  async resumeCampaign(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/resume`, { expectedLockVersion })).data
  },
  async cancelCampaign(id: string, expectedLockVersion: number): Promise<CampaignView> {
    return (await apiClient.post<CampaignView>(`/campaigns/${id}/cancel`, { expectedLockVersion })).data
  },
  async startSafetyRun(id: string, payload: {
    expectedLockVersion: number; recipientLimit: number; confirmation: 'SAFETY_REDIRECT'
  }): Promise<SafetyRunView> {
    return (await apiClient.post<SafetyRunView>(`/campaigns/${id}/safety-runs`, payload)).data
  },
  async listSafetyRuns(id: string): Promise<SafetyRunView[]> {
    return (await apiClient.get<SafetyRunView[]>(`/campaigns/${id}/safety-runs`)).data
  },
  async getSafetyRun(id: string, runId: string): Promise<SafetyRunView> {
    return (await apiClient.get<SafetyRunView>(`/campaigns/${id}/safety-runs/${runId}`)).data
  },
  async cancelSafetyRun(id: string, runId: string, expectedLockVersion: number): Promise<SafetyRunView> {
    return (await apiClient.post<SafetyRunView>(`/campaigns/${id}/safety-runs/${runId}/cancel`, { expectedLockVersion })).data
  },
  async listDeliveries(page = 1, pageSize = 20, campaignId?: string): Promise<DeliveryPage> {
    return (await apiClient.get<DeliveryPage>('/deliveries', { params: { page, pageSize, campaignId } })).data
  },
  async listCampaignAnalytics(page = 1, pageSize = 20, campaignId?: string): Promise<CampaignAnalyticsPage> {
    return (await apiClient.get<CampaignAnalyticsPage>('/campaign-analytics', { params: { page, pageSize, campaignId } })).data
  },
  async listLinkAnalytics(page = 1, pageSize = 20, campaignId?: string): Promise<LinkAnalyticsPage> {
    return (await apiClient.get<LinkAnalyticsPage>('/link-analytics', { params: { page, pageSize, campaignId } })).data
  },
  async runtimeStatus(): Promise<RuntimeStatus> {
    return (await apiClient.get<RuntimeStatus>('/system/runtime')).data
  },
}

export function campaignErrorMessage(error: unknown, fallback: string): string {
  if (!axios.isAxiosError(error)) return fallback
  const detail = (error.response?.data as { detail?: unknown } | undefined)?.detail
  return typeof detail === 'string' && detail.length <= 300 ? detail : fallback
}
