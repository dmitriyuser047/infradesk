import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { SourceConnection } from '../../types/infrastructure'
import { connectionPath } from './infrastructureLinks'

/**
 * Every connection a resource was discovered through, each a link. The order is the backend's
 * (by name); none is marked as the primary one, because the model has no such thing.
 */
export function SourceConnectionLinks({ organizationId, sources }: {
  organizationId: string
  sources: readonly SourceConnection[]
}) {
  const i18n = useI18n()
  const t = i18n.t.infrastructure
  if (sources.length === 0) return <span className="muted-cell">{t.noSource}</span>
  return <ul className="source-list">
    {sources.map(source => <li key={source.id}>
      <Link className="property-link" to={connectionPath(organizationId, source.id)} aria-label={t.connectionLink(source.name)}>
        {source.name}</Link>
      <span className="source-meta"> · {i18n.t.connections.connectorTypes[source.connectorType] ?? source.connectorType}
        {source.active ? null : ` · ${t.inactive}`}</span>
    </li>)}
  </ul>
}

/** One line for a dense row: the first source by name, then how many more. Plain text. */
export function sourceSummary(sources: readonly SourceConnection[], moreLabel: (count: number) => string): string | null {
  if (sources.length === 0) return null
  return sources.length === 1 ? sources[0].name : `${sources[0].name} ${moreLabel(sources.length - 1)}`
}
