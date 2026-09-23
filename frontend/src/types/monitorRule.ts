import type { KnownMetricCode } from './metric'

export const MonitorOperator = {
  greaterThan: 'GREATER_THAN',
  greaterThanOrEqual: 'GREATER_THAN_OR_EQUAL',
  lessThan: 'LESS_THAN',
  lessThanOrEqual: 'LESS_THAN_OR_EQUAL',
} as const

export type MonitorOperatorCode = typeof MonitorOperator[keyof typeof MonitorOperator]

export const MonitorRuleStatus = {
  ok: 'OK',
  pending: 'PENDING',
  firing: 'FIRING',
  noData: 'NO_DATA',
} as const

export type KnownMonitorRuleStatus = typeof MonitorRuleStatus[keyof typeof MonitorRuleStatus]

export interface MonitorRuleResponse {
  id: string
  resourceId: string
  metricCode: string
  operator: string
  threshold: number
  forSeconds: number
  noDataSeconds: number
  enabled: boolean
  status: string | null
  createdAt: string
  updatedAt: string
}

export interface MonitorRuleRequest {
  metricCode: KnownMetricCode
  operator: MonitorOperatorCode
  threshold: number
  forSeconds: number
  noDataSeconds: number
  enabled: boolean
}
