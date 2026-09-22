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
