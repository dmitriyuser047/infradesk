import type { ComponentType } from 'react'
import type { LucideIcon } from 'lucide-react'

import type { I18n } from '../../../i18n'
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
  /** Status badge in the infrastructure tree row, in the active language. */
  rowStatus: (resource: ResourceResponse, i18n: I18n) => ResourceStatusPresentation
  /** Status badge next to the title on the resource page, when this kind has one. */
  headerStatus?: (resource: ResourceResponse, i18n: I18n) => ResourceStatusPresentation
  /** Type-specific content of the Overview tab. */
  Overview: ComponentType<ResourcePresentationProps>
  /**
   * Type-specific summary rendered above the metric charts. Purely how this kind of resource is
   * displayed; whether monitoring applies to it at all is a monitoring feature rule, not a
   * presentation one.
   */
  MetricSummary?: ComponentType<ResourcePresentationProps>
}

export interface ResourcePresentation extends ResourceRendering {
  /** Resource type code as the backend reports it, for example "NODE". */
  code: string
  /** Human label for this type in the active language, used by the type filter and headers. */
  label: (i18n: I18n) => string
}
