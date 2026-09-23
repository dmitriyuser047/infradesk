import { describe, expect, it } from 'vitest'

import { containerPresentation } from './ContainerPresentation'
import { defaultResourcePresentation } from './defaultResourcePresentation'
import { nodePresentation } from './NodePresentation'
import { createResourcePresentationRegistry } from './ResourcePresentationRegistry'
import { resourcePresentationRegistry, resourcePresentations } from './resourcePresentations'
import type { ResourceResponse } from '../../../types/resource'

function resource(resourceTypeCode: string, data: ResourceResponse['data']): ResourceResponse {
  return {
    id: 'resource', organizationId: 'org', environmentId: 'env', resourceTypeId: 'type',
    parentResourceId: null, code: 'code', name: 'name', resourceTypeCode, active: true,
    createdAt: '', updatedAt: '', data,
  }
}

describe('resource presentation registry', () => {
  it('resolves the shipped resource types to their own presentation', () => {
    expect(resourcePresentationRegistry.resolve('NODE')).toBe(nodePresentation)
    expect(resourcePresentationRegistry.resolve('CONTAINER')).toBe(containerPresentation)
    expect(resourcePresentations.map(presentation => presentation.code)).toEqual(['NODE', 'CONTAINER'])
  })

  it('falls back for a resource type this frontend does not know', () => {
    const rendering = resourcePresentationRegistry.resolve('NEW_SERVER_TYPE')

    expect(rendering).toBe(defaultResourcePresentation)
    expect(rendering.rowStatus(resource('NEW_SERVER_TYPE', { kind: 'NODE', spec: null, status: null })))
      .toEqual({ label: 'Unknown', tone: 'neutral' })
    expect(rendering.headerStatus).toBeUndefined()
    expect(rendering.MetricSummary).toBeUndefined()
  })

  it('refuses to register the same resource type code twice', () => {
    expect(() => createResourcePresentationRegistry([nodePresentation, containerPresentation, nodePresentation]))
      .toThrow('Duplicate resource presentation code: NODE')
  })

  it('keeps the row status of each shipped type', () => {
    expect(nodePresentation.rowStatus(resource('NODE', {
      kind: 'NODE', spec: null, status: { online: true, cpuUsagePercent: null, memoryUsagePercent: null, uptimeSeconds: null },
    }))).toEqual({ label: 'Online', tone: 'success' })
    expect(nodePresentation.rowStatus(resource('NODE', { kind: 'NODE', spec: null, status: null })))
      .toEqual({ label: 'Unknown', tone: 'neutral' })
    expect(containerPresentation.rowStatus(resource('CONTAINER', {
      kind: 'CONTAINER', spec: null, status: { state: 'running' },
    }))).toEqual({ label: 'Running', tone: 'success' })
    expect(containerPresentation.rowStatus(resource('CONTAINER', { kind: 'CONTAINER', spec: null, status: null })))
      .toEqual({ label: 'Unknown', tone: 'neutral' })
  })

  it('carries only the optional rendering pieces each type actually has', () => {
    expect(nodePresentation.MetricSummary).toBeDefined()
    expect(containerPresentation.MetricSummary).toBeUndefined()
    expect(nodePresentation.headerStatus).toBeDefined()
    expect(containerPresentation.headerStatus).toBeUndefined()
  })
})
