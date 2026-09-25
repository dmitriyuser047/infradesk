import type { ReactNode } from 'react'
import { AlertTriangle, ArrowLeft, CheckCircle2, CircleAlert, Info, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'

/**
 * The few building blocks every screen shares: page header, section card, tabs, status badge,
 * property list, empty state and inline alert. Anything used by one screen only stays in it.
 */

export function WorkspaceHeader({ title, subtitle, back, actions, status }: {
  title: string
  subtitle?: ReactNode
  back?: { label: string; to: string }
  actions?: ReactNode
  status?: ReactNode
}) {
  return <header className="workspace-header">
    <div className="workspace-heading">
      {back ? <Link className="workspace-back" to={back.to}><ArrowLeft aria-hidden size={14} />{back.label}</Link> : null}
      <div className="workspace-title-line"><h1>{title}</h1>{status}</div>
      {subtitle ? <p className="workspace-subtitle">{subtitle}</p> : null}
    </div>
    {actions ? <div className="workspace-toolbar">{actions}</div> : null}
  </header>
}

export function WorkspaceSection({ title, description, actions, children, className = '' }: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
}) {
  return <section className={`workspace-section ${className}`}>
    <div className="workspace-section-heading"><h2>{title}</h2>{actions}</div>
    {description ? <p className="section-description">{description}</p> : null}
    <div className="section-content">{children}</div>
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

/** A status as a badge: text and a mark, colored by tone — the color is never the only signal. */
export function StatusIndicator({ label, tone = 'neutral', icon: Icon }: {
  label: string
  tone?: StatusTone
  icon?: LucideIcon
}) {
  return <span className={`status-indicator status-${tone}`}>
    {Icon ? <Icon aria-hidden size={13} /> : <span className="status-dot" aria-hidden />}{label}
  </span>
}

/**
 * Label and value pairs. A technical value (a hostname, a kernel version, an image) is set in
 * monospace; long values wrap inside their cell instead of widening the page.
 */
export function PropertyGrid({ items, columns = 1 }: {
  items: readonly { label: string; value: ReactNode; technical?: boolean }[]
  /** Two columns spread a long list on a wide screen; narrow screens always use one. */
  columns?: 1 | 2
}) {
  return <dl className={columns === 2 ? 'property-grid property-grid-2' : 'property-grid'}>{items.map(item =>
    <div className="property-row" key={item.label}><dt>{item.label}</dt>
      <dd className={item.technical ? 'property-technical' : undefined}>{item.value}</dd></div>)}</dl>
}

/**
 * What an empty list means and what to do next; a healthy "nothing wrong" state uses the success tone.
 * The compact form is one line inside a section that has more to show around it.
 */
export function EmptyWorkspaceState({ title, detail, action, icon: Icon, tone = 'neutral', compact = false }: {
  title: string
  detail?: string
  action?: ReactNode
  icon?: LucideIcon
  tone?: 'neutral' | 'success'
  compact?: boolean
}) {
  const className = ['empty-workspace', tone === 'success' ? 'empty-success' : '', compact ? 'empty-compact' : '']
    .filter(Boolean).join(' ')
  return <div className={className} role={tone === 'success' ? 'status' : undefined}>
    {Icon ? <Icon className="empty-workspace-icon" aria-hidden size={compact ? 18 : 22} /> : null}
    {compact ? <span className="empty-compact-text"><strong>{title}</strong>{detail ? <span>{detail}</span> : null}</span>
      : <><strong>{title}</strong>{detail ? <p>{detail}</p> : null}</>}
    {action}
  </div>
}

const alertIcons: Record<'danger' | 'warning' | 'info' | 'success', LucideIcon> = {
  danger: CircleAlert, warning: AlertTriangle, info: Info, success: CheckCircle2,
}

/** A message inside the page: an error with a retry, a warning, or a confirmation. */
export function InlineAlert({ tone, title, children, action }: {
  tone: 'danger' | 'warning' | 'info' | 'success'
  title?: string
  children?: ReactNode
  action?: ReactNode
}) {
  const Icon = alertIcons[tone]
  return <div className={`inline-alert alert-${tone}`} role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}>
    <Icon aria-hidden size={18} />
    <div className="inline-alert-body">{title ? <strong>{title}</strong> : null}{children ? <p>{children}</p> : null}</div>
    {action ? <div className="inline-alert-action">{action}</div> : null}
  </div>
}

/**
 * A choice of one among a few, drawn as segments. Native radio buttons underneath: arrow keys move
 * between them and a screen reader announces the group and the checked option.
 */
export function SegmentedControl<T extends string>({ name, label, options, value, onChange }: {
  name: string
  label: string
  options: readonly { value: T; label: string }[]
  value: T
  onChange: (value: T) => void
}) {
  return <fieldset className="segmented" aria-label={label}>
    {options.map(option => <label key={option.value} className={`segmented-option ${value === option.value ? 'selected' : ''}`}>
      <input type="radio" name={name} value={option.value} checked={value === option.value} onChange={() => onChange(option.value)} />
      {option.label}
    </label>)}
  </fieldset>
}
