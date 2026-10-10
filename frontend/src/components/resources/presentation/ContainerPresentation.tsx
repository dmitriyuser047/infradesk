import { Box } from 'lucide-react'
import { Link, useLocation } from 'react-router-dom'

import { useCachedResource } from '../../../api/resources'

import { useI18n } from '../../../i18n'
import { PropertyGrid, StatusIndicator, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { TelemetrySummary } from '../../metrics/TelemetrySummary'
import { containerCondition, containerStatusPresentation } from './containerStatusPresentation'
import type { ResourcePresentation, ResourcePresentationProps } from './ResourcePresentation'
import type { ContainerResourceData, ResourceResponse } from '../../../types/resource'

/** Narrows the resource payload to a container; a payload that does not match shows as unknown. */
function containerData(resource: ResourceResponse): ContainerResourceData | null {
  return resource.data?.kind === 'CONTAINER' ? resource.data : null
}

/**
 * The container in one section: its Docker state, what it runs, where it runs and its code.
 * The server is linked through the parent the container already carries; its name is shown only
 * when the browser already holds it, so the page never loads a resource to name another.
 */
function ContainerOverview({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const t = i18n.t.resources.container
  const location = useLocation()
  const data = containerData(resource)
  const state = containerStatusPresentation(data?.status?.state, i18n)
  const server = useCachedResource(resource.organizationId, resource.environmentId, resource.parentResourceId)
  const serverPath = resource.parentResourceId === null ? null
    : `/organizations/${encodeURIComponent(resource.organizationId)}/environments/${encodeURIComponent(resource.environmentId)}` +
      `/resources/${encodeURIComponent(resource.parentResourceId)}${location.search}`
  return <div className="resource-overview">
    <WorkspaceSection title={containerPresentation.label(i18n)}><PropertyGrid items={[
      { label: t.state, value: <StatusIndicator label={state.label} tone={state.tone} /> },
      { label: t.image, value: data?.spec?.image ?? '—', technical: Boolean(data?.spec?.image) },
      ...(serverPath === null ? [] : [{ label: t.server, value: <Link className="property-link" to={serverPath}>
        {server?.name ?? t.openServer}</Link>, technical: server !== undefined }]),
      { label: i18n.t.common.code, value: resource.code, technical: true },
    ]} /><TelemetrySummary telemetry={data?.status?.telemetry} /></WorkspaceSection>
  </div>
}

export const containerPresentation: ResourcePresentation = {
  code: 'CONTAINER',
  label: i18n => i18n.t.resources.types.CONTAINER ?? 'CONTAINER',
  Icon: Box,
  rowStatus: (resource, i18n) => containerStatusPresentation(containerData(resource)?.status?.state, i18n),
  condition: resource => containerCondition(containerData(resource)?.status?.state),
  searchTerms: resource => { const image = containerData(resource)?.spec?.image; return image ? [image] : [] },
  headerStatus: (resource, i18n) => containerStatusPresentation(containerData(resource)?.status?.state, i18n),
  Overview: ContainerOverview,
}
