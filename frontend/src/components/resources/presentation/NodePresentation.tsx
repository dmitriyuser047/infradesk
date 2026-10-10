import { Server } from 'lucide-react'

import { useI18n, type I18n } from '../../../i18n'
import { PropertyGrid, StatusIndicator, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { TelemetrySummary } from '../../metrics/TelemetrySummary'
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
    label: online === true ? t.online : online === false ? t.offline : i18n.t.workScreens.unknownState,
    tone: online === true ? 'success' : online === false ? 'danger' : 'neutral',
  }
}

/** The node as inventory last described it: its current state first, then what it is. */
function NodeOverview({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const t = i18n.t.resources.node
  const spec = nodeData(resource)?.spec
  return <WorkspaceSection title={t.currentState}><NodeMetricSummary resource={resource} />
    <TelemetrySummary telemetry={nodeData(resource)?.status?.telemetry} />
    <details className="operation-disclosure"><summary>{t.properties}</summary><PropertyGrid columns={2} items={[
      { label: t.hostname, value: spec?.hostname ?? '—', technical: spec?.hostname != null },
      { label: t.operatingSystem, value: spec?.operatingSystem ?? '—' },
      { label: t.distribution, value: spec?.distribution ?? '—' },
      { label: t.kernel, value: spec?.kernelVersion ?? '—', technical: spec?.kernelVersion != null },
      { label: t.architecture, value: spec?.architecture ?? '—', technical: spec?.architecture != null },
      { label: t.cpuModel, value: spec?.cpuModel ?? '—' },
      { label: t.cpuCores, value: spec?.cpuCores ?? '—' },
      { label: t.memory, value: formatMemoryMb(spec?.memoryMb ?? null, i18n) },
      { label: i18n.t.common.code, value: resource.code, technical: true },
    ]} /></details>
  </WorkspaceSection>
}

/**
 * The node's current state in one compact line: the status inventory stored and the latest
 * reported usage. Shown on the overview and above the charts; a missing value reads "—".
 */
function NodeMetricSummary({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const t = i18n.t.resources.node
  const status = nodeData(resource)?.status
  const state = nodeStatus(resource, i18n)
  return <dl className="metric-strip">
    <div><dt>{t.status}</dt><dd><StatusIndicator label={state.label} tone={state.tone} /></dd></div>
    <div><dt>{t.cpu}</dt><dd>{formatPercent(status?.cpuUsagePercent ?? null, i18n)}</dd></div>
    <div><dt>{t.memoryUsage}</dt><dd>{formatPercent(status?.memoryUsagePercent ?? null, i18n)}</dd></div>
    <div><dt>{t.uptime}</dt><dd>{i18n.format.duration(status?.uptimeSeconds ?? null)}</dd></div>
  </dl>
}

export const nodePresentation: ResourcePresentation = {
  code: 'NODE',
  label: i18n => i18n.t.resources.types.NODE ?? 'NODE',
  Icon: Server,
  rowStatus: nodeStatus,
  // The same flag the badge shows: online runs, offline does not, no report is unknown.
  condition: resource => { const online = nodeData(resource)?.status?.online; return online === true ? 'running' : online === false ? 'inactive' : 'unknown' },
  searchTerms: resource => { const hostname = nodeData(resource)?.spec?.hostname; return hostname ? [hostname] : [] },
  headerStatus: nodeStatus,
  headerSubtitle: resource => {
    const spec = nodeData(resource)?.spec
    return spec ? [spec.hostname !== resource.name ? spec.hostname : null,
      spec.distribution ?? spec.operatingSystem].filter(Boolean).join(' · ') || undefined : undefined
  },
  Overview: NodeOverview,
  MetricSummary: NodeMetricSummary,
}
