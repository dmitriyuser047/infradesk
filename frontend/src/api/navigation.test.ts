import { afterEach, describe, expect, it, vi } from 'vitest'

import { getEnvironmentContext } from './navigation'

describe('environment context navigation', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('resolves a deep environment route with exactly one context request regardless of project count', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      project: { id: 'project-42', organizationId: 'org', code: 'p42', name: 'Project 42', description: null },
      environment: { id: 'environment', organizationId: 'org', projectId: 'project-42', code: 'prod', name: 'Prod', kind: 'PROD' },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    const context = await getEnvironmentContext('org', 'environment')

    expect(context.project.id).toBe('project-42')
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/organizations/org/environments/environment/context')
  })
})
