import type { KnownMetricCode } from './metric'

export const MonitorOperator = {
  greaterThan: 'GREATER_THAN',
} as const

export type MonitorOperatorCode = typeof MonitorOperator[keyof typeof MonitorOperator]

export interface MonitorRuleResponse {
  id: string
  resourceId: string
  metricCode: string
  operator: string
  threshold: number
  forSeconds: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface MonitorRuleRequest {
  metricCode: KnownMetricCode
  operator: MonitorOperatorCode
  threshold: number
  forSeconds: number
  enabled: boolean
}
