export type JobStatus = 'PENDING' | 'QUEUED' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'PARTIALLY_SUCCEEDED' | 'FAILED' | 'CANCELED'
export type JobAction = 'PAUSE' | 'RESUME' | 'CANCEL' | 'RETRY'

export interface JobView {
  id: string
  type: string
  status: JobStatus
  createdBy: string
  parentJobId: string | null
  rootJobId: string | null
  version: number
  totalCount: number
  processedCount: number
  successCount: number
  skippedCount: number
  failedCount: number
  currentStage: string
  progressPercent: number
  startedAt: string | null
  endedAt: string | null
  heartbeatAt: string | null
  createdAt: string
  updatedAt: string
  workerStale: boolean
  errorSummary: string | null
  allowedActions: JobAction[]
}

export interface JobEvent {
  id: number
  eventType: string
  stage: string | null
  message: string
  details: string | null
  occurredAt: string
}

export interface PageResponse<T> { items: T[]; page: number; pageSize: number; total: number; totalPages: number }
