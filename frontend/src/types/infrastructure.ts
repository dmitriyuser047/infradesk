import type { IncidentListItemResponse } from './incident'
import type { ResourceResponse } from './resource'

/** Where something lives in the workspace, by name. */
export interface ProjectReference {
  id: string
  name: string
}

export interface EnvironmentReference {
  id: string
  name: string
  kind: string
}

export interface ResourceReference {
  id: string
  name: string
  resourceTypeCode: string
}

/**
 * A connection through which a resource was discovered. A resource may have several; none of them
 * is the primary one, so every reader shows them all.
 */
export interface SourceConnection {
  id: string
  name: string
  connectorType: string
  active: boolean
}

/** A resource together with the environment it belongs to. */
export interface LocatedResourceResponse extends ResourceResponse {
  environment: EnvironmentReference
}

export interface ConnectionInfrastructureSummary {
  activeResourceCount: number
  inactiveResourceCount: number
  openIncidentCount: number
  resourceTypeCounts: { resourceTypeCode: string; count: number }[]
  /** The newest open incidents, bounded. */
  openIncidents: IncidentListItemResponse[]
  /** The first resources of the hierarchy, roots first, bounded. */
  resources: LocatedResourceResponse[]
}

export interface ResourceContextResponse {
  project: ProjectReference
  environment: EnvironmentReference
  parentResource: ResourceReference | null
  sourceConnections: SourceConnection[]
  /** A bounded preview of the active children. */
  children: ResourceResponse[]
  activeChildCount: number
  openIncidentCount: number
}

export interface ConnectionInfrastructureCounts {
  connectionId: string
  activeResourceCount: number
  openIncidentCount: number
}

export interface ResourceSourcesResponse {
  resourceId: string
  sourceConnections: SourceConnection[]
}
