import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import { getDisplayName, getEnvironmentKindLabel, selectProject, validSelection } from './navigationPresentation'

describe('navigation presentation', () => {
  it('prefers display names and falls back to a nonempty name or code, never an ID', () => {
    expect(getDisplayName({ displayName: '  Friendly name ', name: 'API name', code: 'slug' })).toBe('Friendly name')
    expect(getDisplayName({ name: 'API name', code: 'slug' })).toBe('API name')
    expect(getDisplayName({ name: ' ', code: 'slug' })).toBe('slug')
    const withId = { id: 'uuid', name: undefined }
    expect(getDisplayName(withId)).toBe('')
    expect(getDisplayName(null, 'Unavailable')).toBe('Unavailable')
  })
  it('labels known environment kinds and preserves unknown codes', () => {
    const en = createI18n('en')
    expect(getEnvironmentKindLabel('DEV', en)).toBe('Development')
    expect(getEnvironmentKindLabel('TEST', en)).toBe('Test')
    expect(getEnvironmentKindLabel('STAGE', en)).toBe('Staging')
    expect(getEnvironmentKindLabel('PROD', en)).toBe('Production')
    expect(getEnvironmentKindLabel('CUSTOM', en)).toBe('Custom')
    expect(getEnvironmentKindLabel('PROD', createI18n('ru'))).toBe('Продакшен')
    expect(getEnvironmentKindLabel('FUTURE', en)).toBe('FUTURE')
  })

  it('chooses the first item initially and when a selection disappears', () => {
    const items = [{ id: 'first' }, { id: 'second' }]
    expect(validSelection(null, items)).toBe('first')
    expect(validSelection('second', items)).toBe('second')
    expect(validSelection('removed', items)).toBe('first')
    expect(validSelection('removed', [])).toBeNull()
  })

  it('resets the previous environment when the project changes', () => {
    expect(selectProject('project-b')).toEqual({ projectId: 'project-b', environmentId: null })
    expect(validSelection(null, [{ id: 'environment-b' }])).toBe('environment-b')
    expect(validSelection('removed-environment', [{ id: 'environment-b' }])).toBe('environment-b')
  })
})
