// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { ResourceResponse } from '../types/resource'
import { EnvironmentPage } from './EnvironmentPage'

const base = { organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', active: true, createdAt: '', updatedAt: '' }
const fleet: ResourceResponse[] = [
  { ...base, id: 'finland', parentResourceId: null, code: 'finland', name: 'finland_node', resourceTypeCode: 'NODE',
    data: { kind: 'NODE', spec: null, status: { online: true, cpuUsagePercent: null, memoryUsagePercent: null, uptimeSeconds: null } } },
  { ...base, id: 'postgres', parentResourceId: 'finland', code: 'postgres', name: 'postgres', resourceTypeCode: 'CONTAINER',
    data: { kind: 'CONTAINER', spec: { image: 'postgres:17' }, status: { state: 'running' } } },
  { ...base, id: 'cron', parentResourceId: 'finland', code: 'cron', name: 'report-cron', resourceTypeCode: 'CONTAINER',
    data: { kind: 'CONTAINER', spec: { image: 'cron:1' }, status: { state: 'exited' } } },
  { ...base, id: 'frankfurt', parentResourceId: null, code: 'frankfurt', name: 'frankfurt_node', resourceTypeCode: 'NODE',
    data: { kind: 'NODE', spec: null, status: { online: false, cpuUsagePercent: null, memoryUsagePercent: null, uptimeSeconds: null } } },
]

const sources = [
  { resourceId: 'finland', sourceConnections: [{ id: 'finnish', name: 'Finnish Node', connectorType: 'SSH', active: true }] },
  { resourceId: 'postgres', sourceConnections: [{ id: 'finnish', name: 'Finnish Node', connectorType: 'SSH', active: true },
    { id: 'prom', name: 'Prometheus', connectorType: 'PROMETHEUS', active: true }] },
]

/**
 * Renders the page with the shell data seeded. The page makes two requests: the resource list and,
 * for the whole list at once, which connections discovered each resource.
 */
async function renderPage(resources: ResourceResponse[], locale: Locale = 'ru', role = 'MEMBER') {
  const fetchMock = vi.fn().mockImplementation((url: string) => Promise.resolve(new Response(
    JSON.stringify(String(url).endsWith('/resource-sources') ? sources : resources),
    { status: 200, headers: { 'Content-Type': 'application/json' } })))
  vi.stubGlobal('fetch', fetchMock)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'member@example.com', displayName: 'Member' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [
    { id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
  render(<I18nProvider initialLocale={locale}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/environments/env?project=project']}><Routes>
      <Route path="/organizations/:organizationId/environments/:environmentId" element={<EnvironmentPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  await act(async () => { await Promise.resolve() })
  return fetchMock
}

const rows = () => screen.queryAllByRole('treeitem').map(item => item.querySelector('.resource-name')?.firstChild?.textContent)

describe('resources page filters', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('shows available server metrics and keeps container counts independent of search', async () => {
    const data = fleet.map(item => item.id === 'finland' ? { ...item, updatedAt: '2026-10-01T10:00:00Z',
      data: { kind: 'NODE' as const, spec: null, status: { online: true, cpuUsagePercent: 17,
        memoryUsagePercent: 42, uptimeSeconds: 3600 } } } : item)
    await renderPage(data, 'en')
    await screen.findByRole('tree')
    expect(screen.getByRole('heading', { name: 'Servers' })).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Servers and containers' })).toBeTruthy()
    const row = () => screen.getByRole('link', { name: 'Open resource finland_node · Online' }).closest('.resource-row')!
    expect(row().querySelectorAll('.numeric-cell')[0].textContent).toBe('17.0%')
    expect(row().querySelectorAll('.numeric-cell')[1].textContent).toBe('42.0%')
    expect(row().querySelectorAll('.numeric-cell')[2].textContent).toBe('2')
    expect(row().querySelector('time')?.getAttribute('dateTime')).toBe('2026-10-01T10:00:00Z')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search resources' }), { target: { value: 'postgres' } })
    expect(document.querySelector('.resource-row .numeric-cell:nth-last-child(2)')?.textContent).toBe('2')
    expect(screen.getByRole('status').textContent).toBe('Showing 1 of 4')
  })

  it('keeps the inventory and stored online status when refresh fails', async () => {
    const fetch = await renderPage(fleet, 'en')
    await screen.findByRole('tree')
    fetch.mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ code: 'INTERNAL_ERROR' }), { status: 500 })))
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    expect(await screen.findByText('Unable to refresh data')).toBeTruthy()
    expect(rows()).toEqual(['finland_node', 'postgres', 'report-cron', 'frankfurt_node'])
    expect(screen.getByRole('link', { name: 'Open resource finland_node · Online' })).toBeTruthy()
    expect(screen.getByText(/Showing state from/)).toBeTruthy()
  })

  it('offers adding a connection in an empty environment only with permission and preserves context', async () => {
    await renderPage([], 'en', 'OWNER')
    expect(await screen.findByRole('link', { name: 'Add connection' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Add connection' }).getAttribute('href')).toBe('/organizations/org/connections/new?project=project&environment=env')
    cleanup()
    await renderPage([], 'en', 'MEMBER')
    await screen.findByText('No servers discovered yet')
    expect(screen.queryByRole('link', { name: 'Add connection' })).toBeNull()
  })

  it('searches, filters and resets the loaded list without another request', async () => {
    const fetchMock = await renderPage(fleet)
    await screen.findByRole('tree')
    const search = screen.getByRole('searchbox', { name: 'Поиск ресурсов' })

    // Nothing is filtered yet: no count, no reset.
    expect(screen.queryByText(/Показано/)).toBeNull()
    expect(screen.queryByRole('button', { name: 'Сбросить фильтры' })).toBeNull()

    for (const value of ['p', 'po', 'pos', 'POSTGRES', '  postgres  ']) fireEvent.change(search, { target: { value } })
    expect(rows()).toEqual(['finland_node', 'postgres'])
    // The server is context, not a result.
    expect(screen.getByRole('status').textContent).toBe('Показано 1 из 4')

    fireEvent.change(search, { target: { value: '' } })
    fireEvent.click(screen.getByRole('radio', { name: 'Контейнеры' }))
    fireEvent.click(screen.getByRole('radio', { name: 'Неактивны' }))
    expect(rows()).toEqual(['finland_node', 'report-cron'])
    expect(screen.getByRole('status').textContent).toBe('Показано 1 из 4')

    fireEvent.click(screen.getByRole('button', { name: 'Сбросить фильтры' }))
    expect(rows()).toEqual(['finland_node', 'postgres', 'report-cron', 'frankfurt_node'])
    expect((screen.getByRole('radio', { name: 'Контейнеры' }) as HTMLInputElement).checked).toBe(false)
    expect(screen.queryByRole('button', { name: 'Сбросить фильтры' })).toBeNull()

    // One request loaded the list and one its sources, whatever its size; typing and filtering made none.
    expect(fetchMock.mock.calls.map(([url]) => String(url)).sort()).toEqual([
      '/api/v1/organizations/org/environments/env/resource-sources', '/api/v1/organizations/org/environments/env/resources'])
  })

  it('names the connections that discovered each resource, without a request per row', async () => {
    await renderPage(fleet, 'en')
    await screen.findByRole('tree')
    expect(await screen.findByText('Source')).toBeTruthy()
    const postgres = screen.getAllByRole('treeitem').find(item => item.querySelector('.resource-name')?.firstChild?.textContent === 'postgres')
    expect(postgres?.querySelector('.resource-source-cell')?.textContent).toBe('Finnish Node +1')
  })

  it('clears the search with its button or with Escape', async () => {
    await renderPage(fleet)
    await screen.findByRole('tree')
    const search = screen.getByRole('searchbox', { name: 'Поиск ресурсов' }) as HTMLInputElement

    fireEvent.change(search, { target: { value: 'cron' } })
    fireEvent.click(screen.getByRole('button', { name: 'Очистить поиск' }))
    expect(search.value).toBe('')
    expect(screen.queryByRole('button', { name: 'Очистить поиск' })).toBeNull()

    fireEvent.change(search, { target: { value: 'cron' } })
    fireEvent.keyDown(search, { key: 'Escape' })
    expect(search.value).toBe('')
  })

  it('tells no results apart from no infrastructure', async () => {
    await renderPage(fleet)
    await screen.findByRole('tree')
    fireEvent.change(screen.getByRole('searchbox', { name: 'Поиск ресурсов' }), { target: { value: 'no-such-thing' } })

    expect(screen.getByText('Ресурсы не найдены')).toBeTruthy()
    expect(screen.getByText('Измените поиск или фильтры')).toBeTruthy()
    expect(screen.queryByRole('tree')).toBeNull()
    expect(screen.queryByText('Серверы ещё не обнаружены')).toBeNull()
    // The empty state offers its own way back, next to the one in the filter bar.
    const [, emptyStateReset] = screen.getAllByRole('button', { name: 'Сбросить фильтры' })
    fireEvent.click(emptyStateReset)
    expect(rows()).toHaveLength(4)

    cleanup()
    await renderPage([])
    expect(await screen.findByText('Серверы ещё не обнаружены')).toBeTruthy()
    expect(screen.queryByText('Ресурсы не найдены')).toBeNull()
    // Nothing to search in: no filter bar at all.
    expect(screen.queryByRole('searchbox', { name: 'Поиск ресурсов' })).toBeNull()
  })

  it('speaks English as well', async () => {
    await renderPage(fleet, 'en')
    await screen.findByRole('tree')
    const search = screen.getByRole('searchbox', { name: 'Search resources' })

    const type = screen.getByRole('group', { name: 'Type' })
    expect(within(type).getAllByRole('radio').map(radio => radio.parentElement?.textContent)).toEqual(['All', 'Servers', 'Containers'])
    const state = screen.getByRole('group', { name: 'Status' })
    expect(within(state).getAllByRole('radio').map(radio => radio.parentElement?.textContent)).toEqual(['All', 'Running', 'Inactive'])

    fireEvent.change(search, { target: { value: 'nothing' } })
    expect(screen.getByText('No resources found')).toBeTruthy()
    expect(screen.getByText('Change the search or the filters')).toBeTruthy()
    expect(screen.getByRole('status').textContent).toBe('Showing 0 of 4')
    expect(screen.getAllByRole('button', { name: 'Reset filters' }).length).toBeGreaterThan(0)
  })

  it('says so in English when nothing has been discovered', async () => {
    await renderPage([], 'en')
    expect(await screen.findByText('No servers discovered yet')).toBeTruthy()
  })
})
