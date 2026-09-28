export interface ApiErrorResponse {
  code: string
  message: string
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly code: string,
    message: string,
    /** The whole error body, for the few errors that carry more than a code (validation findings). */
    public readonly body?: unknown,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export async function requestJson<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await request(url, init)
  return (await response.json()) as T
}

export async function requestVoid(url: string, init: RequestInit = {}): Promise<void> {
  await request(url, init)
}

async function request(url: string, init: RequestInit): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')

  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(url, {
    ...init,
    headers,
    credentials: 'same-origin',
  })

  if (!response.ok) {
    const error = await readApiError(response)
    if (error.code === 'UNAUTHENTICATED' && url !== '/api/v1/me') {
      window.dispatchEvent(new Event('infradesk:unauthenticated'))
    }
    throw new ApiError(response.status, error.code, error.message, error)
  }

  return response
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
