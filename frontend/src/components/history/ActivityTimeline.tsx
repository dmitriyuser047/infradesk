import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { HistoryEventResponse } from '../../types/historyEvent'
import { getHistoryActorLabel, getHistoryEventPresentation, getHistorySubjectLink } from './historyEventPresentation'

/**
 * Timeline entries, newest first, in the one presentation every timeline shares: what happened
 * and when on the first line, the object, the detail and who did it on the second.
 *
 * A timeline that spans several objects names the object of each entry and links to it; a
 * resource's own timeline does not repeat the resource.
 */
export function ActivityTimeline({ events, organizationId }: {
  events: readonly HistoryEventResponse[]
  organizationId?: string
}) {
  const i18n = useI18n()
  return <ol className="activity-timeline">
    {events.map(event => {
      const presentation = getHistoryEventPresentation(event, i18n)
      const subject = organizationId === undefined ? null : getHistorySubjectLink(organizationId, event)
      return <li key={event.id} className={`activity-entry activity-${presentation.tone}`}>
        <span className="activity-marker" aria-hidden />
        <div className="activity-body">
          <div className="activity-heading">
            <span className="activity-title">{presentation.title}</span>
            <time className="activity-time" dateTime={event.occurredAt} title={i18n.format.dateTime(event.occurredAt)}>
              {i18n.format.relative(event.occurredAt)}</time>
          </div>
          <div className="activity-secondary">
            {subject !== null ? <Link className="activity-subject" to={subject.to} title={subject.label}>{subject.label}</Link> : null}
            {presentation.detail !== null ? <span className="activity-detail">{presentation.detail}</span> : null}
            <span className="activity-actor">{getHistoryActorLabel(event, i18n)}</span>
          </div>
        </div>
      </li>
    })}
  </ol>
}
