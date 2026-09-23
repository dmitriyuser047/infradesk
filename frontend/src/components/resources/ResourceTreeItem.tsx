import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Box, ChevronDown, ChevronRight, Server } from 'lucide-react'

import type { ResourceTreeNode } from './resourceTreeModel'
import { StatusIndicator } from '../layout/WorkspacePrimitives'

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
  const location = useLocation()
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
          to={`/organizations/${organizationId}/environments/${environmentId}/resources/${node.resource.id}${location.search}`}
        >
          <Icon className="resource-icon" aria-hidden size={16} />
          <span className="resource-name">{node.resource.name}</span>
          <span className="resource-code">{node.resource.code}</span>
          <span className="resource-type-cell">{node.resource.resourceTypeCode}</span>
          <span className="resource-state-cell">{node.resource.data.kind === 'NODE' ?
            <StatusIndicator label={node.resource.data.status?.online === true ? 'Online' :
              node.resource.data.status?.online === false ? 'Offline' : 'Unknown'}
              tone={node.resource.data.status?.online === true ? 'success' :
                node.resource.data.status?.online === false ? 'danger' : 'neutral'} /> :
            <StatusIndicator label={node.resource.data.status?.state ?? 'Unknown'} />}</span>
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
