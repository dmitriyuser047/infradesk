import { CheckCircle2, CircleHelp, Cable, TriangleAlert } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useI18n } from '../../i18n'
import type { OverviewHealthPresentation } from './overviewPresentation'

const icons = { normal: CheckCircle2, warning: TriangleAlert, unknown: CircleHelp, empty: Cable }

export function OverviewHealth({ health, updatedAt, stale, connectionPath, hasAttention }: {
  health: OverviewHealthPresentation
  updatedAt?: number
  stale: boolean
  connectionPath?: string
  hasAttention: boolean
}) {
  const i18n = useI18n()
  const t = i18n.t.overview.health
  const Icon = icons[health.state]
  return <section className={`overview-health overview-health-${health.tone}`} data-health={health.state}
    aria-labelledby="overview-health-title">
    <Icon className="overview-health-icon" aria-hidden size={24} />
    <div className="overview-health-body">
      <h2 id="overview-health-title">{health.title}</h2>
      <p>{health.detail}</p>
      {updatedAt ? <small>{stale ? t.lastKnown : t.checked} <time dateTime={new Date(updatedAt).toISOString()}
        title={i18n.format.dateTime(new Date(updatedAt).toISOString())}>
        {i18n.format.dateTime(new Date(updatedAt).toISOString())}</time></small> : null}
    </div>
    {health.state === 'warning' && hasAttention ? <a className="secondary-button" href="#overview-attention">{t.viewProblems}</a> : null}
    {health.state === 'empty' && connectionPath ? <Link className="secondary-button" to={connectionPath}>{t.addConnection}</Link> : null}
  </section>
}
