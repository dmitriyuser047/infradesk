import { describe, expect, it } from 'vitest'

import { getOrganizationRoleLabel, safeReturnPath } from './authPresentation'

describe('auth presentation', () => {
  it('labels known roles and preserves future codes', () => {
    expect(getOrganizationRoleLabel('OWNER')).toBe('Owner')
    expect(getOrganizationRoleLabel('MEMBER')).toBe('Member')
    expect(getOrganizationRoleLabel('FUTURE')).toBe('FUTURE')
  })

  it('accepts only internal return paths', () => {
    expect(safeReturnPath('/organizations/one')).toBe('/organizations/one')
    expect(safeReturnPath('https://example.com')).toBeNull()
    expect(safeReturnPath('//example.com')).toBeNull()
    expect(safeReturnPath('/\\example.com')).toBeNull()
    expect(safeReturnPath(null)).toBeNull()
  })
})
