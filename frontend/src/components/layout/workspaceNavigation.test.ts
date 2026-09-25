import { describe, expect, it } from 'vitest'
import { activeWorkspaceModule, contextSearch, workspaceModulePaths } from './workspaceNavigation'

describe('workspace module navigation', () => {
  it('keeps each existing route in its module', () => {
    expect(activeWorkspaceModule('/organizations/o')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/projects/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/environments/e/resources/r')).toBe('infrastructure')
    expect(activeWorkspaceModule('/organizations/o/incidents/i')).toBe('incidents')
    expect(activeWorkspaceModule('/organizations/o/connections/c/sync-sessions/s')).toBe('connections')
    expect(activeWorkspaceModule('/organizations/o/overview')).toBe('overview')
  })

  it('opens the overview of the selected organization', () => {
    expect(workspaceModulePaths(undefined, undefined).overview).toBeUndefined()
    expect(workspaceModulePaths('org', undefined).overview).toBe('/organizations/org/overview')
  })

  it('never fabricates an infrastructure path without environment context', () => {
    expect(workspaceModulePaths(undefined, undefined).infrastructure).toBeUndefined()
    expect(workspaceModulePaths('org', undefined).infrastructure).toBeUndefined()
    expect(workspaceModulePaths('org', 'env').infrastructure).toBe('/organizations/org/environments/env')
  })

  it('carries only workspace context across modules', () => {
    expect(contextSearch(new URLSearchParams('project=p&environment=e&status=OPEN'))).toBe('?project=p&environment=e')
  })
})
