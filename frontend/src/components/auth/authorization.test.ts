import { describe, expect, it } from 'vitest'

import { canOrganization, type OrganizationPermission } from './authorization'

const permissions: readonly OrganizationPermission[] = [
  'manageMembers',
  'manageIntegrations',
  'readOrganization',
  'manageWorkspace',
  'manageConnections',
  'runConnectionSync',
  'manageMonitoring',
  'manageNotifications',
  'viewAudit',
  'executeOperations',
  'openTerminal',
  'manageConfigurations',
  'deployConfigurations',
]

describe('organization authorization policy', () => {
  it('limits an operator to operations, synchronization and monitoring', () => {
    expect(permissions.filter(permission => canOrganization('OPERATOR', permission))).toEqual([
      'readOrganization', 'runConnectionSync', 'manageMonitoring', 'executeOperations',
    ])
    expect(permissions.every(permission => canOrganization('ADMINISTRATOR', permission))).toBe(true)
  })
  it('allows an owner every organization capability', () => {
    expect(permissions.every(permission => canOrganization('OWNER', permission))).toBe(true)
  })

  it('keeps a member read-only', () => {
    expect(canOrganization('MEMBER', 'readOrganization')).toBe(true)
    expect(permissions.filter(permission => permission !== 'readOrganization')
      .every(permission => !canOrganization('MEMBER', permission))).toBe(true)
  })

  it('grants nothing before a known membership is loaded', () => {
    expect(permissions.every(permission => !canOrganization(undefined, permission))).toBe(true)
    expect(permissions.every(permission => !canOrganization('ADMIN', permission))).toBe(true)
  })
})
