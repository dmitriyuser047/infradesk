import { useCallback, useEffect, useId, useRef, useState } from 'react'

/**
 * Open/close state of a popover anchored to a trigger button.
 *
 * Escape and a click outside close it; closing with the keyboard returns focus to the trigger, so
 * the popover is usable without a mouse. Shared by the context switcher and the account menu.
 */
export function usePopover<Trigger extends HTMLElement = HTMLButtonElement>() {
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<Trigger>(null)
  const panelRef = useRef<HTMLDivElement>(null)
  const panelId = useId()

  const close = useCallback((restoreFocus = true) => {
    setOpen(false)
    if (restoreFocus) triggerRef.current?.focus()
  }, [])

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close()
      }
    }
    const onPointerDown = (event: MouseEvent) => {
      const target = event.target as Node
      if (!panelRef.current?.contains(target) && !triggerRef.current?.contains(target)) close(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [open, close])

  return {
    open,
    toggle: () => setOpen(value => !value),
    close,
    triggerRef,
    panelRef,
    triggerProps: { 'aria-expanded': open, 'aria-controls': panelId },
    panelId,
  }
}
