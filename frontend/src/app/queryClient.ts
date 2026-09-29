import { QueryClient } from '@tanstack/react-query'

import { ApiError } from '../api/httpClient'

/**
 * One retry for what may be transient — a network failure or a 5xx — and none for an answer the
 * server gave on purpose (4xx): repeating it only delays the error and keeps a skeleton on screen.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= 1) return false
  return !(error instanceof ApiError && error.status >= 400 && error.status < 500)
}

export function createAppQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
        refetchOnWindowFocus: false,
      },
    },
  })
}

export const queryClient = createAppQueryClient()
