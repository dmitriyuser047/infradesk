import type { StatusTone } from '../layout/WorkspacePrimitives'
import { MetricCode, type KnownMetricCode } from '../../types/metric'
import { MonitorOperator, MonitorRuleStatus, type MonitorOperatorCode } from '../../types/monitorRule'

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
    case MonitorOperator.greaterThanOrEqual:
      return 'Greater than or equal'
    case MonitorOperator.lessThan:
      return 'Less than'
    case MonitorOperator.lessThanOrEqual:
      return 'Less than or equal'
    default:
      return operator
  }
}

export function getOperatorSymbol(operator: string): string {
  switch (operator) {
    case MonitorOperator.greaterThan:
      return '>'
    case MonitorOperator.greaterThanOrEqual:
      return '>='
    case MonitorOperator.lessThan:
      return '<'
    case MonitorOperator.lessThanOrEqual:
      return '<='
    default:
      return operator
  }
}

/** A rule with no no-data timeout keeps evaluating whatever observation it last received. */
export function formatNoDataTimeout(seconds: number): string {
  return seconds === 0 ? 'off' : formatRuleDuration(seconds)
}

export function getMonitorRuleStatusPresentation(
  status: string | null,
  enabled: boolean,
): { label: string; tone: StatusTone } {
  if (!enabled) {
    return { label: 'Disabled', tone: 'neutral' }
  }
  switch (status) {
    case MonitorRuleStatus.ok:
      return { label: 'OK', tone: 'success' }
    case MonitorRuleStatus.pending:
      return { label: 'Pending', tone: 'warning' }
    case MonitorRuleStatus.firing:
      return { label: 'Firing', tone: 'danger' }
    case MonitorRuleStatus.noData:
      return { label: 'No data', tone: 'warning' }
    default:
      return { label: 'Enabled', tone: 'success' }
  }
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
  return [
    MonitorOperator.greaterThan,
    MonitorOperator.greaterThanOrEqual,
    MonitorOperator.lessThan,
    MonitorOperator.lessThanOrEqual,
  ]
}

export function isSupportedMetricCode(value: string): value is KnownMetricCode {
  return supportedMetricCodes().includes(value as KnownMetricCode)
}

export function isSupportedOperator(value: string): value is MonitorOperatorCode {
  return supportedOperators().includes(value as MonitorOperatorCode)
}
