import { Server } from 'lucide-react'

import { PropertyGrid, StatusIndicator, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { formatDuration, formatMemoryMb, formatPercent } from '../../metrics/formatters'
import type { ResourcePresentation, ResourcePresentationProps, ResourceStatusPresentation } from './ResourcePresentation'
import type { NodeResourceData, ResourceResponse } from '../../../types/resource'

/**
 * Narrows the resource payload to a node. The backend sends `kind` alongside the resource type
 * code, so a payload that does not match is shown as unavailable instead of crashing the page.
 */
function nodeData(resource: ResourceResponse): NodeResourceData | null {
  return resource.data?.kind === 'NODE' ? resource.data : null
}

function nodeStatus(resource: ResourceResponse): ResourceStatusPresentation {
  const online = nodeData(resource)?.status?.online
  return {
    label: online === true ? 'Online' : online === false ? 'Offline' : 'Unknown',
    tone: online === true ? 'success' : online === false ? 'danger' : 'neutral',
  }
}

function NodeOverview({ resource }: ResourcePresentationProps) {
  const data = nodeData(resource)
  const spec = data?.spec
  const status = data?.status
  return <div className="workspace-split detail-split">
    <WorkspaceSection title="Properties"><PropertyGrid items={[
      { label: 'Code', value: resource.code },
      { label: 'Hostname', value: spec?.hostname ?? '—' },
      { label: 'Operating system', value: spec?.operatingSystem ?? '—' },
      { label: 'Distribution', value: spec?.distribution ?? '—' },
      { label: 'Kernel', value: spec?.kernelVersion ?? '—' },
      { label: 'Architecture', value: spec?.architecture ?? '—' },
      { label: 'CPU model', value: spec?.cpuModel ?? '—' },
      { label: 'CPU cores', value: spec?.cpuCores ?? '—' },
      { label: 'Memory', value: formatMemoryMb(spec?.memoryMb ?? null) },
    ]} /></WorkspaceSection>
    <WorkspaceSection title="Current state"><PropertyGrid items={[
      { label: 'Status', value: <StatusIndicator label={status?.online === true ? 'Online' : status?.online === false ? 'Offline' : 'Unknown'}
        tone={status?.online === true ? 'success' : status?.online === false ? 'danger' : 'neutral'} /> },
      { label: 'CPU', value: formatPercent(status?.cpuUsagePercent ?? null) },
      { label: 'Memory', value: formatPercent(status?.memoryUsagePercent ?? null) },
      { label: 'Uptime', value: formatDuration(status?.uptimeSeconds ?? null) },
    ]} /></WorkspaceSection>
  </div>
}

function NodeMetricSummary({ resource }: ResourcePresentationProps) {
  const status = nodeData(resource)?.status
  return <div className="metric-strip">
    <div><span>CPU</span><strong>{formatPercent(status?.cpuUsagePercent ?? null)}</strong></div>
    <div><span>Memory</span><strong>{formatPercent(status?.memoryUsagePercent ?? null)}</strong></div>
  </div>
}

export const nodePresentation: ResourcePresentation = {
  code: 'NODE',
  label: 'Node',
  Icon: Server,
  rowStatus: nodeStatus,
  headerStatus: nodeStatus,
  Overview: NodeOverview,
  MetricSummary: NodeMetricSummary,
}
