import { describe, expect, it } from 'vitest'

import { useFocusReturningDisclosure } from '@/composables/useFocusReturningDisclosure'

describe('useFocusReturningDisclosure', () => {
  it('closes and restores focus to its trigger', async () => {
    const disclosure = useFocusReturningDisclosure()
    const trigger = document.createElement('button')
    document.body.append(trigger)
    disclosure.trigger.value = trigger

    disclosure.show()
    expect(disclosure.open.value).toBe(true)

    await disclosure.close()

    expect(disclosure.open.value).toBe(false)
    expect(document.activeElement).toBe(trigger)
    trigger.remove()
  })
})
