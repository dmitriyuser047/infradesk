import type { ResourceResponse } from '../../types/resource'
import { buildResourceTree } from './resourceTreeModel'
import { ResourceTreeItem } from './ResourceTreeItem'
import { useI18n } from '../../i18n'

interface ResourceTreeProps {
  resources: readonly ResourceResponse[]
  organizationId: string
  environmentId: string
}

export function ResourceTree({ resources, organizationId, environmentId }: ResourceTreeProps) {
  const { t } = useI18n()
  const tree = buildResourceTree(resources)

  return (
    <div className="resource-tree" role="tree" aria-label={t.resources.treeLabel}>
      {tree.map((node) => (
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
