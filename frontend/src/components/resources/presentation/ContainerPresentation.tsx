import { Box } from 'lucide-react'

import { useI18n } from '../../../i18n'
import { PropertyGrid, StatusIndicator, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { containerStatusPresentation } from './containerStatusPresentation'
import type { ResourcePresentation, ResourcePresentationProps } from './ResourcePresentation'
import type { ContainerResourceData, ResourceResponse } from '../../../types/resource'

/** Narrows the resource payload to a container; a payload that does not match shows as unknown. */
function containerData(resource: ResourceResponse): ContainerResourceData | null {
  return resource.data?.kind === 'CONTAINER' ? resource.data : null
}

function ContainerOverview({ resource }: ResourcePresentationProps) {
  const i18n = useI18n()
  const t = i18n.t.resources.container
  const data = containerData(resource)
  const state = containerStatusPresentation(data?.status?.state, i18n)
  return <div className="workspace-split detail-split">
    <WorkspaceSection title={t.properties}><PropertyGrid items={[
      { label: i18n.t.common.code, value: resource.code },
      { label: i18n.t.common.type, value: containerPresentation.label(i18n) },
      { label: t.image, value: data?.spec?.image ?? '—' },
    ]} /></WorkspaceSection>
    <WorkspaceSection title={t.currentState}><PropertyGrid items={[
      { label: t.state, value: data?.status?.state ? <StatusIndicator label={state.label} tone={state.tone} /> : '—' },
    ]} /></WorkspaceSection>
  </div>
}

export const containerPresentation: ResourcePresentation = {
  code: 'CONTAINER',
  label: i18n => i18n.t.resources.types.CONTAINER ?? 'CONTAINER',
  Icon: Box,
  rowStatus: (resource, i18n) => containerStatusPresentation(containerData(resource)?.status?.state, i18n),
  headerStatus: (resource, i18n) => containerStatusPresentation(containerData(resource)?.status?.state, i18n),
  Overview: ContainerOverview,
}
