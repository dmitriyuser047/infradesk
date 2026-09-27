import type { FilteredResourceNode } from './resourceFilter'
import type { SourceConnection } from '../../types/infrastructure'
import { ResourceTreeItem } from './ResourceTreeItem'
import { useI18n } from '../../i18n'

interface ResourceTreeProps {
  /** The tree as the filters left it; see `buildFilteredResourceTree`. */
  roots: readonly FilteredResourceNode[]
  organizationId: string
  /** The query each resource link carries; the current page's query by default. */
  linkQuery?: string
  /** When given, a column names the connections each resource was discovered through. */
  sources?: ReadonlyMap<string, readonly SourceConnection[]>
  label?: string
}

export function ResourceTree({ roots, organizationId, linkQuery, sources, label }: ResourceTreeProps) {
  const { t } = useI18n()

  return (
    <div className="resource-tree" role="tree" aria-label={label ?? t.resources.treeLabel}>
      {roots.map((node) => (
        <ResourceTreeItem
          key={node.resource.id}
          node={node}
          depth={0}
          organizationId={organizationId}
          linkQuery={linkQuery}
          sources={sources}
        />
      ))}
    </div>
  )
}
