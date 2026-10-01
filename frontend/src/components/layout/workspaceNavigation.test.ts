import { describe, expect, it } from 'vitest'

import { activeWorkspaceModule, contextQuery, contextSearch, modulePath, withWorkspaceContext } from './workspaceNavigation'

describe('workspace navigation', () => {
  it('ignores queries and matches module path boundaries rather than object names', () => {
    expect(activeWorkspaceModule('/organizations/o/integrations/i?tab=nodes&project=p')).toBe('integrations')
    expect(activeWorkspaceModule('/organizations/o/projects/connections/environments/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/connections-old')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/resources/r/history?tab=incidents')).toBe('resources')
  })

  it('carries only scope into a detail, leaving its own tab intact', () => {
    expect(withWorkspaceContext('/configurations/profile?tab=targets', new URLSearchParams('project=p&environment=e&tab=nodes&state=archived')))
      .toBe('/configurations/profile?tab=targets&project=p&environment=e')
    expect(withWorkspaceContext('/configurations', new URLSearchParams('environment=e'))).toBe('/configurations')
  })
  it('keeps every existing route in its section', () => {
    expect(activeWorkspaceModule('/organizations')).toBeNull()
    expect(activeWorkspaceModule('/organizations/o')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/projects/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/projects/p/environments/new')).toBe('workspace')
    expect(activeWorkspaceModule('/organizations/o/overview')).toBe('overview')
    expect(activeWorkspaceModule('/organizations/o/resources')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/resources/r')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/environments/e')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/environments/e/resources/r')).toBe('resources')
    expect(activeWorkspaceModule('/organizations/o/incidents/i')).toBe('incidents')
    expect(activeWorkspaceModule('/organizations/o/connections/c/sync-sessions/s')).toBe('connections')
    expect(activeWorkspaceModule('/organizations/o/notifications/c/edit')).toBe('notifications')
    expect(activeWorkspaceModule('/organizations/o/configurations/p/revisions/1')).toBe('configurations')
    expect(activeWorkspaceModule('/organizations/o/configuration-rules/r')).toBe('configurations')
    expect(activeWorkspaceModule('/organizations/o/configuration-assignments/a')).toBe('configurations')
    expect(activeWorkspaceModule('/organizations/o/integrations/i/config-profiles/p')).toBe('integrations')
  })

  it('builds each section path inside the current context', () => {
    const scope = { organizationId: 'org', projectId: 'p', environmentId: 'e' }

    expect(modulePath('overview', scope)).toBe('/organizations/org/overview?project=p&environment=e')
    expect(modulePath('workspace', scope)).toBe('/organizations/org?project=p&environment=e')
    expect(modulePath('incidents', scope)).toBe('/organizations/org/incidents?project=p&environment=e')
    expect(modulePath('connections', scope)).toBe('/organizations/org/connections?project=p&environment=e')
    expect(modulePath('notifications', scope)).toBe('/organizations/org/notifications?project=p&environment=e')
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
