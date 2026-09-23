import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import type { ResourceResponse } from '../../types/resource'
import { ResourceTree } from './ResourceTree'

describe('resource tree row', () => {
  it('keeps code secondary inside Name and presents running as success', () => {
    const container: ResourceResponse = {
      id: 'container', organizationId: 'org', environmentId: 'env', resourceTypeId: 'type',
      parentResourceId: null, code: 'container-code', name: 'backend', resourceTypeCode: 'CONTAINER',
      active: true, createdAt: '', updatedAt: '',
      data: { kind: 'CONTAINER', spec: null, status: { state: 'running' } },
    }
    const html = renderToStaticMarkup(<MemoryRouter><ResourceTree resources={[container]}
      organizationId="org" environmentId="env" /></MemoryRouter>)
    expect(html).toMatch(/resource-name-stack.*resource-name.*backend.*resource-code.*container-code/s)
    expect(html).toContain('status-indicator status-success')
    expect(html).toContain('Running')
  })
})
