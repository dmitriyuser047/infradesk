import { Link } from 'react-router-dom'
import { useI18n } from '../../i18n'
import type { HistoryEventResponse } from '../../types/historyEvent'
import { getHistoryActorLabel, getHistoryEventPresentation, getHistorySubjectLink } from '../history/historyEventPresentation'
import { withWorkspaceContext } from '../layout/workspaceNavigation'
import { getOverviewActivityEntries } from './overviewActivityPresentation'

export const OverviewActivityLimit = 8

/** A bounded history, separate from current problems. Discovery is a neutral fact here. */
export function OverviewActivity({ events, organizationId, context }: {
  events: readonly HistoryEventResponse[]; organizationId: string; context: string
}) {
  const i18n = useI18n()
  return <ol className="activity-timeline overview-activity">
    {getOverviewActivityEntries(events).slice(0, OverviewActivityLimit).map(entry => {
      const event = entry.event
      const presentation = getHistoryEventPresentation(event, i18n)
      const grouped = entry.events.length > 1
      const subject = grouped && event.connection && event.sync ? {
        label: event.connection.name,
        to: `/organizations/${encodeURIComponent(organizationId)}/connections/${encodeURIComponent(event.connection.id)}/sync-sessions/${encodeURIComponent(event.sync.id)}`,
      } : getHistorySubjectLink(organizationId, event)
      const counts = [
        entry.events.filter(item => item.eventType === 'RESOURCE_DISCOVERED').length,
        entry.events.filter(item => item.eventType === 'RESOURCE_DEACTIVATED').length,
      ]
      const detail = grouped ? i18n.t.overview.activityGroup.detail(counts[0], counts[1]) : presentation.detail
      const discovery = event.eventType === 'RESOURCE_DISCOVERED' || event.eventType === 'RESOURCE_DEACTIVATED'
      const tone = discovery ? 'neutral' : presentation.tone
      return <li key={event.id} className={`activity-entry activity-${tone} ${discovery ? 'activity-discovery' : ''}`}>
        <span className="activity-marker" aria-hidden />
        <div className="activity-body">
          <div className="activity-heading">
            <span className="activity-title">{grouped ? i18n.t.overview.activityGroup.title : presentation.title}</span>
            <time className="activity-time" dateTime={event.occurredAt} title={i18n.format.dateTime(event.occurredAt)}>
              {i18n.format.relative(event.occurredAt)}</time>
          </div>
          <div className="activity-secondary">
            {subject ? <Link className="activity-subject" to={withWorkspaceContext(subject.to, new URLSearchParams(context))}>{subject.label}</Link> : null}
            {detail ? <span className="activity-detail">{detail}</span> : null}
            <span className="activity-actor">{getHistoryActorLabel(event, i18n)}</span>
          </div>
        </div>
      </li>
    })}
  </ol>
}
