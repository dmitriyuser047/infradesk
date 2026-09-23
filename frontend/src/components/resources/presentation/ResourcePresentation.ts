import type { ComponentType } from 'react'
import type { LucideIcon } from 'lucide-react'

import type { StatusTone } from '../../layout/WorkspacePrimitives'
import type { ResourceResponse } from '../../../types/resource'

export interface ResourceStatusPresentation {
  label: string
  tone: StatusTone
}

export interface ResourcePresentationProps {
  resource: ResourceResponse
}

/**
 * How one kind of resource is displayed. Read-only presentation: no operations, no fetching,
 * no permissions. Everything it needs arrives through props.
 */
export interface ResourceRendering {
  /** Icon in the infrastructure tree. */
  Icon: LucideIcon
  /** Status badge in the infrastructure tree row. */
  rowStatus: (resource: ResourceResponse) => ResourceStatusPresentation
  /** Status badge next to the title on the resource page, when this kind has one. */
  headerStatus?: (resource: ResourceResponse) => ResourceStatusPresentation
  /** Type-specific content of the Overview tab. */
  Overview: ComponentType<ResourcePresentationProps>
  /**
   * Present when this kind of resource shows the metric and monitor rule tabs; the component is
   * the type-specific metric summary above the charts. Monitoring itself is unchanged: only the
   * kinds that showed those tabs before declare this.
   */
  monitoring?: {
    MetricSummary: ComponentType<ResourcePresentationProps>
  }
}

export interface ResourcePresentation extends ResourceRendering {
  /** Resource type code as the backend reports it, for example "NODE". */
  code: string
  /** Human label for this type, used by the infrastructure type filter. */
  label: string
}
