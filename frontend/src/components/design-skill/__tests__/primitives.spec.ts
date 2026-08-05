import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import DsButton from '@/components/design-skill/DsButton.vue'
import DsInput from '@/components/design-skill/DsInput.vue'

describe('DesignSkill primitives', () => {
  it('emits click from an accessible button', async () => {
    const wrapper = mount(DsButton, { slots: { default: 'Start sync' } })

    await wrapper.get('button').trigger('click')

    expect(wrapper.get('button').attributes('type')).toBe('button')
    expect(wrapper.emitted('click')).toHaveLength(1)
  })

  it('connects the input label, description, and error message', () => {
    const wrapper = mount(DsInput, {
      props: {
        id: 'campaign-name',
        label: 'Campaign name',
        description: 'Visible to operators only.',
        error: 'Campaign name is required.',
        modelValue: '',
      },
    })

    const input = wrapper.get('input')
    expect(wrapper.get('label').attributes('for')).toBe('campaign-name')
    expect(input.attributes('aria-describedby')).toContain('campaign-name-description')
    expect(input.attributes('aria-describedby')).toContain('campaign-name-error')
    expect(input.attributes('aria-invalid')).toBe('true')
  })
})
