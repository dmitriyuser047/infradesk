import { describe, expect, it } from 'vitest'

import { filterMetricSeries } from './metricSeries'
import { MetricCode, type MetricObservationResponse } from '../../types/metric'

describe('filterMetricSeries', () => {
  const observations: MetricObservationResponse[] = [
    { metricCode: MetricCode.cpuUsagePercent, value: 21.5, observedAt: '2026-09-22T10:00:00Z' },
    { metricCode: MetricCode.memoryUsagePercent, value: 55, observedAt: '2026-09-22T10:01:00Z' },
    { metricCode: MetricCode.cpuUsagePercent, value: 23, observedAt: '2026-09-22T10:02:00Z' },
    { metricCode: 'UNKNOWN', value: 99, observedAt: '2026-09-22T10:03:00Z' },
  ]

  it('filters CPU observations in chronological input order', () => {
    expect(filterMetricSeries(observations, MetricCode.cpuUsagePercent)).toEqual([
      { timestamp: '2026-09-22T10:00:00Z', value: 21.5 },
      { timestamp: '2026-09-22T10:02:00Z', value: 23 },
    ])
  })

  it('filters memory observations and excludes unknown metric codes', () => {
    expect(filterMetricSeries(observations, MetricCode.memoryUsagePercent)).toEqual([
      { timestamp: '2026-09-22T10:01:00Z', value: 55 },
    ])
  })

  it('does not mutate its input', () => {
    const before = structuredClone(observations)

    filterMetricSeries(observations, MetricCode.cpuUsagePercent)

    expect(observations).toEqual(before)
  })
})
