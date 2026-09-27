import { describe, expect, it } from 'vitest'

import { en } from '../../i18n/en'
import { createFormatters } from '../../i18n/format'
import type { I18n } from '../../i18n'
import type { LocatedResourceResponse } from '../../types/infrastructure'
import { formatMonitorCondition } from '../monitoring/monitorRulePresentation'
import { originQuery, readOrigin, withTab, workspaceQuery } from './infrastructureLinks'
import { groupResourcesByEnvironment } from './resourceGroups'

const i18n = { locale: 'en', t: en, format: createFormatters('en', en.units) } as unknown as I18n

describe('infrastructure links', () => {
  it('carries the workspace context and where a link was followed from, and nothing else', () => {
    const current = new URLSearchParams('project=p&environment=e&tab=resources&status=OPEN')
    expect(originQuery(current, { kind: 'connection', id: 'c' })).toBe('?project=p&environment=e&fromConnection=c')
    expect(originQuery(current, { kind: 'resource', id: 'r', environmentId: 'e' }))
      .toBe('?project=p&environment=e&fromResource=r&fromEnvironment=e')
    expect(workspaceQuery(current)).toBe('?project=p&environment=e')
    // An environment means nothing without its project.
    expect(workspaceQuery(new URLSearchParams('environment=e'))).toBe('')
    expect(withTab('', 'incidents')).toBe('?tab=incidents')
  })

  it('reads an origin back, or none', () => {
    expect(readOrigin(new URLSearchParams('fromConnection=c'))).toEqual({ kind: 'connection', id: 'c' })
    expect(readOrigin(new URLSearchParams('fromResource=r&fromEnvironment=e'))).toEqual({ kind: 'resource', id: 'r', environmentId: 'e' })
    // Half an origin is no origin: back falls to the page's own parent.
    expect(readOrigin(new URLSearchParams('fromResource=r'))).toBeNull()
  })
})

describe('resource groups', () => {
  const resource = (id: string, environment: string, parentResourceId: string | null = null): LocatedResourceResponse => ({
    id, organizationId: 'org', environmentId: environment, resourceTypeId: 't', parentResourceId, code: id, name: id,
    resourceTypeCode: 'CONTAINER', active: true, createdAt: '', updatedAt: '',
    data: { kind: 'CONTAINER', spec: null, status: null }, environment: { id: environment, name: environment, kind: 'PROD' },
  })

  it('builds one tree per environment, in order, keeping orphans as roots', () => {
    const groups = groupResourcesByEnvironment([
      resource('node', 'prod'), resource('api', 'prod', 'node'), resource('lost', 'prod', 'elsewhere'), resource('worker', 'stage')])
    expect(groups.map(group => group.environment.id)).toEqual(['prod', 'stage'])
    expect(groups[0].roots.map(node => node.resource.id)).toEqual(['node', 'lost'])
    expect(groups[0].roots[0].children.map(node => node.resource.id)).toEqual(['api'])
    expect(groups[0].roots.every(node => node.matched)).toBe(true)
  })
})

describe('monitor condition', () => {
  it('is built from the typed rule fields', () => {
    expect(formatMonitorCondition({ metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN', threshold: 85, forSeconds: 300 }, i18n))
      .toBe('CPU usage > 85% for 5m')
    expect(formatMonitorCondition({ metricCode: 'MEMORY_USAGE_PERCENT', operator: 'LESS_THAN_OR_EQUAL', threshold: 10.5, forSeconds: 0 }, i18n))
      .toBe('Memory usage <= 10.5%')
  })
})
