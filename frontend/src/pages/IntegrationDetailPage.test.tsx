// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { IntegrationOverview, IntegrationResponse, IntegrationSyncSession, InventoryObject,
  RemnawaveNodeSummary } from '../types/integration'
import { IntegrationDetailPage } from './IntegrationDetailPage'
import { IntegrationsPage } from './IntegrationsPage'

const base = '/api/v1/organizations/org/integrations/one'
const integration: IntegrationResponse = { id: 'one', name: 'Main Remnawave', providerType: 'REMNAWAVE',
  baseUrl: 'https://panel.example.test', enabled: false,
  credential: { apiTokenConfigured: true, caddyApiKeyConfigured: false }, createdAt: '', updatedAt: '' }
const session = (status: IntegrationSyncSession['status'], errorCode: string | null = null): IntegrationSyncSession => ({
  id: `s-${status}`, integrationId: 'one', trigger: 'MANUAL', status, startedAt: '2026-09-29T10:00:00Z',
  finishedAt: '2026-09-29T10:00:02Z', errorCode, errorMessage: errorCode,
  counts: status === 'COMPLETED' ? { nodes: 2, hosts: 1, configProfiles: 1, deactivated: 0 } : null })
const overview: IntegrationOverview = { lastSync: session('COMPLETED'), lastSuccessfulSyncAt: '2026-09-29T10:00:02Z',
  nextRunAt: null, inventory: { nodes: { active: 2, inactive: 1 }, hosts: { active: 1, inactive: 0 },
    configProfiles: { active: 1, inactive: 0 } } }
const nodeSummary = (state: RemnawaveNodeSummary['state']): RemnawaveNodeSummary => ({
  address: '203.0.113.10', port: 2222, state, isConnected: state === 'CONNECTED', isConnecting: false,
  isDisabled: state === 'DISABLED', lastStatusChange: null, xrayVersion: '25.9.11', nodeVersion: null,
  xrayUptimeSeconds: null, trafficTrackingActive: false, trafficLimitBytes: null, trafficUsedBytes: 1536,
  usersOnline: 7, countryCode: 'DE', cpuCount: null, cpuModel: null, totalRam: null, activeConfigProfileUuid: null,
  tags: [], providerUuid: null, providerName: null })
const frankfurt: InventoryObject<RemnawaveNodeSummary> = { id: 'obj-a', objectType: 'NODE', externalId: 'node-a',
  displayName: 'Frankfurt', active: true, firstSeenAt: '', lastSeenAt: '2026-09-29T10:00:02Z',
  summary: nodeSummary('CONNECTED'), binding: null }
const idle: InventoryObject<RemnawaveNodeSummary> = { ...frankfurt, id: 'obj-b', externalId: 'node-b',
  displayName: 'Idle', active: false, summary: nodeSummary('DISABLED'),
  binding: { resource: { id: 'res-2', code: 'vps-2', name: 'vps-2' }, environment: { id: 'env', name: 'Production' },
    project: { id: 'p', name: 'Edge' } } }

type Call = { url: string; method: string; body?: Record<string, unknown> }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value),
  { status, headers: { 'Content-Type': 'application/json' } })

