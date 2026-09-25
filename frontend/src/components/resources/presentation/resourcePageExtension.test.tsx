import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'

import { ResourcePage } from '../../../pages/ResourcePage'
import { ResourceTree } from '../ResourceTree'
import { buildFilteredResourceTree, noResourceFilter } from '../resourceFilter'

/** The whole tree, as the page shows it before any filter. */
const unfiltered = (list: ResourceResponse[]) => buildFilteredResourceTree(list, noResourceFilter, () => { throw new Error('not filtering') }).roots
import type { ResourceResponse } from '../../../types/resource'

/**
 * Architecture acceptance test: a resource type that exists only in this test is displayed by the
 * generic resource shell. It took a presentation plus one registration; no production component
 * mentions TEST_RESOURCE.
 */
vi.mock('./resourcePresentations', async () => {
  const { Database } = await import('lucide-react')
  const { createResourcePresentationRegistry } = await import('./ResourcePresentationRegistry')
  const actual = await vi.importActual<typeof import('./resourcePresentations')>('./resourcePresentations')

  const testPresentation = {
    code: 'TEST_RESOURCE',
    label: () => 'Test resource',
    Icon: Database,
    rowStatus: () => ({ label: 'Test row status', tone: 'info' as const }),
    headerStatus: () => ({ label: 'Test header status', tone: 'warning' as const }),
    Overview: ({ resource }: { resource: ResourceResponse }) =>
      <dl className="property-grid"><div className="property-row"><dt>Test detail</dt><dd>{resource.code}</dd></div></dl>,
  }
  const presentations = [...actual.resourcePresentations, testPresentation]

  return {
    resourcePresentations: presentations,
    resourcePresentationRegistry: createResourcePresentationRegistry(presentations),
  }
})

const resource: ResourceResponse = {
  id: 'resource', organizationId: 'org', environmentId: 'environment', resourceTypeId: 'type',
  parentResourceId: null, code: 'test-code', name: 'Test resource name', resourceTypeCode: 'TEST_RESOURCE',
  active: true, createdAt: '', updatedAt: '',
  data: { kind: 'NODE', spec: null, status: null },
}

describe('registering a new resource presentation', () => {
  it('shows the new type through the generic resource page', () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
    client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role: 'OWNER' }])
    client.setQueryData(['resource', 'org', 'resource'], resource)

    const html = renderToStaticMarkup(<QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/organizations/org/environments/environment/resources/resource']}><Routes>
        <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId"
          element={<ResourcePage />} />
      </Routes></MemoryRouter>
    </QueryClientProvider>)

    expect(html).toContain('Test resource name')
    expect(html).toContain('<dt>Test detail</dt><dd>test-code</dd>')
    expect(html).toContain('Test header status')
    expect(html).not.toContain('Подробности для этого типа ресурса недоступны')
    // A new presentation does not make monitoring apply to the type: that is a feature rule.
    expect(html).not.toContain('id="tab-metrics"')
    expect(html).not.toContain('id="tab-rules"')
  })

  it('shows the new type through the generic infrastructure tree', () => {
    const html = renderToStaticMarkup(<MemoryRouter>
      <ResourceTree roots={unfiltered([resource])} organizationId="org" environmentId="environment" />
    </MemoryRouter>)

    expect(html).toContain('Test resource name')
    expect(html).toContain('TEST_RESOURCE')
    expect(html).toContain('status-indicator status-info')
    expect(html).toContain('Test row status')
  })
})
