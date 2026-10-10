export const MetricCode = {
  cpuUsagePercent: 'CPU_USAGE_PERCENT',
  memoryUsagePercent: 'MEMORY_USAGE_PERCENT',
  diskUsagePercent: 'DISK_USAGE_PERCENT',
  diskFreeBytes: 'DISK_FREE_BYTES',
  inodeUsagePercent: 'INODE_USAGE_PERCENT',
  swapUsagePercent: 'SWAP_USAGE_PERCENT',
  swapUsedBytes: 'SWAP_USED_BYTES',
  loadAverage1: 'LOAD_AVERAGE_1',
  loadAverage5: 'LOAD_AVERAGE_5',
  loadAverage15: 'LOAD_AVERAGE_15',
  loadPerCore: 'LOAD_PER_CORE',
  cpuIowaitPercent: 'CPU_IOWAIT_PERCENT',
  diskReadBytesPerSecond: 'DISK_READ_BYTES_PER_SECOND',
  diskWriteBytesPerSecond: 'DISK_WRITE_BYTES_PER_SECOND',
  diskLatencyMilliseconds: 'DISK_LATENCY_MILLISECONDS',
  diskBusyPercent: 'DISK_BUSY_PERCENT',
  networkReceiveBytesPerSecond: 'NETWORK_RECEIVE_BYTES_PER_SECOND',
  networkTransmitBytesPerSecond: 'NETWORK_TRANSMIT_BYTES_PER_SECOND',
  networkErrorsPerSecond: 'NETWORK_ERRORS_PER_SECOND',
  networkDropsPerSecond: 'NETWORK_DROPS_PER_SECOND',
  containerRestartCount: 'CONTAINER_RESTART_COUNT',
  containerHealthy: 'CONTAINER_HEALTHY',
  tlsDaysRemaining: 'TLS_DAYS_REMAINING',
  serviceAvailable: 'SERVICE_AVAILABLE',
  serviceResponseMilliseconds: 'SERVICE_RESPONSE_MILLISECONDS',
  diskTemperatureCelsius: 'DISK_TEMPERATURE_CELSIUS',
  diskWearPercent: 'DISK_WEAR_PERCENT',
  diskMediaErrors: 'DISK_MEDIA_ERRORS',
  diskHealthy: 'DISK_HEALTHY',
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

export interface ResourceTelemetry {
  metrics: Record<string, number>
  devices: { kind: string; name: string; metrics: Record<string, number> }[]
}

export function metricUnit(code: string): string {
  if (code.endsWith('_PERCENT')) return '%'
  if (code.endsWith('_BYTES_PER_SECOND')) return 'B/s'
  if (code.endsWith('_BYTES')) return 'B'
  if (code.endsWith('_MILLISECONDS')) return 'ms'
  if (code.endsWith('_PER_SECOND')) return '/s'
  if (code === 'TLS_DAYS_REMAINING') return 'd'
  if (code === 'DISK_TEMPERATURE_CELSIUS') return '\u00b0C'
  return ''
}