function setup(entry: string, role: 'OWNER' | 'MEMBER' = 'OWNER', options: { syncFails?: string } = {}) {
  const calls: Call[] = []
  let nodes = [frankfurt, idle]
  const client = createAppQueryClient()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    calls.push({ url, method, body })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role }])
    if (url.includes('/projects') || url.includes('/environments')) return json([])
    if (url === '/api/v1/organizations/org/integrations' && method === 'GET') return json([{ ...integration, overview }])
    if (url === base && method === 'GET') return json({ ...integration, overview })
    if (url === `${base}/inventory/summary`) return json(overview)
    if (url.startsWith(`${base}/inventory/nodes`)) {
      const params = new URL(url, 'http://x').searchParams
      const filtered = nodes.filter(node => (!params.get('state') || node.summary.state === params.get('state'))
        && (!params.get('search') || node.displayName.toLowerCase().includes(params.get('search')!.toLowerCase())))
      return json({ items: filtered, total: filtered.length, limit: 50, offset: 0 })
    }
    if (url.startsWith(`${base}/inventory/hosts`) || url.startsWith(`${base}/inventory/config-profiles`))
      return json({ items: [], total: 0, limit: 50, offset: 0 })
    if (url.startsWith(`${base}/sync-sessions`)) return json({ items: [session('FAILED', 'INTEGRATION_AUTH_FAILED'), session('COMPLETED')] })
    if (url === `${base}/sync` && method === 'POST')
      return json(options.syncFails ? session('FAILED', options.syncFails) : session('COMPLETED'))
    if (url.startsWith(`${base}/binding-candidates`)) return json({ items: [
      { id: 'res-1', code: 'vps-frankfurt', name: 'vps-frankfurt', environment: { id: 'env', name: 'Production' }, project: { id: 'p', name: 'Edge' } },
      { id: 'res-2', code: 'vps-2', name: 'vps-2', environment: { id: 'env', name: 'Production' }, project: { id: 'p', name: 'Edge' } }] })
    if (url === `${base}/inventory/objects/obj-a/binding` && method === 'PUT') {
      nodes = nodes.map(node => node.id === 'obj-a' ? { ...node, binding: { resource: { id: 'res-1', code: 'vps-frankfurt',
        name: 'vps-frankfurt' }, environment: { id: 'env', name: 'Production' }, project: { id: 'p', name: 'Edge' } } } : node)
      return json({ id: 'b1', inventoryObjectId: 'obj-a', resourceId: body!.resourceId, createdAt: '', updatedAt: '' })
    }
    if (url === `${base}/inventory/objects/obj-b/binding` && method === 'DELETE') {
      nodes = nodes.map(node => node.id === 'obj-b' ? { ...node, binding: null } : node)
      return new Response(null, { status: 204 })
    }
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/organizations/:organizationId/integrations" element={<IntegrationsPage />} />
      <Route path="/organizations/:organizationId/integrations/:integrationId" element={<IntegrationDetailPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { calls, client }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('integration detail', () => {
  it('members without ManageIntegrations request nothing about the integration', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'MEMBER')
    expect(await screen.findByText('Integration settings require owner access.')).toBeTruthy()
    expect(calls.some(call => call.url.includes('/integrations/one'))).toBe(false)
  })

  it('shows the overview, says automatic sync is off while disabled and runs Sync now', async () => {
    const { calls } = setup('/organizations/org/integrations/one')
    expect(await screen.findByRole('heading', { name: 'Main Remnawave' })).toBeTruthy()
    expect(screen.getByText('Automatic synchronization is disabled')).toBeTruthy()
    expect(await screen.findByText('2 present · 1 gone')).toBeTruthy()
    expect(screen.getByText('Not scheduled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('Synchronization completed')).toBeTruthy()
    expect(screen.getByText('2 nodes · 1 hosts · 1 profiles')).toBeTruthy()
    expect(calls.filter(call => call.url === `${base}/sync` && call.method === 'POST')).toHaveLength(1)
  })

  it('reports a failed synchronization by its code, never by remote text', async () => {
    setup('/organizations/org/integrations/one', 'OWNER', { syncFails: 'INTEGRATION_AUTH_FAILED' })
    fireEvent.click(await screen.findByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('Synchronization failed')).toBeTruthy()
    expect(screen.getByText('The API token was rejected.')).toBeTruthy()
  })

  it('lists nodes with Remnawave state and binding, filters by state, binds and unbinds by hand', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes')
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Frankfurt')).toBeTruthy()
    expect(within(table).getByText('Connected')).toBeTruthy()
    expect(within(table).getAllByText('203.0.113.10:2222')).toHaveLength(2)
    expect(within(table).getByText('Not bound')).toBeTruthy()
    expect(within(table).getByText('Gone')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('State'), { target: { value: 'DISABLED' } })
    await waitFor(() => expect(calls.some(call => call.url.includes('state=DISABLED'))).toBe(true))
    await waitFor(() => expect(screen.queryByText('Frankfurt')).toBeNull())
    fireEvent.change(screen.getByLabelText('State'), { target: { value: '' } })
    fireEvent.click(await screen.findByRole('button', { name: 'Bind' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(await within(dialog).findByLabelText(/vps-frankfurt/))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Bind' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find(call => call.method === 'PUT')?.body).toEqual({ resourceId: 'res-1' })
    expect(await screen.findByRole('link', { name: 'vps-frankfurt' })).toBeTruthy()
    fireEvent.click(screen.getAllByRole('button', { name: 'Unbind' })[1])
    await waitFor(() => expect(calls.some(call => call.method === 'DELETE')).toBe(true))
  })

  it('shows the last synchronizations with their results', async () => {
    setup('/organizations/org/integrations/one?tab=history')
    expect(await screen.findByText('The API token was rejected.')).toBeTruthy()
    expect(screen.getByText('2 nodes · 1 hosts · 1 profiles')).toBeTruthy()
    expect(screen.getAllByText('Manual')).toHaveLength(2)
  })

  it('the list links each integration to its detail and summarizes its last sync', async () => {
    setup('/organizations/org/integrations')
    expect(await screen.findByRole('link', { name: 'Main Remnawave' })).toBeTruthy()
    expect(screen.getByText(/2 nodes · last sync/)).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: 'Open' }))
    expect(await screen.findByText('Automatic synchronization is disabled')).toBeTruthy()
  })
})
