import { useEffect, useId, useRef, useState } from 'react'
import { MoreHorizontal } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useI18n } from '../../i18n'

export interface PageAction {
  label: string
  to?: string
  onSelect?: () => void
  danger?: boolean
  disabled?: boolean
}

/** A small action menu; callers filter actions using their existing permissions. */
export function PageActionMenu({ actions }: { actions: readonly PageAction[] }) {
  const { t } = useI18n()
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState({ top: 0, left: 0 })
  const trigger = useRef<HTMLButtonElement>(null)
  const root = useRef<HTMLDivElement>(null)
  const id = useId()
  const close = () => { setOpen(false); trigger.current?.focus() }
  useEffect(() => {
    if (!open) return
    root.current?.querySelector<HTMLElement>('[role="menuitem"]:not(:disabled)')?.focus()
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', outside)
    const moved = (event: Event) => {
      if (!root.current?.contains(event.target as Node)) setOpen(false)
    }
    window.addEventListener('resize', moved)
    document.addEventListener('scroll', moved, true)
    return () => {
      document.removeEventListener('pointerdown', outside)
      window.removeEventListener('resize', moved)
      document.removeEventListener('scroll', moved, true)
    }
  }, [open])
  if (!actions.length) return null
  return <div className="page-action-menu" ref={root} onClick={event => event.stopPropagation()}
    onBlur={event => { if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false) }}
    onKeyDown={event => {
      if (event.key === 'Escape' && open) { event.preventDefault(); event.stopPropagation(); close() }
      if (!open || !['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) return
      event.preventDefault()
      const items = [...(root.current?.querySelectorAll<HTMLElement>('[role="menuitem"]:not(:disabled)') ?? [])]
      const current = items.indexOf(document.activeElement as HTMLElement)
      const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1
        : (current + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length
      items[next]?.focus()
    }}>
    <button ref={trigger} className="icon-button" type="button" aria-label={t.workScreens.actions}
      title={t.workScreens.actions} aria-haspopup="menu" aria-expanded={open} aria-controls={open ? id : undefined}
      onClick={() => {
        const rect = trigger.current?.getBoundingClientRect()
        if (rect) setPosition({ left: Math.max(8, rect.right - 220),
          top: Math.max(8, Math.min(rect.bottom + 4, window.innerHeight - actions.length * 44 - 24)) })
        setOpen(value => !value)
      }}><MoreHorizontal aria-hidden size={18} /></button>
    {open ? <div id={id} role="menu" aria-label={t.workScreens.actions} className="page-action-popup" style={position}>
      {actions.map(action => action.to && !action.disabled
        ? <Link key={action.label} role="menuitem" className={action.danger ? 'danger-action' : undefined}
          to={action.to} onClick={close}>{action.label}</Link>
        : <button key={action.label} role="menuitem" type="button" disabled={action.disabled}
          className={action.danger ? 'danger-action' : undefined}
          onClick={() => { close(); action.onSelect?.() }}>{action.label}</button>)}
    </div> : null}
  </div>
}
