import { useState } from 'react'
import { Link } from 'react-router-dom'
import { Box, ChevronDown, ChevronRight, Server } from 'lucide-react'

import type { ResourceTreeNode } from './resourceTreeModel'

interface ResourceTreeItemProps {
  node: ResourceTreeNode
  depth: number
  organizationId: string
  environmentId: string
}

export function ResourceTreeItem({
  node,
  depth,
  organizationId,
  environmentId,
}: ResourceTreeItemProps) {
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
        <Link
          className="resource-link"
          to={`/organizations/${organizationId}/environments/${environmentId}/resources/${node.resource.id}`}
        >
          <Icon className="resource-icon" aria-hidden size={16} />
          <span className="resource-name">{node.resource.name}</span>
          <span className="resource-code">{node.resource.code}</span>
          <span className={`resource-badge resource-badge-${isNode ? 'node' : 'container'}`}>
            {node.resource.resourceTypeCode}
          </span>
        </Link>
      </div>
      {hasChildren && expanded ? (
        <div role="group">
          {node.children.map((child) => (
            <ResourceTreeItem
              key={child.resource.id}
              node={child}
              depth={depth + 1}
              organizationId={organizationId}
              environmentId={environmentId}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}
