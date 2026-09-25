import type { FilteredResourceNode } from './resourceFilter'
import { ResourceTreeItem } from './ResourceTreeItem'
import { useI18n } from '../../i18n'

interface ResourceTreeProps {
  /** The tree as the filters left it; see `buildFilteredResourceTree`. */
  roots: readonly FilteredResourceNode[]
  organizationId: string
  environmentId: string
}

export function ResourceTree({ roots, organizationId, environmentId }: ResourceTreeProps) {
  const { t } = useI18n()

  return (
    <div className="resource-tree" role="tree" aria-label={t.resources.treeLabel}>
      {roots.map((node) => (
        <ResourceTreeItem
          key={node.resource.id}
          node={node}
          depth={0}
          organizationId={organizationId}
          environmentId={environmentId}
        />
      ))}
    </div>
  )
}
