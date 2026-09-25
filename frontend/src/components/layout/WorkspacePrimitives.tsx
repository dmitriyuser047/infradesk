import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'

export function WorkspaceHeader({ title, subtitle, back, actions, status }: {
  title: string
  subtitle?: ReactNode
  back?: { label: string; to: string }
  actions?: ReactNode
  status?: ReactNode
}) {
  return <header className="workspace-header">
    <div className="workspace-heading">
      {back ? <Link className="workspace-back" to={back.to}>← {back.label}</Link> : null}
      <div className="workspace-title-line"><h1>{title}</h1>{status}</div>
      {subtitle ? <p className="workspace-subtitle">{subtitle}</p> : null}
    </div>
    {actions ? <div className="workspace-toolbar">{actions}</div> : null}
  </header>
}

export function WorkspaceSection({ title, actions, children, className = '' }: {
  title: string
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return <section className={`workspace-section ${className}`}>
    <div className="workspace-section-heading"><h2>{title}</h2>{actions}</div>
    {children}
  </section>
}

export function WorkspaceTabs<T extends string>({ tabs, active, onChange }: {
  tabs: readonly { id: T; label: string }[]
  active: T
  onChange: (value: T) => void
}) {
  const { t } = useI18n()
  return <div className="workspace-tabs" role="tablist" aria-label={t.common.sections}>
    {tabs.map((tab, index) => <button key={tab.id} id={`tab-${tab.id}`} type="button" role="tab" aria-selected={active === tab.id}
      aria-controls={`panel-${tab.id}`} tabIndex={active === tab.id ? 0 : -1}
      className={active === tab.id ? 'workspace-tab active' : 'workspace-tab'} onClick={() => onChange(tab.id)}
      onKeyDown={event => {
        const next = event.key === 'ArrowRight' ? tabs[(index + 1) % tabs.length]
          : event.key === 'ArrowLeft' ? tabs[(index - 1 + tabs.length) % tabs.length]
            : event.key === 'Home' ? tabs[0] : event.key === 'End' ? tabs[tabs.length - 1] : undefined
        if (next) {
          event.preventDefault()
          onChange(next.id)
          document.getElementById(`tab-${next.id}`)?.focus()
        }
      }}>
      {tab.label}
    </button>)}
  </div>
}

export type StatusTone = 'success' | 'danger' | 'info' | 'warning' | 'neutral'

export function StatusIndicator({ label, tone = 'neutral' }: {
  label: string
  tone?: StatusTone
}) {
  return <span className={`status-indicator status-${tone}`}><span className="status-dot" aria-hidden />{label}</span>
}

export function PropertyGrid({ items }: { items: readonly { label: string; value: ReactNode }[] }) {
  return <dl className="property-grid">{items.map(item =>
    <div className="property-row" key={item.label}><dt>{item.label}</dt><dd>{item.value}</dd></div>)}</dl>
}

export function EmptyWorkspaceState({ title, detail, action }: {
  title: string
  detail?: string
  action?: ReactNode
}) {
  return <div className="empty-workspace"><strong>{title}</strong>{detail ? <p>{detail}</p> : null}{action}</div>
}
