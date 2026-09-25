import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it } from 'vitest'
import { ResourceOperationsPanel } from './ResourceOperationsPanel'
import { operationAppearance, operationsApplicability } from './operationPresentation'

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
    expect(html).toContain('>Запустить</button>')
    expect(html).toContain('>Остановить</button>')
    expect(html).toContain('>Перезапустить</button>')
    // UNKNOWN is its own state, not a failure.
    expect(html).toContain('status-warning')
    expect(html).toContain('Результат неизвестен')
    expect(html).toContain('Результат операции неизвестен.')
    expect(html).not.toContain('Operation result is unknown because execution was interrupted')
  })

  it('keeps history visible and controls hidden for a member', () => {
    const html = render('MEMBER')
    expect(html).toContain('Последние запуски')
    expect(html).toContain('Результат неизвестен')
    expect(html).not.toContain('>Запустить</button>')
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

    expect(html).toContain('status-danger')
    expect(html).toContain('Docker не смог выполнить операцию.')
    expect(html).not.toContain('permission denied')
  })

  it('asks for confirmation instead of executing from the action button', () => {
    const html = renderWith(['CONTAINER_STOP'], [])

    // The button opens the confirmation dialog; nothing is sent until it is confirmed.
    expect(html).toContain('>Остановить</button>')
    expect(html).not.toContain('aria-modal="true"')
  })

  it('draws actions as buttons whose look says how much they interrupt', () => {
    const html = renderWith(['CONTAINER_START', 'CONTAINER_STOP', 'CONTAINER_RESTART'], [])

    expect(html).toMatch(/<button class="secondary-button operation-button" type="button"><svg[^>]*lucide-play[^>]*>.*?<\/svg>Запустить<\/button>/)
    expect(html).toMatch(/<button class="secondary-button operation-button impact-disruptive" type="button">.*?Остановить<\/button>/)
    expect(html).toMatch(/<button class="secondary-button operation-button impact-caution" type="button">.*?Перезапустить<\/button>/)
    // Actions are buttons, never links.
    expect(html).not.toMatch(/<a [^>]*>[^<]*(Запустить|Остановить)/)
  })

  it('makes a running execution visible and says the state follows the next synchronization', () => {
    const html = renderWith(['CONTAINER_STOP'], [{
      id: 'execution', resourceId: 'resource', operationCode: 'CONTAINER_STOP', status: 'RUNNING',
      actorUserId: 'actor', startedAt: '2026-09-24T10:00:00Z', finishedAt: null, errorCode: null, errorMessage: null,
    }])

    expect(html).toContain('class="operation-entry operation-running"')
    expect(html).toContain('status-indicator status-info')
    expect(html).toContain('Состояние контейнера изменится после следующей синхронизации')
  })

  it('tells a member why there are no action buttons', () => {
    const html = renderWith(['CONTAINER_STOP'], [], 'MEMBER')

    expect(html).toContain('Запускать операции может владелец организации.')
    expect(html).not.toContain('operation-button')
    expect(html).toContain('Операции ещё не выполнялись.')
  })
})

describe('operation presentation', () => {
  it('decides whether a resource has operations from the backend answers only', () => {
    const offered = { data: { operations: ['CONTAINER_STOP' as const], unavailableReason: null }, isError: false }
    const none = { data: { operations: [], unavailableReason: 'RESOURCE_UNSUPPORTED' }, isError: false }
    const ran = { data: [{ id: 'e', resourceId: 'r', operationCode: 'CONTAINER_STOP' as const, status: 'SUCCEEDED' as const,
      actorUserId: 'a', startedAt: '', finishedAt: null, errorCode: null, errorMessage: null }], isError: false }
    const never = { data: [], isError: false }
    const pending = { data: undefined, isError: false }

    expect(operationsApplicability(offered, never)).toBe('applicable')
    expect(operationsApplicability(none, ran)).toBe('applicable')
    expect(operationsApplicability(none, never)).toBe('not-applicable')
    expect(operationsApplicability(pending, never)).toBe('unknown')
    expect(operationsApplicability({ data: undefined, isError: true }, never)).toBe('applicable')
  })

  it('shows an operation it does not know as a plain action', () => {
    expect(operationAppearance('CONTAINER_STOP').impact).toBe('disruptive')
    expect(operationAppearance('CONTAINER_RESTART').impact).toBe('caution')
    expect(operationAppearance('SOMETHING_NEW').impact).toBe('normal')
  })
})
