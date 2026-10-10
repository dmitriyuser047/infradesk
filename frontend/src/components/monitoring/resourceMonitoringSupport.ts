/**
 * Which resource types monitoring applies to today: metrics are collected and monitor rules are
 * evaluated for nodes and containers. This is a feature rule, not a display rule, so it lives with the
 * monitoring feature instead of in the resource presentation contract.
 */
import { MetricCode, type KnownMetricCode } from '../../types/metric'

export function supportsResourceMonitoring(resourceTypeCode: string | undefined): boolean {
  return resourceTypeCode === 'NODE' || resourceTypeCode === 'CONTAINER'
}

export function initialExtraMetric(resourceTypeCode: string): KnownMetricCode {
  return resourceTypeCode === 'CONTAINER' ? MetricCode.containerRestartCount : MetricCode.diskUsagePercent
}
