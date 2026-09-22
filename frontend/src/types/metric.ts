export const MetricCode = {
  cpuUsagePercent: 'CPU_USAGE_PERCENT',
  memoryUsagePercent: 'MEMORY_USAGE_PERCENT',
} as const

export type KnownMetricCode = typeof MetricCode[keyof typeof MetricCode]

export interface MetricObservationResponse {
  metricCode: string
  value: number
  observedAt: string
}

export interface MetricPoint {
  timestamp: string
  value: number
}
