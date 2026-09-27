// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { IncidentListItemResponse } from '../types/incident'
import { IncidentsPage } from './IncidentsPage'

function incident(index: number, status: 'OPEN' | 'RESOLVED' = 'OPEN'): IncidentListItemResponse {
  const resourceId = `resource-${index}`
  return {
    id: `incident-${index}`, monitorRuleId: `rule-${index}`, resourceId, status, reason: 'THRESHOLD',
    startedAt: '2026-09-25T10:00:00Z', openedAt: '2026-09-25T10:00:00Z',
    resolvedAt: status === 'RESOLVED' ? '2026-09-25T10:30:00Z' : null, createdAt: '', updatedAt: '',
    resource: { id: resourceId, name: `node-${index}`, resourceTypeCode: index % 2 === 0 ? 'NODE' : 'CONTAINER' },
    project: { id: 'project', name: 'SvinPeak' }, environment: { id: 'env', name: 'Production', kind: 'PROD' },
    monitorRule: { id: 'rule', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN', threshold: 85, forSeconds: 300, noDataSeconds: 900 },
    parentResource: null, sourceConnections: [{ id: 'connection', name: 'Finnish Node', connectorType: 'SSH', active: true }],
  }
}

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** The list answers by the status it is asked for; the shell's own data is seeded, the incidents start cold. */
function renderPage(options: { path?: string; locale?: Locale; answer?: (url: string) => Response | Promise<Response> } = {}) {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(options.answer ? options.answer(String(url)) : json([])))
  vi.stubGlobal('fetch', fetchMock)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'MEMBER' }])
  client.setQueryData(['projects', 'org'], [])
  render(<I18nProvider initialLocale={options.locale ?? 'ru'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[options.path ?? '/organizations/org/incidents']}><Routes>
      <Route path="/organizations/:organizationId/incidents" element={<IncidentsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return fetchMock
}

const rows = () => within(screen.getByRole('list', { name: 'Список инцидентов' })).getAllByRole('listitem')
const urls = (fetchMock: ReturnType<typeof vi.fn>) => fetchMock.mock.calls.map(([url]) => String(url))
const list = '/api/v1/organizations/org/incidents'

describe('incidents page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it.each([1, 12])('renders %i incidents on distinct resources from one request', async count => {
    const incidents = Array.from({ length: count }, (_, index) => incident(index))
    const fetchMock = renderPage({ answer: () => json(incidents) })

    expect(await screen.findByText(`node-${count - 1}`)).toBeTruthy()
    expect(rows()).toHaveLength(count)
    // One read for the list, however many resources it mentions: no request per row.
    expect(urls(fetchMock)).toEqual([`${list}?limit=50&status=OPEN`])
  })

  it('opens on the open incidents and asks for each filter once, through the status the list already takes', async () => {
    const fetchMock = renderPage({ answer: url => json(url.endsWith('RESOLVED') ? [incident(1, 'RESOLVED')]
      : url.endsWith('OPEN') ? [incident(0)] : [incident(0), incident(1, 'RESOLVED')]) })
    await screen.findByText('node-0')
    expect((screen.getByRole('radio', { name: 'Открытые' }) as HTMLInputElement).checked).toBe(true)
    expect(screen.getByRole('group', { name: 'Фильтр по статусу инцидента' })).toBeTruthy()

    fireEvent.click(screen.getByRole('radio', { name: 'Закрытые' }))
    expect(await screen.findByText('node-1')).toBeTruthy()
    expect(screen.queryByText('node-0')).toBeNull()
    expect(screen.getByText(/^Закрыт /)).toBeTruthy()

    fireEvent.click(screen.getByRole('radio', { name: 'Все' }))
    await waitFor(() => expect(rows()).toHaveLength(2))

    expect(urls(fetchMock)).toEqual([`${list}?limit=50&status=OPEN`, `${list}?limit=50&status=RESOLVED`, `${list}?limit=50`])
  })

  it('tells a healthy "no open incidents" apart from the other empty lists', async () => {
    renderPage()
    expect(await screen.findByText('Открытых инцидентов нет')).toBeTruthy()
    expect(screen.getByText('Инфраструктура не требует внимания')).toBeTruthy()
    expect(screen.getByRole('status').className).toContain('empty-success')
    cleanup()

    renderPage({ path: '/organizations/org/incidents?status=RESOLVED' })
    expect(await screen.findByText('Закрытых инцидентов пока нет')).toBeTruthy()
    expect(document.querySelector('.empty-success')).toBeNull()
    cleanup()

    renderPage({ path: '/organizations/org/incidents?status=ALL' })
    expect(await screen.findByText('Инцидентов пока нет')).toBeTruthy()
  })

  it('speaks English', async () => {
    renderPage({ locale: 'en' })
    expect(await screen.findByText('No open incidents')).toBeTruthy()
    expect(screen.getByText('Infrastructure needs no attention')).toBeTruthy()
    expect(screen.getAllByRole('radio').map(radio => radio.parentElement?.textContent)).toEqual(['Open', 'Resolved', 'All'])
    cleanup()

    renderPage({ locale: 'en', path: '/organizations/org/incidents?status=RESOLVED' })
    expect(await screen.findByText('No resolved incidents yet')).toBeTruthy()
    cleanup()

    renderPage({ locale: 'en', path: '/organizations/org/incidents?status=ALL' })
    expect(await screen.findByText('No incidents yet')).toBeTruthy()
  })

  it('shows loading until the list arrives', () => {
    renderPage({ answer: () => new Promise<Response>(() => undefined) })

    expect(screen.getByLabelText('Загрузка инцидентов')).toBeTruthy()
  })

  it('explains a failed load in Russian without the server message', async () => {
    renderPage({ answer: () => json({ code: 'INTERNAL_ERROR', message: 'database password leaked' }, 500) })

    expect(await screen.findByText('Не удалось загрузить инциденты')).toBeTruthy()
    expect(document.body.textContent).not.toContain('database password leaked')
    expect(screen.getByRole('button', { name: 'Повторить' })).toBeTruthy()
  })

  it('keeps the last list when a refresh fails, and says so', async () => {
    let fail = false
    renderPage({ answer: () => fail ? json({ code: 'INTERNAL_ERROR', message: 'x' }, 500) : json([incident(0)]) })
    await screen.findByText('node-0')

    fail = true
    fireEvent.click(screen.getByRole('button', { name: 'Обновить' }))

    expect(await screen.findByText('Не удалось обновить список инцидентов')).toBeTruthy()
    expect(screen.getByText('node-0')).toBeTruthy()
    expect(screen.getByText(/Показан список на/)).toBeTruthy()
  })

  it.each(['OPEN', 'RESOLVED', 'ALL'] as const)('pages the %s list: a full page offers more, the next continues after its last row', async filter => {
    const status = filter === 'ALL' ? 'OPEN' : filter
    // Every incident opened at the same instant: only the id tells the pages apart.
    const first = Array.from({ length: 50 }, (_, index) => incident(index, status))
    const second = Array.from({ length: 3 }, (_, index) => incident(50 + index, status))
    const fetchMock = renderPage({ path: `/organizations/org/incidents?status=${filter}`,
      answer: url => json(url.includes('beforeId=') ? second : first) })
    await screen.findByText('node-49')
    expect(screen.getByText('50+ инцидентов')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Показать ещё' }))
    expect(await screen.findByText('node-52')).toBeTruthy()
    expect(rows()).toHaveLength(53)
    // A short page is the last one: no more button, and the count is now exact.
    expect(screen.queryByRole('button', { name: 'Показать ещё' })).toBeNull()
    expect(screen.getByText('53 инцидента')).toBeTruthy()
    const next = new URL(urls(fetchMock)[1], 'http://localhost').searchParams
    expect(next.get('beforeOpenedAt')).toBe('2026-09-25T10:00:00Z')
    expect(next.get('beforeId')).toBe('incident-49')
    expect(next.get('status')).toBe(filter === 'ALL' ? null : filter)
  })

  it('starts another filter from its own first page, not from the cursor of the previous one', async () => {
    const fetchMock = renderPage({ answer: url => json(url.includes('beforeId=') ? []
      : Array.from({ length: 50 }, (_, index) => incident(index, url.includes('RESOLVED') ? 'RESOLVED' : 'OPEN'))) })
    await screen.findByText('node-49')
    fireEvent.click(screen.getByRole('button', { name: 'Показать ещё' }))
    await waitFor(() => expect(urls(fetchMock)).toHaveLength(2))
    fireEvent.click(screen.getByRole('radio', { name: 'Закрытые' }))
    await waitFor(() => expect(urls(fetchMock)).toHaveLength(3))
    expect(urls(fetchMock)[2]).toBe(`${list}?limit=50&status=RESOLVED`)
  })
})
