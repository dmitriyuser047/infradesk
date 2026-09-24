import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { ResourcePage } from '../../../pages/ResourcePage'
import type { ResourceResponse } from '../../../types/resource'

function resource(resourceTypeCode: string, data: ResourceResponse['data']): ResourceResponse {
  return {
    id: 'resource', organizationId: 'org', environmentId: 'environment', resourceTypeId: 'type',
    parentResourceId: null, code: 'resource-code', name: 'Resource name', resourceTypeCode,
    active: true, createdAt: '', updatedAt: '', data,
  }
}

function renderResourcePage(value: ResourceResponse): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role: 'OWNER' }])
  client.setQueryData(['resource', 'org', 'resource'], value)

  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/environments/environment/resources/resource']}><Routes>
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId"
        element={<ResourcePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider>)
}

describe('resource page presentation', () => {
  const node = resource('NODE', {
    kind: 'NODE',
    spec: {
      hostname: 'node-1', operatingSystem: 'Linux', distribution: 'Ubuntu 24.04 LTS',
      kernelVersion: '6.8.0', architecture: 'x86_64', cpuModel: 'AMD EPYC', cpuCores: 4, memoryMb: 8192,
    },
    status: { online: true, cpuUsagePercent: 12.5, memoryUsagePercent: 37.5, uptimeSeconds: 3600 },
  })
  const container = resource('CONTAINER', {
    kind: 'CONTAINER', spec: { image: 'backend:2.0' }, status: { state: 'running' },
  })

  it('renders node properties, node status and the node tabs', () => {
    const html = renderResourcePage(node)

    expect(html).toContain('<dt>Hostname</dt><dd>node-1</dd>')
    expect(html).toContain('<dt>Operating system</dt><dd>Linux</dd>')
    expect(html).toContain('<dt>Distribution</dt><dd>Ubuntu 24.04 LTS</dd>')
    expect(html).toContain('<dt>Kernel</dt><dd>6.8.0</dd>')
    expect(html).toContain('<dt>CPU model</dt><dd>AMD EPYC</dd>')
    expect(html).toContain('<dt>Architecture</dt><dd>x86_64</dd>')
    expect(html).toContain('<dt>CPU cores</dt><dd>4</dd>')
    expect(html).toContain('status-indicator status-success')
    expect(html).toContain('Online')
    expect(html).toContain('id="tab-metrics"')
    expect(html).toContain('id="tab-rules"')
    expect(html).toContain('Monitor rules')
  })

  it('renders an em dash for missing optional node inventory fields', () => {
    if (node.data.kind !== 'NODE') throw new Error('Expected NODE test data')
    const withoutOptionalFacts = resource('NODE', {
      kind: 'NODE',
      status: node.data.status,
      spec: node.data.spec ? {
        ...node.data.spec, distribution: null, kernelVersion: null, cpuModel: null,
      } : null,
    })
    const html = renderResourcePage(withoutOptionalFacts)

    expect(html).toContain('<dt>Distribution</dt><dd>—</dd>')
    expect(html).toContain('<dt>Kernel</dt><dd>—</dd>')
    expect(html).toContain('<dt>CPU model</dt><dd>—</dd>')
    expect(html).toContain('<dt>CPU</dt><dd>12.5%</dd>')
    expect(html).toContain('<dt>Memory</dt><dd>37.5%</dd>')
    expect(html).toContain('<dt>Uptime</dt><dd>1h 0m</dd>')
  })

  it('renders container properties without the node-only tabs and header status', () => {
    const html = renderResourcePage(container)

    expect(html).toContain('<dt>Image</dt><dd>backend:2.0</dd>')
    expect(html).toContain('<dt>State</dt><dd>running</dd>')
    expect(html).not.toContain('id="tab-metrics"')
    expect(html).not.toContain('id="tab-rules"')
    expect(html).not.toContain('status-indicator')
  })

  it('renders the common shell and a safe fallback for an unknown resource type', () => {
    const html = renderResourcePage(resource('NEW_SERVER_TYPE', { kind: 'NODE', spec: null, status: null }))

    expect(html).toContain('Resource name')
    expect(html).toContain('NEW_SERVER_TYPE · resource-code')
    expect(html).toContain('Details are not available for this resource type')
    expect(html).not.toContain('id="tab-metrics"')
    expect(html).not.toContain('id="tab-rules"')
  })

  it('keeps the common shell identical across resource types', () => {
    for (const value of [node, container]) {
      const html = renderResourcePage(value)

      expect(html).toContain('Resource name')
      expect(html).toContain(`${value.resourceTypeCode} · resource-code`)
      expect(html).toContain('← Infrastructure')
      expect(html).toContain('Overview')
    }
  })
})
