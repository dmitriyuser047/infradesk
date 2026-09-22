import { describe, expect, it } from 'vitest'

import { getEnvironmentKindLabel, selectProject, validSelection } from './navigationPresentation'

describe('navigation presentation', () => {
  it('labels known environment kinds and preserves unknown codes', () => {
    expect(getEnvironmentKindLabel('DEV')).toBe('Development')
    expect(getEnvironmentKindLabel('TEST')).toBe('Test')
    expect(getEnvironmentKindLabel('STAGE')).toBe('Staging')
    expect(getEnvironmentKindLabel('PROD')).toBe('Production')
    expect(getEnvironmentKindLabel('CUSTOM')).toBe('Custom')
    expect(getEnvironmentKindLabel('FUTURE')).toBe('FUTURE')
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
