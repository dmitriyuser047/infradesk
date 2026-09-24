export const ResourceOperationCode = {
  start: 'CONTAINER_START',
  stop: 'CONTAINER_STOP',
  restart: 'CONTAINER_RESTART',
} as const
export type ResourceOperationCode = typeof ResourceOperationCode[keyof typeof ResourceOperationCode]

export type OperationExecutionStatus = 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'UNKNOWN'

export interface AvailableResourceOperationsResponse {
  operations: ResourceOperationCode[]
  unavailableReason: string | null
}

export interface OperationExecutionResponse {
  id: string
  resourceId: string
  operationCode: ResourceOperationCode
  status: OperationExecutionStatus
  actorUserId: string
  startedAt: string
  finishedAt: string | null
  errorCode: string | null
  errorMessage: string | null
}
