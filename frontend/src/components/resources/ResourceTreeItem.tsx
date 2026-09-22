import { useState } from 'react'
import { Box, ChevronDown, ChevronRight, Server } from 'lucide-react'

import type { ResourceTreeNode } from './resourceTreeModel'

interface ResourceTreeItemProps {
  node: ResourceTreeNode
  depth: number
}

export function ResourceTreeItem({ node, depth }: ResourceTreeItemProps) {
  const [expanded, setExpanded] = useState(true)
  const hasChildren = node.children.length > 0
  const isNode = node.resource.resourceTypeCode === 'NODE'
  const Icon = isNode ? Server : Box

  return (
    <div className="resource-tree-item" role="treeitem" aria-expanded={hasChildren ? expanded : undefined}>
      <div className="resource-row" style={{ paddingInlineStart: `${depth * 22 + 10}px` }}>
        {hasChildren ? (
          <button
            className="tree-toggle"
            type="button"
            aria-label={`${expanded ? 'Collapse' : 'Expand'} ${node.resource.name}`}
            aria-expanded={expanded}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? <ChevronDown aria-hidden size={16} /> : <ChevronRight aria-hidden size={16} />}
          </button>
        ) : (
          <span className="tree-toggle-placeholder" aria-hidden />
        )}
        <Icon className="resource-icon" aria-hidden size={16} />
        <span className="resource-name">{node.resource.name}</span>
        <span className="resource-code">{node.resource.code}</span>
        <span className={`resource-badge resource-badge-${isNode ? 'node' : 'container'}`}>
          {node.resource.resourceTypeCode}
        </span>
      </div>
      {hasChildren && expanded ? (
        <div role="group">
          {node.children.map((child) => (
            <ResourceTreeItem key={child.resource.id} node={child} depth={depth + 1} />
          ))}
        </div>
      ) : null}
    </div>
  )
}
