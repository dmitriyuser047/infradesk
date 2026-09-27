import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import type { ResourceResponse } from '../../types/resource'
import { ResourceTree } from './ResourceTree'
import { buildFilteredResourceTree, noResourceFilter } from './resourceFilter'

/** The whole tree, as the page shows it before any filter. */
const unfiltered = (list: ResourceResponse[]) => buildFilteredResourceTree(list, noResourceFilter, () => { throw new Error('not filtering') }).roots

describe('resource tree row', () => {
  it('keeps code secondary inside Name and presents running as success', () => {
    const container: ResourceResponse = {
      id: 'container', organizationId: 'org', environmentId: 'env', resourceTypeId: 'type',
      parentResourceId: null, code: 'container-code', name: 'backend', resourceTypeCode: 'CONTAINER',
      active: true, createdAt: '', updatedAt: '',
      data: { kind: 'CONTAINER', spec: null, status: { state: 'running' } },
    }
    const html = renderToStaticMarkup(<MemoryRouter><ResourceTree roots={unfiltered([container])}
       organizationId="org" /></MemoryRouter>)
    expect(html).toMatch(/resource-name-stack.*resource-name.*backend.*resource-code.*container-code/s)
    expect(html).toContain('status-indicator status-success')
    expect(html).toContain('Работает')
    expect(html).toContain('Контейнер')
  })

  it('shows a parent kept only for context quieter than a result, and says so to screen readers', () => {
    const base = { organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', active: true, createdAt: '', updatedAt: '' }
    const server: ResourceResponse = { ...base, id: 'server', parentResourceId: null, code: 'server', name: 'finland_node',
      resourceTypeCode: 'NODE', data: { kind: 'NODE', spec: null, status: null } }
    const found: ResourceResponse = { ...base, id: 'found', parentResourceId: 'server', code: 'postgres', name: 'postgres',
      resourceTypeCode: 'CONTAINER', data: { kind: 'CONTAINER', spec: null, status: { state: 'running' } } }
    const html = renderToStaticMarkup(<MemoryRouter><ResourceTree roots={[{ resource: server, matched: false,
      children: [{ resource: found, matched: true, children: [] }] }]}  organizationId="org" /></MemoryRouter>)

    expect(html).toMatch(/class="resource-row resource-row-context".*finland_node<span class="visually-hidden"> \(показан как сервер найденного ресурса\)/s)
    expect(html).toMatch(/<div class="resource-row"><span class="tree-toggle-placeholder"[^>]*><\/span><a class="resource-link"[^>]*>.*postgres/s)
    // The context row is still a real link to the server.
    expect(html).toContain('href="/organizations/org/environments/env/resources/server"')
  })
})
