import type { I18n } from '../../i18n'
import type { StatusTone } from '../layout/WorkspacePrimitives'
import { MetricCode, metricUnit, type KnownMetricCode } from '../../types/metric'
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

export function getMetricLabel(metricCode: string, i18n: I18n): string {
  return i18n.t.monitoring.metrics[metricCode] ?? metricCode
}

export function getOperatorLabel(operator: string, i18n: I18n): string {
  return i18n.t.monitoring.operators[operator] ?? operator
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
export function formatNoDataTimeout(seconds: number, i18n: I18n): string {
  return seconds === 0 ? i18n.t.monitoring.off : formatRuleDuration(seconds, i18n)
}

export function getMonitorRuleStatusPresentation(
  status: string | null,
  enabled: boolean,
  i18n: I18n,
): { label: string; tone: StatusTone } {
  const t = i18n.t.monitoring
  if (!enabled) {
    return { label: t.disabled, tone: 'neutral' }
  }
  switch (status) {
    case MonitorRuleStatus.ok:
      return { label: t.statuses.OK, tone: 'success' }
    case MonitorRuleStatus.pending:
      return { label: t.statuses.PENDING, tone: 'warning' }
    case MonitorRuleStatus.firing:
      return { label: t.statuses.FIRING, tone: 'danger' }
    case MonitorRuleStatus.noData:
      return { label: t.statuses.NO_DATA, tone: 'warning' }
    default:
      return { label: t.enabled, tone: 'success' }
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

export function formatRuleDuration(seconds: number, i18n: I18n): string {
  if (seconds === 0) {
    return i18n.t.monitoring.immediately
  }

  const duration = secondsToDurationInput(seconds)
  const units = i18n.t.units
  const unit = duration.unit === 'seconds' ? units.second : duration.unit === 'minutes' ? units.minute : units.hour
  return `${duration.value}${unit}`
}

export function supportedMetricCodes() {
  return Object.values(MetricCode)
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

/**
 * A rule's condition in one line — "CPU usage > 85% for 5m" — built from its typed fields, never
 * from a stored string. Units are defined by the metric catalog.
 */
export function formatMonitorCondition(
  rule: { metricCode: string; operator: string; threshold: number; forSeconds: number },
  i18n: I18n,
): string {
  const threshold = `${i18n.format.number(rule.threshold)}${metricUnit(rule.metricCode)}`
  const condition = `${getMetricLabel(rule.metricCode, i18n)} ${getOperatorSymbol(rule.operator)} ${threshold}`
  return rule.forSeconds === 0 ? condition : i18n.t.monitoring.conditionFor(condition, formatRuleDuration(rule.forSeconds, i18n))
}
