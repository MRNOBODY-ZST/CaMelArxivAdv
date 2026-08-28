import { describe, expect, it } from 'vitest'

import {
  formatMailTrackingDate,
  isMailTrackingExpired,
  mailSendSourceLabel,
  mailSendStatusLabel,
  mailTrackingState,
} from '@/modules/email/mail-tracking.presentation'
import type { MailSendRecord } from '@/modules/email/mail-tracking.types'

function record(overrides: Partial<MailSendRecord> = {}): MailSendRecord {
  return {
    id: 'record-1', source: 'SMTP_DIAGNOSTIC', recipientMasked: 'q***@example.invalid', subject: '测试记录',
    smtpAccountName: 'Mailpit', status: 'SMTP_ACCEPTED', failureCategory: null, trackingEnabled: true,
    createdAt: '2026-08-28T10:00:00Z', completedAt: '2026-08-28T10:00:01Z', trackingExpiresAt: '2099-08-29T10:00:00Z',
    rawOpenCount: 0, automatedOpenCount: 0, firstOpenAt: null, lastOpenAt: null,
    ...overrides,
  }
}

describe('mail tracking presentation', () => {
  it('keeps source, SMTP result, and tracking state wording consistent', () => {
    expect(mailSendSourceLabel(record())).toBe('SMTP 诊断')
    expect(mailSendSourceLabel(record({ source: 'TEMPLATE_TEST' }))).toBe('模板测试')
    expect(mailSendStatusLabel(record({ status: 'UNKNOWN' }))).toBe('发送状态未知')
    expect(mailTrackingState(record({ rawOpenCount: 2 }))).toBe('检测到图片加载（2）')
    expect(mailTrackingState(record({ status: 'FAILED' }))).toBe('发送失败，未确认检测')
  })

  it('keeps observed image-load evidence when SMTP or collection status later becomes uncertain', () => {
    expect(mailTrackingState(record({ status: 'UNKNOWN', rawOpenCount: 1 }))).toBe('检测到图片加载（1）')
    expect(mailTrackingState(record({ status: 'FAILED', rawOpenCount: 2 }))).toBe('检测到图片加载（2）')
    expect(mailTrackingState(record({ trackingExpiresAt: '2020-01-01T00:00:00Z', rawOpenCount: 3 }))).toBe('检测到图片加载（3）')
  })

  it('handles nullable dates and expired tracking with the shared semantics', () => {
    expect(formatMailTrackingDate(null)).toBe('—')
    expect(isMailTrackingExpired(record({ trackingExpiresAt: '2020-01-01T00:00:00Z' }))).toBe(true)
    expect(mailTrackingState(record({ trackingExpiresAt: '2020-01-01T00:00:00Z' }))).toBe('检测期已过期')
    expect(isMailTrackingExpired(record())).toBe(false)
  })
})
