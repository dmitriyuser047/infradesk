import { afterEach, describe, expect, it, vi } from 'vitest'

import { getOperationsOverview, overviewQueryKey, overviewSearch } from './overview'

describe('operations overview api', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('sends the scope as query parameters, and an environment only with its project', () => {
    expect(overviewSearch({})).toBe('')
    expect(overviewSearch({ projectId: 'p' })).toBe('?projectId=p')
    expect(overviewSearch({ projectId: 'p', environmentId: 'e' })).toBe('?projectId=p&environmentId=e')
    expect(overviewSearch({ environmentId: 'e' })).toBe('')
  })

  it('reads the whole overview in one request', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }))
    vi.stubGlobal('fetch', fetchMock)

    await getOperationsOverview('org', { projectId: 'p', environmentId: 'e' })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/organizations/org/overview?projectId=p&environmentId=e')
  })

  it('shares one key prefix across scopes, so a mutation refreshes every scope at once', () => {
    expect(overviewQueryKey('org')).toEqual(['overview', 'org'])
  })
})
