import { onBeforeUnmount, type Ref, watch } from 'vue'
import { useAuthStore } from '@/modules/auth/auth.store'
import { jobsApi } from './jobs.api'
import type { JobView } from './jobs.types'

export function useJobProgress(jobId: Ref<string | undefined>, onJob: (job: JobView) => void) {
  let abort: AbortController | undefined
  let timer: number | undefined
  let lastEventId = 0

  async function poll(): Promise<void> {
    if (!jobId.value) return
    try { onJob(await jobsApi.get(jobId.value)) } catch { /* the visible view owns errors */ }
  }

  function fallback(): void {
    if (timer !== undefined) return
    void poll()
    timer = window.setInterval(() => { void poll() }, 5_000)
  }

  async function connect(id: string): Promise<void> {
    abort?.abort(); abort = new AbortController()
    const token = useAuthStore().accessToken
    try {
      const response = await fetch(`/api/v1/jobs/${id}/stream`, {
        headers: {
          Accept: 'text/event-stream',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
          ...(lastEventId ? { 'Last-Event-ID': String(lastEventId) } : {}),
        }, signal: abort.signal,
      })
      if (!response.ok || !response.body) throw new Error('SSE unavailable')
      const reader = response.body.getReader(); const decoder = new TextDecoder(); let buffer = ''
      while (true) {
        const { done, value } = await reader.read(); if (done) break
        buffer += decoder.decode(value, { stream: true })
        const frames = buffer.split('\n\n'); buffer = frames.pop() ?? ''
        for (const frame of frames) {
          const idLine = frame.split('\n').find((line) => line.startsWith('id:'))
          if (idLine) lastEventId = Math.max(lastEventId, Number(idLine.slice(3).trim()) || 0)
          if (frame.includes('data:')) await poll()
        }
      }
      fallback()
    } catch (error) {
      if (!(error instanceof DOMException && error.name === 'AbortError')) fallback()
    }
  }

  watch(jobId, (id) => { if (id) void connect(id) }, { immediate: true })
  onBeforeUnmount(() => { abort?.abort(); if (timer !== undefined) window.clearInterval(timer) })
  return { poll }
}
