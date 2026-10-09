// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import { ResourcePage } from './ResourcePage'
import { ConnectionPage } from './ConnectionPage'
import { IncidentPage } from './IncidentPage'
import { ConnectionsPage } from './ConnectionsPage'
import type { ResourceResponse } from '../types/resource'
import type { ConnectionResponse } from '../types/connection'
import type { IncidentListItemResponse } from '../types/incident'

const resource: ResourceResponse = {
  id: 'server', organizationId: 'org', environmentId: 'env', resourceTypeId: 'node',
  parentResourceId: null, code: 'node-01', name: 'Finland VPS', resourceTypeCode: 'NODE', active: true,
  createdAt: '', updatedAt: '2026-10-01T10:00:00Z', data: { kind: 'NODE', spec: null,
    status: { online: true, cpuUsagePercent: 12, memoryUsagePercent: 20, uptimeSeconds: 3600 } },
}
const connection: ConnectionResponse = {
  id: 'ssh', connectorType: 'SSH', code: 'technical-ssh', name: 'Finnish Connection', scope: { type: 'ORGANIZATION' },
  active: true, schedule: null, lastSync: null, createdAt: '', updatedAt: '',
  ssh: { host: 'fin.example', username: 'root', port: 22, authenticationType: 'PASSWORD',
    hostKeyFingerprint: 'SHA256:key', hostTrusted: true, credentialConfigured: true },
}
const incident: IncidentListItemResponse = {
  id: 'cpu', monitorRuleId: 'rule', resourceId: 'server', status: 'OPEN', reason: 'THRESHOLD',
  startedAt: '2026-10-01T10:00:00Z', openedAt: '2026-10-01T10:05:00Z', resolvedAt: null, createdAt: '', updatedAt: '',
  resource: { id: 'server', name: 'Finland VPS', resourceTypeCode: 'NODE' },
  project: { id: 'p', name: 'App' }, environment: { id: 'env', name: 'Production', kind: 'PROD' },
  monitorRule: { id: 'rule', metricCode: 'CPU_USAGE_PERCENT', operator: 'GREATER_THAN', threshold: 85, forSeconds: 300, noDataSeconds: 900 },
  parentResource: null, sourceConnections: [],
}
afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })
function client(role = 'OWNER') {
  const value = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  value.setQueryData(['me'], { id: 'u', email: 'u@example.com', displayName: 'User' })
  value.setQueryData(['my-organizations'], [{ id: 'org', code: 'org', name: 'Org', role }])
  value.setQueryData(['projects', 'org'], [{ id: 'p', organizationId: 'org', code: 'p', name: 'App', description: null }])
  value.setQueryData(['environments', 'org', 'p'], [{ id: 'env', organizationId: 'org', projectId: 'p', code: 'prod', name: 'Production', kind: 'PROD' }])
  value.setQueryData(['environment-context', 'org', 'env'], { project: incident.project, environment: incident.environment })
  value.setQueryData(['resource-context', 'org', 'server'], { project: incident.project, environment: incident.environment,
    parentResource: null, sourceConnections: [], children: [], activeChildCount: 0, openIncidentCount: 0 })
  value.setQueryData(['resource-operations', 'org', 'server'], { operations: [], unavailableReason: null })
  value.setQueryData(['resource-operation-executions', 'org', 'server'], [])
  value.setQueryData(['resource-integration-bindings', 'org', 'server'], [])
  value.setQueryData(['connection-summary', 'org', 'ssh'], { activeResourceCount: 0, inactiveResourceCount: 0,
    resourceTypeCounts: [], openIncidentCount: 0, openIncidents: [], resources: [] })
  return value
}
function show(value: QueryClient, path: string, route: string, Page: typeof ConnectionPage) {
  return render(<I18nProvider initialLocale="en"><QueryClientProvider client={value}>
    <MemoryRouter initialEntries={[path]}><Routes><Route path={route} element={<Page />} /></Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}
describe('working screen snapshot behavior', () => {
  it.each([
    { Page: ResourcePage, path: '/organizations/org/environments/env/resources/server', route: '/organizations/:organizationId/environments/:environmentId/resources/:resourceId', key: ['resource', 'org', 'server'], data: resource, title: 'Finland VPS' },
    { Page: ConnectionPage, path: '/organizations/org/connections/ssh', route: '/organizations/:organizationId/connections/:connectionId', key: ['connection', 'org', 'ssh'], data: connection, title: 'Finnish Connection' },
    { Page: IncidentPage, path: '/organizations/org/incidents/cpu', route: '/organizations/:organizationId/incidents/:incidentId', key: ['incident', 'org', 'cpu'], data: incident, title: 'CPU usage threshold breached' },
  ])('keeps $title on a failed refresh, but handles denied access as an unavailable page', async ({ Page, path, route, key, data, title }) => {
    let status = 500
    vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({ code: status === 403 ? 'FORBIDDEN' : 'INTERNAL_ERROR', message: 'diagnostic' }), { status }))))
    const value = client()
    value.setQueryData(key, data)
    show(value, path, route, Page)
    expect(screen.getByRole('heading', { level: 1, name: title })).toBeTruthy()
    await act(async () => { await value.refetchQueries({ queryKey: key, exact: true }) })
    expect(await screen.findByText('Unable to refresh data')).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1, name: title })).toBeTruthy()
    expect(screen.getByText(/Showing state from/)).toBeTruthy()
    expect(value.getQueryData(key)).toEqual(data)
    status = 403
    await act(async () => { await value.refetchQueries({ queryKey: key, exact: true }) })
    await waitFor(() => expect(screen.queryByRole('heading', { level: 1, name: title })).toBeNull())
    expect(screen.queryByText('Unable to refresh data')).toBeNull()
  })
  it('has one primary sync action, confirms the named connection and permits cancellation', async () => {
    const fetch = vi.fn((_url: RequestInfo | URL, _init?: RequestInit) => Promise.resolve(new Response('{}')))
    vi.stubGlobal('fetch', fetch)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const value = client()
    value.setQueryData(['connection', 'org', 'ssh'], connection)
    show(value, '/organizations/org/connections/ssh?project=p&environment=env', '/organizations/:organizationId/connections/:connectionId', ConnectionPage)
    const primary = document.querySelectorAll('.workspace-toolbar .primary-button')
    expect(primary).toHaveLength(1)
    expect(primary[0].textContent).toBe('Synchronize')
    expect(screen.getByRole('link', { name: 'Edit' }).getAttribute('href')).toContain('project=p&environment=env')
    expect(screen.queryByRole('menuitem', { name: 'Delete' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Finnish Connection'))
    expect(fetch.mock.calls.some(call => call[1]?.method === 'DELETE')).toBe(false)
    expect(document.querySelector('.workspace-title-line')?.textContent).toContain('Active')
    confirm.mockReturnValue(true)
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    await act(async () => { fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' })) })
    await waitFor(() => expect(fetch.mock.calls.some(call => String(call[0]) === '/api/v1/organizations/org/connections/ssh' && call[1]?.method === 'DELETE')).toBe(true))
  })
  it.each(['OWNER', 'MEMBER'])('shows connection edit menus only with permission: %s', role => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    const value = client(role)
    value.setQueryData(['connections', 'org'], [connection])
    value.setQueryData(['connection-infrastructure', 'org'], [])
    show(value, '/organizations/org/connections?project=p', '/organizations/:organizationId/connections', ConnectionsPage)
    expect(screen.queryAllByRole('button', { name: 'Actions' })).toHaveLength(role === 'OWNER' ? 1 : 0)
    if (role === 'OWNER') {
      fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
      expect(screen.getByRole('menuitem', { name: 'Edit' }).getAttribute('href')).toBe('/organizations/org/connections/ssh/edit?project=p')
    }
  })
})
