import { nextTick, ref } from 'vue'

export function useFocusReturningDisclosure() {
  const open = ref(false)
  const trigger = ref<HTMLButtonElement | null>(null)

  function show(): void {
    open.value = true
  }

  async function restoreFocus(): Promise<void> {
    await nextTick()
    trigger.value?.focus()
  }

  async function close(): Promise<void> {
    open.value = false
    await restoreFocus()
  }

  return { close, open, restoreFocus, show, trigger }
}
