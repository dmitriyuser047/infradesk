import type { KnownMetricCode, MetricObservationResponse, MetricPoint } from '../../types/metric'

export function filterMetricSeries(
  observations: readonly MetricObservationResponse[],
  metricCode: KnownMetricCode,
): MetricPoint[] {
  return observations
    .filter((observation) => observation.metricCode === metricCode)
    .map((observation) => ({
      timestamp: observation.observedAt,
      value: observation.value,
    }))
}
