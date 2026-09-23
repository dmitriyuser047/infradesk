import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { Box, ChevronDown, ChevronRight, Server } from 'lucide-react'

import type { ResourceTreeNode } from './resourceTreeModel'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { containerStatusPresentation } from './resourceStatusPresentation'

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
  const destination = `/organizations/${organizationId}/environments/${environmentId}/resources/${node.resource.id}${location.search}`
  const containerStatus = node.resource.data.kind === 'CONTAINER'
    ? containerStatusPresentation(node.resource.data.status?.state) : null

  return (
    <div className="resource-tree-item" role="treeitem" aria-expanded={hasChildren ? expanded : undefined}>
      <div className="resource-row">
        {hasChildren ? (
          <button
            className="tree-toggle"
            type="button"
            aria-label={`${expanded ? 'Collapse' : 'Expand'} ${node.resource.name}`}
            aria-expanded={expanded}
            onClick={() => setExpanded((current) => !current)}
          >
            {expanded ? <ChevronDown aria-hidden size={14} /> : <ChevronRight aria-hidden size={14} />}
          </button>
        ) : (
          <span className="tree-toggle-placeholder" aria-hidden />
        )}
        <Link
          className="resource-link"
          to={destination}
        >
          <span className="resource-name-cell" style={{ paddingInlineStart: `${depth * 20}px` }}>
            <Icon className="resource-icon" aria-hidden size={14} />
            <span className="resource-name-stack"><span className="resource-name">{node.resource.name}</span>
              <small className="resource-code">{node.resource.code}</small></span>
          </span>
          <span className="resource-type-cell">{node.resource.resourceTypeCode}</span>
          <span className="resource-state-cell">{node.resource.data.kind === 'NODE' ?
            <StatusIndicator label={node.resource.data.status?.online === true ? 'Online' :
              node.resource.data.status?.online === false ? 'Offline' : 'Unknown'}
              tone={node.resource.data.status?.online === true ? 'success' :
                node.resource.data.status?.online === false ? 'danger' : 'neutral'} /> :
            <StatusIndicator label={containerStatus?.label ?? 'Unknown'} tone={containerStatus?.tone} />}</span>
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
