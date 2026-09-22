import type { ResourceResponse } from '../../types/resource'
import { buildResourceTree } from './resourceTreeModel'
import { ResourceTreeItem } from './ResourceTreeItem'

interface ResourceTreeProps {
  resources: readonly ResourceResponse[]
}

export function ResourceTree({ resources }: ResourceTreeProps) {
  const tree = buildResourceTree(resources)

  return (
    <div className="resource-tree" role="tree" aria-label="Infrastructure resources">
      {tree.map((node) => (
        <ResourceTreeItem key={node.resource.id} node={node} depth={0} />
      ))}
    </div>
  )
}
