import { MetricCode, type KnownMetricCode } from '../../types/metric'
import { MonitorOperator, type MonitorOperatorCode } from '../../types/monitorRule'

export type DurationUnit = 'seconds' | 'minutes' | 'hours'

export interface DurationInput {
  value: number
  unit: DurationUnit
}

const secondsPerUnit: Record<DurationUnit, number> = {
  seconds: 1,
  minutes: 60,
  hours: 3_600,
}

export function getMetricLabel(metricCode: string): string {
  switch (metricCode) {
    case MetricCode.cpuUsagePercent:
      return 'CPU usage'
    case MetricCode.memoryUsagePercent:
      return 'Memory usage'
    default:
      return metricCode
  }
}

export function getOperatorLabel(operator: string): string {
  switch (operator) {
    case MonitorOperator.greaterThan:
      return 'Greater than'
    default:
      return operator
  }
}

export function getOperatorSymbol(operator: string): string {
  return operator === MonitorOperator.greaterThan ? '>' : operator
}

export function durationToSeconds(value: number, unit: DurationUnit): number {
  return value * secondsPerUnit[unit]
}

export function secondsToDurationInput(seconds: number): DurationInput {
  if (seconds > 0 && seconds % secondsPerUnit.hours === 0) {
    return { value: seconds / secondsPerUnit.hours, unit: 'hours' }
  }
  if (seconds > 0 && seconds % secondsPerUnit.minutes === 0) {
    return { value: seconds / secondsPerUnit.minutes, unit: 'minutes' }
  }
  return { value: seconds, unit: 'seconds' }
}

export function formatRuleDuration(seconds: number): string {
  if (seconds === 0) {
    return 'Immediately'
  }

  const duration = secondsToDurationInput(seconds)
  const unit = duration.unit === 'seconds' ? 's' : duration.unit === 'minutes' ? 'm' : 'h'
  return `${duration.value}${unit}`
}

export function supportedMetricCodes() {
  return [MetricCode.cpuUsagePercent, MetricCode.memoryUsagePercent] as const
}

export function supportedOperators(): readonly MonitorOperatorCode[] {
  return [MonitorOperator.greaterThan]
}

export function isSupportedMetricCode(value: string): value is KnownMetricCode {
  return supportedMetricCodes().includes(value as KnownMetricCode)
}

export function isSupportedOperator(value: string): value is MonitorOperatorCode {
  return supportedOperators().includes(value as MonitorOperatorCode)
}
