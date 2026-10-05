import { useEffect, useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { useI18n } from '../../i18n'

/** Integration and operation dialogs share focus containment and return focus to their opener. */
export function IntegrationDialog({ title, children, actions, onClose, busy = false, size = 'small', description, actionNote, actionFeedback }: {
  title: string; children: ReactNode; actions: ReactNode; onClose: () => void; busy?: boolean
  size?: 'small' | 'medium' | 'large'; description?: ReactNode; actionNote?: ReactNode; actionFeedback?: ReactNode
}) {
  const { t } = useI18n()
  const titleId = useId()
  const ref = useRef<HTMLElement>(null)
  const close = useRef(onClose); close.current = onClose
  const locked = useRef(busy); locked.current = busy
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const dialog = ref.current
    const focusable = () => Array.from(dialog?.querySelectorAll<HTMLElement>(
      'button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), a[href], summary, [tabindex="0"]') ?? [])
      .filter(item => !item.closest('[hidden], [inert]') && !Array.from(dialog?.querySelectorAll('details:not([open])') ?? [])
        .some(details => details.contains(item) && item !== details.querySelector(':scope > summary')))
    ;(focusable()[0] ?? dialog)?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { event.preventDefault(); if (!locked.current) close.current() }
      if (event.key !== 'Tab') return
      const items = focusable(); const first = items[0]; const last = items[items.length - 1]
      if (!first) { event.preventDefault(); dialog?.focus(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) {
        event.preventDefault(); last.focus()
      } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
    }
    document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('keydown', onKey); if (opener?.isConnected) opener.focus() }
  }, [])
  return createPortal(<div className="dialog-backdrop" role="presentation" onMouseDown={event => {
    if (event.target === event.currentTarget && !busy) onClose()
  }}><section ref={ref} className={`monitor-rule-dialog integration-bind-dialog integration-dialog dialog-${size}`} role="dialog"
    aria-modal="true" aria-labelledby={titleId} aria-busy={busy} tabIndex={-1}>
    <div className="dialog-heading"><div className="dialog-title"><h2 id={titleId}>{title}</h2>{description ? <p className="muted-copy">{description}</p> : null}</div><button type="button" className="dialog-close"
      aria-label={t.common.close} title={t.common.close} disabled={busy} onClick={onClose}>×</button></div>
    <div className="dialog-body">{children}</div><div className="dialog-footer">{actionFeedback}{actionNote ? <p className="dialog-action-note" role="status">{actionNote}</p> : null}<div className="dialog-actions">{actions}</div></div>
  </section></div>, document.body)
}
