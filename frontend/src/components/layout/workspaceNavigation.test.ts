import { describe, expect, it } from 'vitest'

import { activeWorkspaceModule, contextQuery, contextSearch, modulePath } from './workspaceNavigation'

describe('workspace navigation', () => {
  it('keeps every existing route in its section', () => {
    expect(activeWorkspaceModule('/organizations')).toBeNull()
    expect(activeWorkspaceModule('/organizations/o')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/projects/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/projects/p/environments/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/overview')).toBe('overview')
    expect(activeWorkspaceModule('/organizations/o/resources')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/environments/e')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/environments/e/resources/r')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/incidents/i')).toBe('incidents')
    expect(activeWorkspaceModule('/organizations/o/connections/c/sync-sessions/s')).toBe('connections')
  })

  it('builds each section path inside the current context', () => {
    const scope = { organizationId: 'org', projectId: 'p', environmentId: 'e' }

    expect(modulePath('overview', scope)).toBe('/organizations/org/overview?project=p&environment=e')
    expect(modulePath('workspace', scope)).toBe('/organizations/org?project=p&environment=e')
    expect(modulePath('incidents', scope)).toBe('/organizations/org/incidents?project=p&environment=e')
    expect(modulePath('connections', scope)).toBe('/organizations/org/connections?project=p&environment=e')
    expect(modulePath('resources', scope)).toBe('/organizations/org/environments/e?project=p')
  })

  it('asks for an environment before listing resources, and has no paths without an organization', () => {
    expect(modulePath('resources', { organizationId: 'org' })).toBe('/organizations/org/resources')
    expect(modulePath('resources', { organizationId: 'org', projectId: 'p' })).toBe('/organizations/org/resources?project=p')
    expect(modulePath('overview', {})).toBeUndefined()
  })

  it('never carries an environment without its project', () => {
    expect(contextQuery({ projectId: null, environmentId: 'e' })).toBe('')
    expect(contextSearch(new URLSearchParams('project=p&environment=e&status=OPEN'))).toBe('?project=p&environment=e')
  })
})
