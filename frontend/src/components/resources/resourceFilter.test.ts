import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import type { ResourceResponse } from '../../types/resource'
import { resourcePresentationRegistry } from './presentation/resourcePresentations'
import {
  buildFilteredResourceTree,
  describeWithPresentations,
  isResourceFilterActive,
  noResourceFilter,
  type FilteredResourceNode,
  type ResourceFilterCriteria,
} from './resourceFilter'

function node(id: string, name: string, online: boolean | null, hostname = `${id}.example.test`): ResourceResponse {
  return { id, name, code: id, parentResourceId: null, resourceTypeCode: 'NODE', organizationId: 'org', environmentId: 'env',
    resourceTypeId: 'node-type', active: true, createdAt: '', updatedAt: '',
    data: { kind: 'NODE', spec: { hostname, operatingSystem: null, distribution: null, kernelVersion: null, architecture: null,
      cpuModel: null, cpuCores: null, memoryMb: null },
    status: online === null ? null : { online, cpuUsagePercent: null, memoryUsagePercent: null, uptimeSeconds: null } } }
}

function container(id: string, parent: string, state: string | null, image = 'nginx:1.27'): ResourceResponse {
  return { id, name: id, code: `${id}-code`, parentResourceId: parent, resourceTypeCode: 'CONTAINER', organizationId: 'org',
    environmentId: 'env', resourceTypeId: 'container-type', active: true, createdAt: '', updatedAt: '',
    data: { kind: 'CONTAINER', spec: { image }, status: state === null ? null : { state } } }
}

const fleet = [
  node('finland', 'finland_node', true),
  container('postgres', 'finland', 'running', 'postgres:17'),
  container('infra-api', 'finland', 'running'),
  container('infra-cron', 'finland', 'exited'),
  node('frankfurt', 'frankfurt_node', false),
  container('infra-worker', 'frankfurt', 'running'),
  container('redis', 'frankfurt', 'restarting', 'redis:7'),
  node('silent', 'silent_node', null),
]

const describeEn = describeWithPresentations(resourcePresentationRegistry, createI18n('en'))
const describeRu = describeWithPresentations(resourcePresentationRegistry, createI18n('ru'))

function filter(criteria: Partial<ResourceFilterCriteria>, describe = describeEn) {
  return buildFilteredResourceTree(fleet, { ...noResourceFilter, ...criteria }, describe)
}

/** The visible tree as `id` for a result and `(id)` for a parent kept only as context. */
function shape(nodes: readonly FilteredResourceNode[]): unknown[] {
  return nodes.map(item => {
    const label = item.matched ? item.resource.id : `(${item.resource.id})`
    return item.children.length === 0 ? label : [label, shape(item.children)]
  })
}

describe('resource filter', () => {
  it('shows the whole tree, all of it found, when no criterion is set', () => {
    const tree = filter({})

    expect(shape(tree.roots)).toEqual([
      ['finland', ['postgres', 'infra-api', 'infra-cron']], ['frankfurt', ['infra-worker', 'redis']], 'silent'])
    expect(tree.matched).toBe(8)
    expect(tree.total).toBe(8)
    expect(isResourceFilterActive(noResourceFilter)).toBe(false)
  })

  it('searches by name, ignoring case and surrounding spaces', () => {
    expect(shape(filter({ query: 'FINLAND_NODE' }).roots)).toEqual(['finland'])
    expect(shape(filter({ query: '   finland_node  ' }).roots)).toEqual(['finland'])
    // Spaces alone are no search at all.
    expect(isResourceFilterActive({ ...noResourceFilter, query: '   ' })).toBe(false)
  })

  it('also finds the code, the type in the active language and identity the resource already carries', () => {
    expect(shape(filter({ query: 'infra-cron-code' }).roots)).toEqual([['(finland)', ['infra-cron']]])
    expect(shape(filter({ query: 'postgres:17' }).roots)).toEqual([['(finland)', ['postgres']]])
    expect(shape(filter({ query: 'frankfurt.example.test' }).roots)).toEqual(['frankfurt'])
    expect(filter({ query: 'server' }).matched).toBe(3)
    expect(filter({ query: 'сервер' }, describeRu).matched).toBe(3)
  })

  it('keeps the server of a matching container as context, without its other containers', () => {
    const tree = filter({ query: 'postgres' })

    expect(shape(tree.roots)).toEqual([['(finland)', ['postgres']]])
    // The server is there to place the result; it was not found.
    expect(tree.matched).toBe(1)
  })

  it('shows a matching server without the containers that do not match', () => {
    expect(shape(filter({ query: 'frankfurt_node' }).roots)).toEqual(['frankfurt'])
  })

  it('hides a server when neither it nor any of its containers matches', () => {
    expect(shape(filter({ query: 'infra-worker' }).roots)).toEqual([['(frankfurt)', ['infra-worker']]])
  })

  it('filters by type from the type code', () => {
    expect(shape(filter({ type: 'NODE' }).roots)).toEqual(['finland', 'frankfurt', 'silent'])
    const containers = filter({ type: 'CONTAINER' })
    expect(shape(containers.roots)).toEqual([
      ['(finland)', ['postgres', 'infra-api', 'infra-cron']], ['(frankfurt)', ['infra-worker', 'redis']]])
    expect(containers.matched).toBe(5)
  })

  it('groups each type by its own status, leaving unknown ones out of both groups', () => {
    // Online servers and running containers.
    expect(shape(filter({ condition: 'RUNNING' }).roots)).toEqual([
      ['finland', ['postgres', 'infra-api']], ['(frankfurt)', ['infra-worker']]])
    // Offline servers and stopped containers; a restarting container and a server without a report are neither.
    expect(shape(filter({ condition: 'INACTIVE' }).roots)).toEqual([['(finland)', ['infra-cron']], 'frankfurt'])
  })

  it('combines search, type and status as the intersection of all three', () => {
    const tree = filter({ query: 'infra', type: 'CONTAINER', condition: 'RUNNING' })

    expect(shape(tree.roots)).toEqual([['(finland)', ['infra-api']], ['(frankfurt)', ['infra-worker']]])
    expect(tree.matched).toBe(2)
    expect(tree.total).toBe(8)
  })

  it('returns nothing, not empty parents, when nothing matches', () => {
    const tree = filter({ query: 'no-such-resource' })

    expect(tree.roots).toEqual([])
    expect(tree.matched).toBe(0)
  })
})
