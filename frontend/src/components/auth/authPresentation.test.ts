import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import { getOrganizationRoleLabel, safeReturnPath } from './authPresentation'

describe('auth presentation', () => {
  it('labels known roles in both languages and preserves future codes', () => {
    const en = createI18n('en')
    const ru = createI18n('ru')
    expect(getOrganizationRoleLabel('OWNER', en)).toBe('Owner')
    expect(getOrganizationRoleLabel('MEMBER', en)).toBe('Member')
    expect(getOrganizationRoleLabel('OWNER', ru)).toBe('Владелец')
    expect(getOrganizationRoleLabel('MEMBER', ru)).toBe('Участник')
    expect(getOrganizationRoleLabel('FUTURE', ru)).toBe('FUTURE')
  })

  it('accepts only internal return paths', () => {
    expect(safeReturnPath('/organizations/one')).toBe('/organizations/one')
    expect(safeReturnPath('https://example.com')).toBeNull()
    expect(safeReturnPath('//example.com')).toBeNull()
    expect(safeReturnPath('/\\example.com')).toBeNull()
    expect(safeReturnPath(null)).toBeNull()
  })
})
