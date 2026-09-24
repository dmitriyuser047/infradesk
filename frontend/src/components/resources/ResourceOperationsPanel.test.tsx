import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ResourceOperationsPanel } from './ResourceOperationsPanel'

function render(role: 'OWNER' | 'MEMBER') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', role }])
  client.setQueryData(['resource-operations', 'org', 'resource'], {
    operations: ['CONTAINER_START', 'CONTAINER_STOP', 'CONTAINER_RESTART'], unavailableReason: null,
  })
  client.setQueryData(['resource-operation-executions', 'org', 'resource'], [{
    id: 'execution', resourceId: 'resource', operationCode: 'CONTAINER_RESTART', status: 'UNKNOWN',
    actorUserId: 'actor', startedAt: '2026-09-24T10:00:00Z', finishedAt: '2026-09-24T10:10:00Z',
    errorCode: 'OPERATION_RESULT_UNKNOWN', errorMessage: 'Operation result is unknown because execution was interrupted',
  }])
  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <ResourceOperationsPanel organizationId="org" resourceId="resource" resourceName="backend" />
  </QueryClientProvider>)
}

function renderWith(
  operations: string[],
  history: Array<Record<string, unknown>>,
  role: 'OWNER' | 'MEMBER' = 'OWNER',
  unavailableReason: string | null = null,
) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['my-organizations'], [{ id: 'org', role }])
  client.setQueryData(['resource-operations', 'org', 'resource'], { operations, unavailableReason })
  client.setQueryData(['resource-operation-executions', 'org', 'resource'], history)
  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <ResourceOperationsPanel organizationId="org" resourceId="resource" resourceName="backend" />
  </QueryClientProvider>)
}

describe('resource operations panel', () => {
  it('shows controlled actions and distinct UNKNOWN history to an owner', () => {
    const html = render('OWNER')
    expect(html).toContain('>Start</button>')
    expect(html).toContain('>Stop</button>')
    expect(html).toContain('>Restart</button>')
    expect(html).toContain('operation-status-unknown')
    expect(html).toContain('Operation result is unknown because execution was interrupted')
  })

  it('keeps history visible and controls hidden for a member', () => {
    const html = render('MEMBER')
    expect(html).toContain('Recent executions')
    expect(html).toContain('UNKNOWN')
    expect(html).not.toContain('>Start</button>')
  })

  it('stays out of the page for a resource without operations or history', () => {
    // A NODE, or a container behind an ambiguous target, gets no controls and no empty section.
    expect(renderWith([], [], 'OWNER', 'RESOURCE_UNSUPPORTED')).toBe('')
  })

  it('renders a failed execution with its safe message only', () => {
    const html = renderWith(['CONTAINER_RESTART'], [{
      id: 'execution', resourceId: 'resource', operationCode: 'CONTAINER_RESTART', status: 'FAILED',
      actorUserId: 'actor', startedAt: '2026-09-24T10:00:00Z', finishedAt: '2026-09-24T10:00:03Z',
      errorCode: 'DOCKER_OPERATION_FAILED', errorMessage: 'Docker container restart failed',
    }])

    expect(html).toContain('operation-status-failed')
    expect(html).toContain('Docker container restart failed')
    expect(html).not.toContain('permission denied')
  })

  it('asks for confirmation instead of executing from the action button', () => {
    const html = renderWith(['CONTAINER_STOP'], [])

    // The button opens the confirmation dialog; nothing is sent until it is confirmed.
    expect(html).toContain('>Stop</button>')
    expect(html).not.toContain('aria-modal="true"')
  })
})
