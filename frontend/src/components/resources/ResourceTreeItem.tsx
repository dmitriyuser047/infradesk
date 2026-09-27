import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ChevronDown, ChevronRight } from 'lucide-react'

import type { FilteredResourceNode } from './resourceFilter'
import type { SourceConnection } from '../../types/infrastructure'
import { resourcePath } from '../infrastructure/infrastructureLinks'
import { sourceSummary } from '../infrastructure/SourceConnections'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { resourcePresentationRegistry } from './presentation/resourcePresentations'
import { useI18n } from '../../i18n'

interface ResourceTreeItemProps {
  node: FilteredResourceNode
  depth: number
  organizationId: string
  linkQuery?: string
  sources?: ReadonlyMap<string, readonly SourceConnection[]>
}

export function ResourceTreeItem({
  node,
  depth,
  organizationId,
  linkQuery,
  sources,
}: ResourceTreeItemProps) {
  const i18n = useI18n()
  const [expanded, setExpanded] = useState(true)
  const location = useLocation()
  const hasChildren = node.children.length > 0
  const presentation = resourcePresentationRegistry.resolve(node.resource.resourceTypeCode)
  const Icon = presentation.Icon
  // The resource's own environment: one tree may span several, as a connection's does.
  const destination = resourcePath(organizationId, node.resource.environmentId, node.resource.id, linkQuery ?? location.search)
  const source = sources ? sourceSummary(sources.get(node.resource.id) ?? [], i18n.t.infrastructure.moreSources) : undefined
  const status = presentation.rowStatus(node.resource, i18n)

  return (
    <div className="resource-tree-item" role="treeitem" aria-expanded={hasChildren ? expanded : undefined}>
      {/* A parent kept only for its matching children reads as context, not as a result. */}
      <div className={node.matched ? 'resource-row' : 'resource-row resource-row-context'}>
        {hasChildren ? (
          <button
            className="tree-toggle"
            type="button"
            aria-label={expanded ? i18n.t.resources.collapse(node.resource.name) : i18n.t.resources.expand(node.resource.name)}
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
            <span className="resource-name-stack"><span className="resource-name">{node.resource.name}
              {node.matched ? null : <span className="visually-hidden"> {i18n.t.resources.filter.contextHint}</span>}</span>
              <small className="resource-code">{node.resource.code}</small></span>
          </span>
          <span className="resource-type-cell">{i18n.t.resources.types[node.resource.resourceTypeCode] ?? node.resource.resourceTypeCode}</span>
          <span className="resource-state-cell">
            <StatusIndicator label={status.label} tone={status.tone} /></span>
          {sources ? <span className="resource-source-cell">{source ?? <span className="muted-cell">—</span>}</span> : null}
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
              linkQuery={linkQuery}
              sources={sources}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}
