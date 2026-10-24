import { Schema, type Slice } from '@tiptap/pm/model'
import { EditorState } from '@tiptap/pm/state'
import { EditorView } from '@tiptap/pm/view'
import { flushPromises, mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TemplateRichTextEditor from '@/modules/email/TemplateRichTextEditor.vue'

function paste(target: HTMLElement, html: string, text: string): Event {
  const event = new Event('paste', { bubbles: true, cancelable: true })
  Object.defineProperty(event, 'clipboardData', {
    value: { getData: (type: string) => type === 'text/html' ? html : type === 'text/plain' ? text : '' },
  })
  target.dispatchEvent(event)
  return event
}

function installJsdomRangeGeometry(): () => void {
  // jsdom has no layout. Stub only the missing geometry used by post-insertion
  // scrolling, leaving clipboard parsing, document changes and emits real.
  const methods = {
    getClientRects: () => [],
    getBoundingClientRect: () => new DOMRect(0, 0, 0, 0),
  }
  const previous = Object.entries(methods).map(([name, value]) => {
    const descriptor = Object.getOwnPropertyDescriptor(Range.prototype, name)
    Object.defineProperty(Range.prototype, name, { configurable: true, value })
    return { name, descriptor }
  })
  return () => {
    for (const { name, descriptor } of previous) {
      if (descriptor) Object.defineProperty(Range.prototype, name, descriptor)
      else Reflect.deleteProperty(Range.prototype, name)
    }
  }
}

async function withEditorGeometry<T extends { unmount: () => void }>(create: () => T, work: (wrapper: T) => Promise<void>) {
  const restoreGeometry = installJsdomRangeGeometry()
  let wrapper: T | undefined
  try {
    wrapper = create()
    await work(wrapper)
  } finally {
    try {
      wrapper?.unmount()
    } finally {
      restoreGeometry()
    }
  }
}

// An inert schema isolates the maintainer's slice-context attribute-validation
// branch. This is not an executable advisory exploit or the component's schema.
function inspectContextPaste(html: string) {
  const validated: unknown[] = []
  const schema = new Schema({
    nodes: {
      doc: { content: 'block+' },
      paragraph: { group: 'block', content: 'text*', parseDOM: [{ tag: 'p' }], toDOM: () => ['p', 0] },
      text: { group: 'inline' },
      contextWrapper: {
        group: 'block', content: 'block+',
        attrs: { label: { default: 'default-label', validate: (value: unknown) => {
          validated.push(value)
          if (typeof value !== 'string') throw new RangeError('label must be a string')
        } } },
        toDOM: () => ['section', 0],
      },
    },
  })
  const host = document.createElement('div')
  document.body.append(host)
  let parsed: Slice | undefined
  const view = new EditorView(host, {
    state: EditorState.create({ schema }),
    transformPasted: (slice) => { parsed = slice; return slice },
    // Observe parsing before insertion/fitting can discard the context wrapper.
    handlePaste: () => true,
  })
  try {
    const event = paste(view.dom, html, 'clipboard-context-control')
    expect(event.defaultPrevented).toBe(true)
    expect(parsed).toBeDefined()
    return { parsed: parsed!, validated }
  } finally {
    view.destroy()
    host.remove()
  }
}

describe('installed editor clipboard context validation', () => {
  it('preserves a valid string context wrapper as a positive control', () => {
    const { parsed } = inspectContextPaste('<p data-pm-slice=\'0 0 ["contextWrapper",{"label":"context-ok"}]\'>clipboard-context-control</p>')

    expect(parsed.content.firstChild?.type.name).toBe('contextWrapper')
    expect(parsed.content.firstChild?.attrs.label).toBe('context-ok')
    expect(parsed.content.textBetween(0, parsed.content.size)).toBe('clipboard-context-control')
  })

  it('rejects a numeric context attribute without losing the inert paragraph', () => {
    // Removing the installed view's checkAttrs branch restores the invalid
    // wrapper and fails this literal expectation (verified RED on 1.42.2).
    const { parsed, validated } = inspectContextPaste('<p data-pm-slice=\'0 0 ["contextWrapper",{"label":7}]\'>clipboard-context-control</p>')

    expect(parsed.content.firstChild?.type.name).toBe('paragraph')
    expect(parsed.content.textBetween(0, parsed.content.size)).toBe('clipboard-context-control')
    expect(validated).toContain(7)
  })
})

describe('TemplateRichTextEditor paste integration', () => {
  it.each(['mount', 'unmount'])('restores owned Range geometry after %s failure', async (stage) => {
    const names = ['getClientRects', 'getBoundingClientRect'] as const
    const before = names.map(name => Object.getOwnPropertyDescriptor(Range.prototype, name))
    try {
      await expect(withEditorGeometry(() => {
        if (stage === 'mount') throw new Error('synthetic mount failure')
        const wrapper = mount(TemplateRichTextEditor, { props: { modelValue: '<p></p>' } })
        return { unmount: () => { wrapper.unmount(); throw new Error('synthetic unmount failure') } }
      }, async () => { await flushPromises() })).rejects.toThrow(`synthetic ${stage} failure`)
      expect(names.map(name => Object.getOwnPropertyDescriptor(Range.prototype, name))).toEqual(before)
    } finally {
      names.forEach((name, index) => {
        const descriptor = before[index]
        if (descriptor) Object.defineProperty(Range.prototype, name, descriptor)
        else Reflect.deleteProperty(Range.prototype, name)
      })
    }
  })

  it.each([
    { name: 'rich HTML', html: '<p><strong>rich-paste-control</strong></p>', text: 'rich-paste-control', expected: '<p><strong>rich-paste-control</strong></p>' },
    { name: 'plain text', html: '', text: 'plain-paste-control', expected: '<p>plain-paste-control</p>' },
  ])('renders and emits pasted $name through the real editor', async ({ html, text, expected }) => {
    // Losing the paste handler or the component's onUpdate emit must fail even
    // when unsafe input happens to remain inert in the test environment.
    await withEditorGeometry(() => mount(TemplateRichTextEditor, { attachTo: document.body, props: { modelValue: '<p></p>' } }), async wrapper => {
      await flushPromises()
      const editable = wrapper.get<HTMLElement>('[aria-label="邮件 HTML 正文编辑器"]')
      editable.element.focus()

      expect(paste(editable.element, html, text).defaultPrevented).toBe(true)
      await flushPromises()

      expect(editable.text()).toBe(text)
      expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual([expected])
    })
  })
})
