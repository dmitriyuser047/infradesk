import { Server } from 'lucide-react'

import { useI18n, type I18n } from '../../../i18n'
import { PropertyGrid, StatusIndicator, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { formatMemoryMb, formatPercent } from '../../metrics/formatters'
import type { ResourcePresentation, ResourcePresentationProps, ResourceStatusPresentation } from './ResourcePresentation'
import type { NodeResourceData, ResourceResponse } from '../../../types/resource'

/**
 * Narrows the resource payload to a node. The backend sends `kind` alongside the resource type
 * code, so a payload that does not match is shown as unavailable instead of crashing the page.
 */
function nodeData(resource: ResourceResponse): NodeResourceData | null {
  return resource.data?.kind === 'NODE' ? resource.data : null
}

/** The `online` flag inventory stored; nothing is inferred from how long ago it was seen. */
function nodeStatus(resource: ResourceResponse, i18n: I18n): ResourceStatusPresentation {
  const online = nodeData(resource)?.status?.online
  const t = i18n.t.resources.node
  return {
    label: online === true ? t.online : online === false ? t.offline : i18n.t.common.unknown,
    tone: online === true ? 'success' : online === false ? 'danger' : 'neutral',
  }
}

function NodeOverview({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const t = i18n.t.resources.node
  const data = nodeData(resource)
  const spec = data?.spec
  const status = data?.status
  const state = nodeStatus(resource, i18n)
  return <div className="workspace-split detail-split">
    <WorkspaceSection title={t.properties}><PropertyGrid items={[
      { label: i18n.t.common.code, value: resource.code },
      { label: t.hostname, value: spec?.hostname ?? '—' },
      { label: t.operatingSystem, value: spec?.operatingSystem ?? '—' },
      { label: t.distribution, value: spec?.distribution ?? '—' },
      { label: t.kernel, value: spec?.kernelVersion ?? '—' },
      { label: t.architecture, value: spec?.architecture ?? '—' },
      { label: t.cpuModel, value: spec?.cpuModel ?? '—' },
      { label: t.cpuCores, value: spec?.cpuCores ?? '—' },
      { label: t.memory, value: formatMemoryMb(spec?.memoryMb ?? null, i18n) },
    ]} /></WorkspaceSection>
    <WorkspaceSection title={t.currentState}><PropertyGrid items={[
      { label: t.status, value: <StatusIndicator label={state.label} tone={state.tone} /> },
      { label: t.cpu, value: formatPercent(status?.cpuUsagePercent ?? null, i18n) },
      { label: t.memoryUsage, value: formatPercent(status?.memoryUsagePercent ?? null, i18n) },
      { label: t.uptime, value: i18n.format.duration(status?.uptimeSeconds ?? null) },
    ]} /></WorkspaceSection>
  </div>
}

function NodeMetricSummary({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const status = nodeData(resource)?.status
  return <div className="metric-strip">
    <div><span>{i18n.t.resources.node.cpu}</span><strong>{formatPercent(status?.cpuUsagePercent ?? null, i18n)}</strong></div>
    <div><span>{i18n.t.resources.node.memoryUsage}</span><strong>{formatPercent(status?.memoryUsagePercent ?? null, i18n)}</strong></div>
  </div>
}

export const nodePresentation: ResourcePresentation = {
  code: 'NODE',
  label: i18n => i18n.t.resources.types.NODE ?? 'NODE',
  Icon: Server,
  rowStatus: nodeStatus,
  headerStatus: nodeStatus,
  Overview: NodeOverview,
  MetricSummary: NodeMetricSummary,
}
