import type { ResourceTelemetry } from './metric'

export interface NodeSpecResponse {
  hostname: string
  operatingSystem: string | null
  distribution: string | null
  kernelVersion: string | null
  architecture: string | null
  cpuModel: string | null
  cpuCores: number | null
  memoryMb: number | null
}

export interface NodeStatusResponse {
  online: boolean
  cpuUsagePercent: number | null
  memoryUsagePercent: number | null
  uptimeSeconds: number | null
  telemetry?: ResourceTelemetry
}

export interface ContainerSpecResponse {
  image: string | null
}

export interface ContainerStatusResponse {
  state: string | null
  telemetry?: ResourceTelemetry
}

export interface NodeResourceData {
  kind: 'NODE'
  spec: NodeSpecResponse | null
  status: NodeStatusResponse | null
}

export interface ContainerResourceData {
  kind: 'CONTAINER'
  spec: ContainerSpecResponse | null
  status: ContainerStatusResponse | null
}

export type ResourceData = NodeResourceData | ContainerResourceData

export interface ResourceResponse {
  id: string
  organizationId: string
  environmentId: string
  resourceTypeId: string
  parentResourceId: string | null
  code: string
  name: string
  resourceTypeCode: string
  active: boolean
  data: ResourceData
  createdAt: string
  updatedAt: string
}
