export interface ApiErrorResponse {
  code: string
  message: string
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')

  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(url, {
    ...init,
    headers,
  })

  if (!response.ok) {
    const error = await readApiError(response)
    throw new ApiError(response.status, error.code, error.message)
  }

  return (await response.json()) as T
}

async function readApiError(response: Response): Promise<ApiErrorResponse> {
  const fallback: ApiErrorResponse = {
    code: 'HTTP_ERROR',
    message: `Request failed with status ${response.status}`,
  }

  try {
    const payload: unknown = await response.json()
    return isApiErrorResponse(payload) ? payload : fallback
  } catch {
    return fallback
  }
}

function isApiErrorResponse(value: unknown): value is ApiErrorResponse {
  if (typeof value !== 'object' || value === null) {
    return false
  }

  const candidate = value as Record<string, unknown>
  return typeof candidate.code === 'string' && typeof candidate.message === 'string'
}
