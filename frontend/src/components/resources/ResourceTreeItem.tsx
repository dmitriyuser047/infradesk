import { useId, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { ChevronDown, ChevronRight } from 'lucide-react'

import type { FilteredResourceNode } from './resourceFilter'
import type { SourceConnection } from '../../types/infrastructure'
import { resourcePath } from '../infrastructure/infrastructureLinks'
import { sourceSummary } from '../infrastructure/SourceConnections'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { resourceDisplayName } from './resourceInventoryPresentation'
import { resourcePresentationRegistry } from './presentation/resourcePresentations'
import { formatPercent } from '../metrics/formatters'
import { useI18n } from '../../i18n'

interface ResourceTreeItemProps {
  node: FilteredResourceNode
  depth: number
  organizationId: string
  linkQuery?: string
  inventory?: ReadonlyMap<string, number>
  sources?: ReadonlyMap<string, readonly SourceConnection[]>
}

export function ResourceTreeItem({
  node,
  depth,
  organizationId,
  linkQuery,
  sources,
  inventory,
}: ResourceTreeItemProps) {
  const i18n = useI18n()
  const [expanded, setExpanded] = useState(true)
  const detailsId = useId()
  const location = useLocation()
  const hasChildren = node.children.length > 0
  const presentation = resourcePresentationRegistry.resolve(node.resource.resourceTypeCode)
  const Icon = presentation.Icon
  // The resource's own environment: one tree may span several, as a connection's does.
  const destination = resourcePath(organizationId, node.resource.environmentId, node.resource.id, linkQuery ?? location.search)
  const source = sources ? sourceSummary(sources.get(node.resource.id) ?? [], i18n.t.infrastructure.moreSources) : undefined
  const status = node.resource.active ? presentation.rowStatus(node.resource, i18n)
    : { label: i18n.t.resources.page.inactive, tone: 'neutral' as const }
  const name = resourceDisplayName(node.resource)
  const data = node.resource.data
  const secondary = data?.kind === 'NODE' ? data.spec?.hostname : data?.kind === 'CONTAINER' ? data.spec?.image : undefined
  const nodeStatus = data?.kind === 'NODE' ? data.status : undefined
  const containers = inventory?.get(node.resource.id) ?? 0
  const details = inventory ? [
    `${i18n.t.resources.node.cpu}: ${formatPercent(nodeStatus?.cpuUsagePercent ?? null, i18n)}`,
    `${i18n.t.resources.node.memoryUsage}: ${formatPercent(nodeStatus?.memoryUsagePercent ?? null, i18n)}`,
    data?.kind === 'NODE' ? `${i18n.t.workScreens.containers}: ${containers}` : null,
    node.resource.updatedAt ? `${i18n.t.workScreens.updated}: ${i18n.format.dateTime(node.resource.updatedAt)}` : null,
    source,
  ].filter(Boolean).join(' · ') : undefined

  return (
    <div className="resource-tree-item" role="treeitem" aria-expanded={hasChildren ? expanded : undefined}>
      {/* A parent kept only for its matching children reads as context, not as a result. */}
      <div className={node.matched ? 'resource-row' : 'resource-row resource-row-context'}>
        {hasChildren ? (
          <button
            className="tree-toggle"
            type="button"
            aria-label={expanded ? i18n.t.resources.collapse(name) : i18n.t.resources.expand(name)}
            title={expanded ? i18n.t.resources.collapse(name) : i18n.t.resources.expand(name)}
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
          aria-label={`${i18n.t.infrastructure.resourceLink(name)} · ${status.label}${node.matched ? '' : ` ${i18n.t.resources.filter.contextHint}`}`}
          aria-describedby={details ? detailsId : undefined}
        >
          <span className="resource-name-cell" style={{ paddingInlineStart: `${depth * 20}px` }}>
            <Icon className="resource-icon" aria-hidden size={14} />
            <span className="resource-name-stack"><span className="resource-name">{name}
              {node.matched ? null : <span className="visually-hidden"> {i18n.t.resources.filter.contextHint}</span>}</span>
              <small className="resource-code">{secondary && secondary !== name ? secondary : node.resource.code}</small></span>
          </span>
          <span className="resource-type-cell">{i18n.t.resources.types[node.resource.resourceTypeCode] ?? node.resource.resourceTypeCode}</span>
          <span className="resource-state-cell">
            <StatusIndicator label={status.label} tone={status.tone} /></span>
          {sources ? <span className="resource-source-cell">{source ?? <span className="muted-cell">—</span>}</span> : null}
          {inventory ? <>
            <span className="numeric-cell">{formatPercent(nodeStatus?.cpuUsagePercent ?? null, i18n)}</span>
            <span className="numeric-cell">{formatPercent(nodeStatus?.memoryUsagePercent ?? null, i18n)}</span>
            <span className="numeric-cell">{data?.kind === 'NODE' ? containers : '—'}</span>
            <span className="resource-updated">{node.resource.updatedAt ? <time dateTime={node.resource.updatedAt}>{i18n.format.dateTime(node.resource.updatedAt)}</time> : '—'}</span>
          </> : null}
        </Link>
        {details ? <span id={detailsId} className="visually-hidden">{details}</span> : null}
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
              inventory={inventory}
            />
          ))}
        </div>
      ) : null}
    </div>
  )
}
