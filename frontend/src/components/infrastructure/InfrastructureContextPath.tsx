import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { EnvironmentReference, ProjectReference, ResourceReference, SourceConnection } from '../../types/infrastructure'
import { modulePath } from '../layout/workspaceNavigation'
import { resourcePath } from './infrastructureLinks'

export interface ContextPathItem {
  label: string
  /** Absent for the current page, which is named but not linked. */
  to?: string
}

/**
 * Where the current page sits within its module: source connection, parent resource, current object.
 * It only renders the context it is given and never requests anything; every link is a canonical
 * route, so the path works the same when the page was opened directly.
 */
export function InfrastructureContextPath({ items }: { items: readonly ContextPathItem[] }) {
  const { t } = useI18n()
  if (items.length < 3) return null
  return <nav className="context-path" aria-label={t.infrastructure.pathLabel}>
    <ol>
      {items.map((item, index) => {
        const last = index === items.length - 1
        return <li key={`${index}-${item.label}`}>
          {item.to && !last ? <Link to={item.to}>{item.label}</Link>
            : <span aria-current={last ? 'page' : undefined}>{item.label}</span>}
          {last ? null : <ChevronRight aria-hidden size={12} className="context-path-separator" />}
        </li>
      })}
    </ol>
  </nav>
}

/**
 * Resources always belong to the Servers module in navigation. Discovery sources are
 * related entities on the page; they do not change the object's breadcrumb hierarchy.
 */
export function resourceContextPath(
  organizationId: string,
  context: {
    project: ProjectReference
    environment: EnvironmentReference
    sourceConnections: readonly SourceConnection[]
    parentResource: ResourceReference | null
  },
  tail: readonly ContextPathItem[],
  labels: { resources: string; connections: string },
  /** The query links to the connection and the parent carry, so their "back" returns here. */
  linkQuery = '',
): ContextPathItem[] {
  const { project, environment, parentResource } = context
  const params = new URLSearchParams(linkQuery)
  const scope = { organizationId, projectId: params.get('project') ?? project.id,
    environmentId: params.has('project') ? params.get('environment') : environment.id }
  const items: ContextPathItem[] = [{
    label: labels.resources,
    to: modulePath('resources', scope),
  }]
  if (parentResource) {
    items.push({ label: parentResource.name, to: resourcePath(organizationId, environment.id, parentResource.id, linkQuery) })
  }
  return [...items, ...tail]
}
