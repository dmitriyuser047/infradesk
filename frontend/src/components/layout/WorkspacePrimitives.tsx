import { useEffect, useState, type ButtonHTMLAttributes, type FormHTMLAttributes, type ReactNode } from 'react'
import { AlertTriangle, ArrowLeft, Check, CheckCircle2, CircleAlert, Copy, Info, SearchX, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import { describeError } from '../../i18n/errors'

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

/** A detail page while its object loads: the page's own header and way back, then a skeleton. */
export function PageLoading({ title, back, label }: { title: string; back: { label: string; to: string }; label: string }) {
  return <div className="workspace-page" aria-busy="true">
    <WorkspaceHeader title={title} back={back} />
    <div className="row-skeleton" aria-label={label}><span /><span /><span /></div>
  </div>
}

/**
 * A detail page whose object is not there: gone (explained, nothing to retry) or failed to load
 * (a safe message and a retry). The way back stays in both.
 */
export function PageUnavailable({ back, notFound, notFoundTitle, notFoundDetail, errorTitle, error, onRetry }: {
  back: { label: string; to: string }
  notFound: boolean
  notFoundTitle: string
  notFoundDetail?: string
  errorTitle: string
  error: unknown
  onRetry: () => void
}) {
  const i18n = useI18n()
  return <div className="workspace-page">
    <WorkspaceHeader title={notFound ? notFoundTitle : errorTitle} back={back} />
    {notFound ? <EmptyWorkspaceState icon={SearchX} title={notFoundTitle} detail={notFoundDetail} />
      : <InlineAlert tone="danger" title={errorTitle}
        action={<button className="secondary-button" type="button" onClick={onRetry}>{i18n.t.common.retry}</button>}>
        {describeError(error, i18n)}</InlineAlert>}
  </div>
}

export function WorkspaceSection({ title, description, actions, children, className = '', headingId }: {
  title: string
  description?: ReactNode
  actions?: ReactNode
  children: ReactNode
  className?: string
  headingId?: string
}) {
  return <section className={`workspace-section ${className}`}>
    <div className="workspace-section-heading"><h2 id={headingId}>{title}</h2>{actions}</div>
    {description ? <p className="section-description">{description}</p> : null}
    <div className="section-content">{children}</div>
  </section>
}

/**
 * A section card that is itself a form: the same heading, description and padded content as
 * WorkspaceSection, so a form never lays its fields against the card edge. Without a title it is
 * a plain padded card.
 */
export function WorkspaceFormSection({ title, description, descriptionId, actions, children, className = '', contentClassName = '',
  headingId, ...form }: {
  title?: string
  description?: ReactNode
  descriptionId?: string
  actions?: ReactNode
  children: ReactNode
  className?: string
  contentClassName?: string
  headingId?: string
} & Omit<FormHTMLAttributes<HTMLFormElement>, 'className' | 'children' | 'title'>) {
  return <form className={`workspace-section ${className}`} {...form}>
    {title ? <div className="workspace-section-heading"><h2 id={headingId}>{title}</h2>{actions}</div> : null}
    {description ? <p id={descriptionId} className="section-description">{description}</p> : null}
    <div className={`section-content ${contentClassName}`}>{children}</div>
  </form>
}

export function WorkspaceTabs<T extends string>({ tabs, active, onChange }: {
  /** `count`, when known, follows the label as a small badge; the label alone still names the tab. */
  tabs: readonly { id: T; label: string; count?: number; disabled?: boolean }[]
  active: T
  onChange: (value: T) => void
}) {
  const { t } = useI18n()
  const enabledTabs = tabs.filter(tab => !tab.disabled)
  return <div className="workspace-tabs" role="tablist" aria-label={t.common.sections}>
    {tabs.map(tab => <button key={tab.id} id={`tab-${tab.id}`} type="button" role="tab" aria-selected={active === tab.id}
      aria-controls={`panel-${tab.id}`} tabIndex={active === tab.id ? 0 : -1} disabled={tab.disabled}
      className={active === tab.id ? 'workspace-tab active' : 'workspace-tab'} onClick={() => { if (!tab.disabled) onChange(tab.id) }}
      onKeyDown={event => {
        const index = enabledTabs.findIndex(item => item.id === tab.id)
        const next = event.key === 'ArrowRight' ? enabledTabs[(index + 1) % enabledTabs.length]
          : event.key === 'ArrowLeft' ? enabledTabs[(index - 1 + enabledTabs.length) % enabledTabs.length]
            : event.key === 'Home' ? enabledTabs[0] : event.key === 'End' ? enabledTabs[enabledTabs.length - 1] : undefined
        if (next) {
          event.preventDefault()
          onChange(next.id)
          document.getElementById(`tab-${next.id}`)?.focus()
        }
      }}>
      {tab.label}{tab.count === undefined ? null : <> <span className="tab-count">{tab.count}</span></>}
    </button>)}
  </div>
}

export type StatusTone = 'success' | 'danger' | 'info' | 'warning' | 'neutral'

/** A compact summary of the existing read projection; optional actions open the relevant section. */
export function WorkspaceMetrics({ items }: { items: readonly {
  label: string; value: ReactNode; detail?: ReactNode; icon: LucideIcon; tone?: StatusTone; onSelect?: () => void
}[] }) {
  return <div className="workspace-metrics">{items.map(({ label, value, detail, icon: Icon, tone = 'neutral', onSelect }) => {
    const content = <><span className="metric-heading"><span>{label}</span><Icon aria-hidden size={18} /></span>
      <strong className="metric-value">{value}</strong>{detail ? <span className="metric-detail">{detail}</span> : null}</>
    return onSelect ? <button key={label} type="button" className={`workspace-metric metric-${tone}`} onClick={onSelect}>{content}</button>
      : <div key={label} className={`workspace-metric metric-${tone}`}>{content}</div>
  })}</div>
}

/** Both labels occupy the same grid cell, reserving their width before a request starts. */
export function PendingButton({ pending, pendingLabel, children, disabled, className = 'secondary-button', ...button }: {
  pending: boolean; pendingLabel: string; children: ReactNode
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return <button {...button} className={`${className} pending-button`} disabled={disabled || pending} aria-busy={pending}>
    <span className="pending-button-label" data-visible={!pending} aria-hidden={pending}>{children}</span>
    <span className="pending-button-label" data-visible={pending} aria-hidden={!pending}>{pendingLabel}</span>
  </button>
}

/** Unknown operation codes never borrow the meaning of another backend error. */
export function OperationProblem({ code, messages, title, tone = 'danger' }: {
  code: string; messages: Record<string, string>; title: string; tone?: 'danger' | 'warning'
}) {
  const { t } = useI18n()
  return <InlineAlert tone={tone} title={title}>{messages[code] ?? (tone === 'danger' ? t.common.operationBlocked : t.common.operationWarning)}</InlineAlert>
}

/** A status as a badge: text and a mark, colored by tone — the color is never the only signal. */
export function StatusIndicator({ label, tone = 'neutral', size = 'default', icon: Icon }: {
  label: string
  tone?: StatusTone
  size?: 'small' | 'default'
  icon?: LucideIcon
}) {
  return <span className={`status-indicator${size === 'small' ? ' status-indicator-small' : ''} status-${tone}`}>
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

/**
 * Copies a value when pressed, never on its own. The confirmation lasts a moment and is announced;
 * a browser that refuses the clipboard gets a short note instead of an error page.
 */
export function CopyButton({ value, label }: { value: string; label?: string }) {
  const { t } = useI18n()
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle')
  // "Copied" is about the value that was copied; a new value starts over.
  useEffect(() => { setState('idle') }, [value])
  useEffect(() => {
    if (state === 'idle') return undefined
    const timer = window.setTimeout(() => setState('idle'), 2_000)
    return () => window.clearTimeout(timer)
  }, [state])

  const copy = async () => {
    try {
      if (!navigator.clipboard) throw new Error('Clipboard unavailable')
      await navigator.clipboard.writeText(value)
      setState('copied')
    } catch {
      setState('failed')
    }
  }

  return <span className="copy-control">
    <button className="text-button copy-button" type="button" onClick={() => void copy()} aria-label={label ?? t.common.copy}>
      {state === 'copied' ? <Check aria-hidden size={14} /> : <Copy aria-hidden size={14} />}
      {state === 'copied' ? t.common.copied : t.common.copy}
    </button>
    {/* Announced either way; seen only when copying failed, since the button already says "Copied". */}
    <span className={state === 'failed' ? 'copy-feedback' : 'visually-hidden'} role="status">{state === 'failed' ? t.common.copyFailed : state === 'copied' ? t.common.copied : ''}</span>
  </span>
}
