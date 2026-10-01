import { describe, expect, it } from 'vitest'
import { createI18n } from '../../i18n'
import type { ResourceResponse } from '../../types/resource'
import { resourceChildrenTitle, resourceDisplayName, resourceTerminalConnection } from './resourceInventoryPresentation'

const child = { resourceTypeCode: 'CONTAINER' } as ResourceResponse
describe('resource child section identity', () => {
  it('uses a display name, falling back to hostname, and offers only an unambiguous SSH source', () => {
    const node = { name: 'Finland VPS', code: 'internal', active: true, resourceTypeCode: 'NODE',
      data: { kind: 'NODE', spec: { hostname: 'fin.example' }, status: null } } as ResourceResponse
    const source = { id: 'ssh', name: 'SSH', connectorType: 'SSH', active: true }
    expect(resourceDisplayName(node)).toBe('Finland VPS')
    expect(resourceDisplayName({ ...node, name: ' ' })).toBe('fin.example')
    expect(resourceTerminalConnection(node, [source])).toEqual(source)
    expect(resourceTerminalConnection(node, [source, { ...source, id: 'other' }])).toBeUndefined()
    expect(resourceTerminalConnection({ ...node, active: false }, [source])).toBeUndefined()
    expect(resourceTerminalConnection({ ...node, resourceTypeCode: 'CONTAINER' }, [source])).toBeUndefined()
  })
  it('names a complete container list and keeps incomplete or mixed previews generic', () => {
    const i18n = createI18n('ru')
    expect(resourceChildrenTitle([child], 1, i18n)).toBe('Контейнеры')
    expect(resourceChildrenTitle([child], 4, i18n)).toBe(i18n.t.infrastructure.children)
    expect(resourceChildrenTitle([child, { ...child, resourceTypeCode: 'NEW_TYPE' }], 2, i18n)).toBe(i18n.t.infrastructure.children)
    expect(resourceChildrenTitle([], 0, i18n)).toBe(i18n.t.infrastructure.children)
    expect(resourceChildrenTitle([child], 1, createI18n('en'))).toBe('Containers')
  })
})
