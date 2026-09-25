import { Link } from 'react-router-dom'

import type { HistoryEventResponse } from '../../types/historyEvent'
import { getHistoryActorLabel, getHistoryEventPresentation, getHistorySubjectLink } from './historyEventPresentation'

/**
 * Timeline entries, newest first, in the one presentation every timeline shares.
 *
 * A timeline that spans several objects names the object of each entry and links to it; a
 * resource's own timeline does not repeat the resource.
 */
export function ActivityTimeline({ events, organizationId }: {
  events: readonly HistoryEventResponse[]
  organizationId?: string
}) {
  return <ol className="activity-timeline">
    {events.map(event => {
      const presentation = getHistoryEventPresentation(event)
      const subject = organizationId === undefined ? null : getHistorySubjectLink(organizationId, event)
      return <li key={event.id} className={`activity-entry activity-${presentation.tone}`}>
        <span className="activity-time">{new Date(event.occurredAt).toLocaleString()}</span>
        <span className="activity-title">{presentation.title}</span>
        {subject !== null || presentation.detail !== null ? <span className="activity-detail">
          {subject !== null ? <Link className="grid-link" to={subject.to}>{subject.label}</Link> : null}
          {subject !== null && presentation.detail !== null ? ' · ' : null}
          {presentation.detail}
        </span> : null}
        <span className="activity-actor">{getHistoryActorLabel(event)}</span>
      </li>
    })}
  </ol>
}
