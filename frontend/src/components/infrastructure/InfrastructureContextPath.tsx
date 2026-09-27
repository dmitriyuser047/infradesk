import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'

import { useI18n } from '../../i18n'
import type { EnvironmentReference, ProjectReference, ResourceReference, SourceConnection } from '../../types/infrastructure'
import { connectionPath, environmentPath, projectPath, resourcePath } from './infrastructureLinks'

export interface ContextPathItem {
  label: string
  /** Absent for the current page, which is named but not linked. */
  to?: string
}

/**
 * Where the current page sits in the infrastructure: project, environment, connection, resource.
 * It only renders the context it is given and never requests anything; every link is a canonical
 * route, so the path works the same when the page was opened directly.
 */
export function InfrastructureContextPath({ items }: { items: readonly ContextPathItem[] }) {
  const { t } = useI18n()
  if (items.length === 0) return null
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
 * The path of something that lives on a resource: project, environment, the connection that
 * discovered it when there is exactly one, the parent, then the given tail. With several sources
 * none is pretended to be the one: they are listed elsewhere on the page instead.
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
  /** The query links to the connection and the parent carry, so their "back" returns here. */
  linkQuery = '',
): ContextPathItem[] {
  const { project, environment, sourceConnections, parentResource } = context
  const items: ContextPathItem[] = [
    { label: project.name, to: projectPath(organizationId, project.id) },
    { label: environment.name, to: environmentPath(organizationId, project.id, environment.id) },
  ]
  if (sourceConnections.length === 1) {
    items.push({ label: sourceConnections[0].name, to: connectionPath(organizationId, sourceConnections[0].id, linkQuery) })
  }
  if (parentResource) {
    items.push({ label: parentResource.name, to: resourcePath(organizationId, environment.id, parentResource.id, linkQuery) })
  }
  return [...items, ...tail]
}
