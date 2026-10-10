import { useQuery } from '@tanstack/react-query'

import { requestJson } from './httpClient'
import type { MetricObservationResponse } from '../types/metric'

export interface MetricWindow {
  from: Date
  to: Date
}

export function createLastHourWindow(now = new Date()): MetricWindow {
  return {
    from: new Date(now.getTime() - 60 * 60 * 1000),
    to: now,
  }
}

export function useResourceMetrics(
  organizationId: string | undefined,
  resourceId: string | undefined,
  window: MetricWindow,
  enabled: boolean,
) {
  return useQuery({
    queryKey: [
      'resource-metrics',
      organizationId,
      resourceId,
      window.from.toISOString(),
      window.to.toISOString(),
    ],
    queryFn: () => getResourceMetrics(
      requireId(organizationId),
      requireId(resourceId),
      window.from,
      window.to,
    ),
    enabled: Boolean(organizationId && resourceId) && enabled,
  })
}

export function getResourceMetrics(
  organizationId: string,
  resourceId: string,
  from: Date,
  to: Date,
): Promise<MetricObservationResponse[]> {
  const query = new URLSearchParams({
    from: from.toISOString(),
    to: to.toISOString(),
  })

  return requestJson<MetricObservationResponse[]>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}/metrics?${query.toString()}`,
  )
}

function requireId(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    throw new Error('Missing route identifier')
  }

  return value
}

/** One point of a metric series: an observation, or the summary of one bucket of them. */
export interface MetricSeriesPointResponse {
  metricCode: string
  bucketStart: string
  average: number
  minimum: number
  maximum: number
  samples: number
}

/** `RAW` up to six hours, then `FIVE_MINUTES`, `HOUR` and `DAY` as the period grows (400 days at most). */
export interface MetricSeriesResponse {
  resolution: 'RAW' | 'FIVE_MINUTES' | 'HOUR' | 'DAY'
  from: string
  to: string
  points: MetricSeriesPointResponse[]
}

/**
 * Metric history at the resolution the period calls for. Long periods are read from the rollups
 * that outlive raw observations, so a chart of the last month stays a few hundred points.
 */
export function getResourceMetricSeries(organizationId: string, resourceId: string, from: Date, to: Date): Promise<MetricSeriesResponse> {
  const query = new URLSearchParams({ from: from.toISOString(), to: to.toISOString() })
  return requestJson<MetricSeriesResponse>(
    `/api/v1/organizations/${encodeURIComponent(organizationId)}/resources/${encodeURIComponent(resourceId)}/metric-series?${query.toString()}`,
  )
}

export function useResourceMetricSeries(organizationId: string | undefined, resourceId: string | undefined,
  window: MetricWindow, enabled: boolean) {
  return useQuery({
    queryKey: ['resource-metric-series', organizationId, resourceId, window.from.toISOString(), window.to.toISOString()],
    queryFn: () => getResourceMetricSeries(requireId(organizationId), requireId(resourceId), window.from, window.to),
    enabled: Boolean(organizationId && resourceId) && enabled,
  })
}
