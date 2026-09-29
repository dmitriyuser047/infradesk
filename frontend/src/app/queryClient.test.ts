import { describe, expect, it } from 'vitest'

import { ApiError } from '../api/httpClient'
import { shouldRetryQuery } from './queryClient'

describe('query retry policy', () => {
  it('retries a network failure or a server error once', () => {
    expect(shouldRetryQuery(0, new TypeError('Failed to fetch'))).toBe(true)
    expect(shouldRetryQuery(0, new ApiError(502, 'HTTP_ERROR', 'Bad gateway'))).toBe(true)
    expect(shouldRetryQuery(1, new ApiError(503, 'NOT_READY', 'x'))).toBe(false)
  })

  it('never retries an answer the server gave on purpose', () => {
    for (const status of [400, 401, 403, 404, 409, 429]) {
      expect(shouldRetryQuery(0, new ApiError(status, 'CODE', 'x'))).toBe(false)
    }
  })
})
