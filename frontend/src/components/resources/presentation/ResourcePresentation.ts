import type { ComponentType } from 'react'
import type { LucideIcon } from 'lucide-react'

import type { I18n } from '../../../i18n'
import type { StatusTone } from '../../layout/WorkspacePrimitives'
import type { ResourceResponse } from '../../../types/resource'

export interface ResourceStatusPresentation {
  label: string
  tone: StatusTone
}

/**
 * Where a resource stands for the list filter: running, not running, or not known. This is a
 * frontend grouping of each type's own status (a node's online flag, a container's Docker state);
 * the backend and the domain have no such status and never receive it.
 */
export type ResourceCondition = 'running' | 'inactive' | 'unknown'

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
  /** The filter group of this resource's status; a type without one is always `unknown`. */
  condition?: (resource: ResourceResponse) => ResourceCondition
  /** Further identity the loaded resource already carries that a search should find, such as a hostname. */
  searchTerms?: (resource: ResourceResponse) => readonly string[]
  /** Status badge next to the title on the resource page, when this kind has one. */
  headerStatus?: (resource: ResourceResponse, i18n: I18n) => ResourceStatusPresentation
  /** Secondary identity, when a type has a more useful value than its internal code. */
  headerSubtitle?: (resource: ResourceResponse, i18n: I18n) => string | undefined
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
