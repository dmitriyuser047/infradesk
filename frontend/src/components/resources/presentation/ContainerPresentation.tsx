import { Box } from 'lucide-react'

import { PropertyGrid, WorkspaceSection } from '../../layout/WorkspacePrimitives'
import { containerStatusPresentation } from './containerStatusPresentation'
import type { ResourcePresentation, ResourcePresentationProps } from './ResourcePresentation'
import type { ContainerResourceData, ResourceResponse } from '../../../types/resource'

/** Narrows the resource payload to a container; a payload that does not match shows as unknown. */
function containerData(resource: ResourceResponse): ContainerResourceData | null {
  return resource.data?.kind === 'CONTAINER' ? resource.data : null
}

function ContainerOverview({ resource }: ResourcePresentationProps) {
  const data = containerData(resource)
  return <div className="workspace-split detail-split">
    <WorkspaceSection title="Properties"><PropertyGrid items={[
      { label: 'Code', value: resource.code }, { label: 'Type', value: resource.resourceTypeCode },
      { label: 'Image', value: data?.spec?.image ?? '—' },
    ]} /></WorkspaceSection>
    <WorkspaceSection title="Current state"><PropertyGrid items={[
      { label: 'State', value: data?.status?.state ?? '—' },
    ]} /></WorkspaceSection>
  </div>
}

export const containerPresentation: ResourcePresentation = {
  code: 'CONTAINER',
  label: 'Container',
  Icon: Box,
  rowStatus: resource => containerStatusPresentation(containerData(resource)?.status?.state),
  Overview: ContainerOverview,
}
