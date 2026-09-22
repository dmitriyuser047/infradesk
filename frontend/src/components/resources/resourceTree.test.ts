import { describe, expect, it } from 'vitest'

import { buildResourceTree } from './resourceTreeModel'
import type { ResourceResponse } from '../../types/resource'

describe('buildResourceTree', () => {
  it('creates one NODE root with containers as children', () => {
    const node = resource('node-1', null, 'NODE')
    const firstContainer = resource('container-1', node.id, 'CONTAINER')
    const secondContainer = resource('container-2', node.id, 'CONTAINER')

    const tree = buildResourceTree([node, firstContainer, secondContainer])

    expect(tree).toHaveLength(1)
    expect(tree[0]?.resource.id).toBe(node.id)
    expect(tree[0]?.children.map((child) => child.resource.id)).toEqual([
      firstContainer.id,
      secondContainer.id,
    ])
  })

  it('supports multiple NODE roots', () => {
    const firstNode = resource('node-1', null, 'NODE')
    const secondNode = resource('node-2', null, 'NODE')

    const tree = buildResourceTree([firstNode, secondNode])

    expect(tree.map((node) => node.resource.id)).toEqual([firstNode.id, secondNode.id])
  })

  it('places an orphan resource at the root', () => {
    const orphan = resource('container-1', 'missing-parent', 'CONTAINER')

    const tree = buildResourceTree([orphan])

    expect(tree.map((node) => node.resource.id)).toEqual([orphan.id])
  })

  it('does not mutate the source list or resource objects', () => {
    const node = resource('node-1', null, 'NODE')
    const container = resource('container-1', node.id, 'CONTAINER')
    const source = [node, container]
    const before = structuredClone(source)

    buildResourceTree(source)

    expect(source).toEqual(before)
  })
})

function resource(
  id: string,
  parentResourceId: string | null,
  resourceTypeCode: 'NODE' | 'CONTAINER',
): ResourceResponse {
  return {
    id,
    organizationId: 'organization-1',
    environmentId: 'environment-1',
    resourceTypeId: `${resourceTypeCode.toLowerCase()}-type`,
    parentResourceId,
    code: id,
    name: id,
    resourceTypeCode,
    active: true,
    data: resourceTypeCode === 'NODE'
      ? { kind: 'NODE', spec: null, status: null }
      : { kind: 'CONTAINER', spec: null, status: null },
    createdAt: '2026-09-22T10:00:00Z',
    updatedAt: '2026-09-22T10:00:00Z',
  }
}
