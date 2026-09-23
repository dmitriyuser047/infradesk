import { describe, expect, it } from 'vitest'
import { connectionScopeDisplayLabel, environmentName, projectName } from './connectionContextPresentation'

describe('connection context names', () => {
  const projects = [{ id: 'p', organizationId: 'org', code: 'app', name: 'App', description: null }]
  const environments = [{ id: 'e', organizationId: 'org', projectId: 'p', code: 'prod', name: 'Production', kind: 'PROD' }]

  it('resolves existing navigation data into human names', () => {
    expect(projectName('p', projects)).toBe('App')
    expect(environmentName('e', environments)).toBe('Production')
    expect(connectionScopeDisplayLabel({ type: 'ENVIRONMENT', projectId: 'p', environmentId: 'e' },
      projects, environments)).toBe('Environment · Production')
  })

  it('keeps a short identifier as the loading or missing fallback', () => {
    expect(projectName('12345678-0000-0000-0000-000000000001', undefined)).toBe('12345678…0001')
    expect(connectionScopeDisplayLabel({ type: 'ORGANIZATION' }, undefined, undefined)).toBe('Organization')
  })
})
