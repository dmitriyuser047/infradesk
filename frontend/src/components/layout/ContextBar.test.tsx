import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const hooks = vi.hoisted(() => ({
  useMyOrganizations: vi.fn(),
  useProjects: vi.fn(),
  useEnvironmentContext: vi.fn(),
  useEnvironments: vi.fn(),
  useWorkspaceRouteContext: vi.fn(),
}))

vi.mock('../../api/auth', () => ({ useMyOrganizations: hooks.useMyOrganizations }))
vi.mock('../../api/navigation', () => ({
  useProjects: hooks.useProjects,
  useEnvironmentContext: hooks.useEnvironmentContext,
  useEnvironments: hooks.useEnvironments,
}))
vi.mock('./useWorkspaceRouteContext', () => ({
  useWorkspaceRouteContext: hooks.useWorkspaceRouteContext,
}))

import { ContextBar } from './ContextBar'

describe('ContextBar environment resolution', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    hooks.useMyOrganizations.mockReturnValue({
      data: [{ id: 'org', code: 'ORG', name: 'Organization', role: 'OWNER' }],
    })
    hooks.useProjects.mockReturnValue({
      data: Array.from({ length: 50 }, (_, index) => ({
        id: `project-${index}`, organizationId: 'org', code: `p${index}`,
        name: `Project ${index}`, description: null,
      })),
      isSuccess: true,
    })
    hooks.useEnvironmentContext.mockReturnValue({
      data: {
        project: { id: 'project-42', organizationId: 'org', code: 'p42', name: 'Project 42', description: null },
        environment: { id: 'environment', organizationId: 'org', projectId: 'project-42', code: 'prod', name: 'Prod', kind: 'PROD' },
      },
    })
    hooks.useEnvironments.mockReturnValue({ data: [], isSuccess: true })
    hooks.useWorkspaceRouteContext.mockReturnValue({ organizationId: 'org', environmentId: 'environment' })
  })

  it('uses one context lookup and one resolved-project environment lookup for 50 projects', () => {
    renderToStaticMarkup(<MemoryRouter><ContextBar /></MemoryRouter>)

    expect(hooks.useEnvironmentContext).toHaveBeenCalledTimes(1)
    expect(hooks.useEnvironmentContext).toHaveBeenCalledWith('org', 'environment', true)
    expect(hooks.useEnvironments).toHaveBeenCalledTimes(1)
    expect(hooks.useEnvironments).toHaveBeenCalledWith('org', 'project-42')
  })
})
