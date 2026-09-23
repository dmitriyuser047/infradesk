import { describe, expect, it } from 'vitest'
import type { ResourceResponse } from '../../types/resource'
import { filterResourcesForTree } from './filterResourcesForTree'

function resource(id: string, parentResourceId: string | null, type: 'NODE' | 'CONTAINER'): ResourceResponse {
  return { id, parentResourceId, organizationId: 'org', environmentId: 'env', resourceTypeId: type,
    name: id, code: id, resourceTypeCode: type, active: true, createdAt: '', updatedAt: '',
    data: type === 'NODE' ? { kind: 'NODE', spec: null, status: null } :
      { kind: 'CONTAINER', spec: null, status: null } }
}

describe('resource tree filtering', () => {
  const rows = [resource('finland', null, 'NODE'), resource('nginx', 'finland', 'CONTAINER'),
    resource('other', null, 'NODE')]

  it('keeps parent nodes visible when a child matches', () => {
    expect(filterResourcesForTree(rows, 'nginx', 'CONTAINER').map(row => row.id)).toEqual(['finland', 'nginx'])
  })

  it('filters nodes without inventing child rows', () => {
    expect(filterResourcesForTree(rows, '', 'NODE').map(row => row.id)).toEqual(['finland', 'other'])
  })
})
