import { describe, expect, it } from 'vitest'

import {
  createTemplateDraft,
  insertPlaceholder,
  maskEmailAddress,
  passwordForUpdate,
  previewWidthClass,
} from '@/modules/email/email.editor'

describe('email editor state', () => {
  it('starts with a safe unsubscribe-aware draft', () => {
    const draft = createTemplateDraft()

    expect(draft.status).toBe('DRAFT')
    expect(draft.content.autoGenerateText).toBe(true)
    expect(draft.content.htmlContent).toContain('{{unsubscribe_url}}')
  })

  it('inserts a supported placeholder at the current selection', () => {
    expect(insertPlaceholder('Hello name', 'author_name', 6, 10)).toBe('Hello {{author_name}}')
  })

  it('uses stable preview widths and preserves an existing SMTP password', () => {
    expect(previewWidthClass('desktop')).toContain('max-w-2xl')
    expect(previewWidthClass('mobile')).toContain('max-w-sm')
    expect(passwordForUpdate('', true)).toBeNull()
    expect(passwordForUpdate('replacement', true)).toBe('replacement')
  })

  it('masks addresses outside the explicit edit flow', () => {
    expect(maskEmailAddress('sender@example.org')).toBe('se***@example.org')
    expect(maskEmailAddress('a@example.org')).toBe('a***@example.org')
    expect(maskEmailAddress('invalid')).toBe('***')
  })
})
