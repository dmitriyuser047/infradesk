// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { DesiredStateStatus, DesiredStateView, IntegrationOverview, IntegrationResponse, IntegrationSyncSession,
  InventoryObject, RemnawaveNodeSummary, IntegrationActionExecution } from '../types/integration'
import { IntegrationDetailPage } from './IntegrationDetailPage'
import { IntegrationsPage } from './IntegrationsPage'

const base = '/api/v1/organizations/org/integrations/one'
const integration: IntegrationResponse = { id: 'one', name: 'Main Remnawave', providerType: 'REMNAWAVE',
  baseUrl: 'https://panel.example.test', enabled: false,
  credential: { apiTokenConfigured: true, caddyApiKeyConfigured: false }, createdAt: '', updatedAt: '',
  managementMode: 'OBSERVE' }
const desired = (state: DesiredStateView['state'], status: DesiredStateStatus, version = 1): DesiredStateView =>
  ({ id: `desired-${state}-${status}`, state, version, status, lastActionExecutionId: null, updatedAt: '2026-09-29T10:00:00Z' })
const session = (status: IntegrationSyncSession['status'], errorCode: string | null = null): IntegrationSyncSession => ({
  id: `s-${status}`, integrationId: 'one', trigger: 'MANUAL', status, startedAt: '2026-09-29T10:00:00Z',
  finishedAt: '2026-09-29T10:00:02Z', errorCode, errorMessage: errorCode,
  counts: status === 'COMPLETED' ? { nodes: 2, hosts: 1, configProfiles: 1, deactivated: 0 } : null })
const overview: IntegrationOverview = { lastSync: session('COMPLETED'), lastSuccessfulSyncAt: '2026-09-29T10:00:02Z',
  nextRunAt: null, inventory: { nodes: { active: 2, inactive: 1 }, hosts: { active: 1, inactive: 0 },
    configProfiles: { active: 1, inactive: 0 } },
  desiredState: { managed: 0, compliant: 0, drifted: 0, applying: 0, needsAttention: 0 } }
const nodeSummary = (state: RemnawaveNodeSummary['state']): RemnawaveNodeSummary => ({
  address: '203.0.113.10', port: 2222, state, isConnected: state === 'CONNECTED', isConnecting: false,
  isDisabled: state === 'DISABLED', lastStatusChange: null, xrayVersion: '25.9.11', nodeVersion: null,
  xrayUptimeSeconds: 0, trafficTrackingActive: false, trafficLimitBytes: null, trafficUsedBytes: 1536,
  usersOnline: 7, countryCode: 'DE', cpuCount: null, cpuModel: null, memoryTotalBytes: null, activeConfigProfileUuid: null,
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

function setup(entry: string, role: 'OWNER' | 'MEMBER' = 'OWNER', options: { syncFails?: string; activeDisabled?: boolean; loseFirstActionResponse?: boolean
  hideActionHistory?: boolean; enabled?: boolean; managed?: boolean; nodes?: InventoryObject<RemnawaveNodeSummary>[]
  executions?: IntegrationActionExecution[] } = {}) {
  const calls: Call[] = []
  let nodes = options.nodes ?? [frankfurt, options.activeDisabled ? { ...idle, active: true } : idle]
  let executions: IntegrationActionExecution[] = options.executions ?? []
  // The integration as the backend would hold it: enabled and mode change only through the API.
  let current: IntegrationResponse = { ...integration, enabled: options.enabled ?? false,
    managementMode: options.managed ? 'MANAGED_SELECTED' : 'OBSERVE' }
  const counts = () => {
    const managed = nodes.filter(node => node.desiredState)
    const of = (...statuses: DesiredStateStatus[]) => managed.filter(node => statuses.includes(node.desiredState!.status)).length
    return { managed: managed.length, compliant: of('COMPLIANT'), drifted: of('DRIFTED'),
      applying: of('APPLYING', 'WAITING_REFRESH'), needsAttention: of('REMEDIATION_FAILED', 'UNAVAILABLE') }
  }
  const currentOverview = () => ({ ...overview, desiredState: counts() })
  let lostResponses = options.loseFirstActionResponse ? 1 : 0
  const client = createAppQueryClient()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    calls.push({ url, method, body })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role }])
    if (url.includes('/projects') || url.includes('/environments')) return json([])
    if (url === '/api/v1/organizations/org/integrations' && method === 'GET') return json([{ ...current, overview: currentOverview() }])
    if (url === base && method === 'GET') return json({ ...current, overview: currentOverview() })
    if (url === `${base}/inventory/summary`) return json(currentOverview())
    if (url === `${base}/management-mode` && method === 'PUT') {
      const mode = body!.mode as IntegrationResponse['managementMode']
      if (mode === 'MANAGED_SELECTED' && !current.enabled)
        return json({ code: 'INTEGRATION_MANAGEMENT_REQUIRES_SYNC', message: 'x' }, 409)
      // Back to OBSERVE removes every intent and changes nothing remotely.
      if (mode === 'OBSERVE') nodes = nodes.map(node => ({ ...node, desiredState: null }))
      current = { ...current, managementMode: mode }
      return json(current)
    }
    if (url.endsWith('/desired-state') && method === 'PUT') {
      const objectId = url.split('/').at(-2)!
      const node = nodes.find(value => value.id === objectId)!
      const state = body!.state as DesiredStateView['state']
      const view: DesiredStateView = { id: `desired-${objectId}`, state, version: (node.desiredState?.version ?? 0) + 1,
        status: (state === 'DISABLED') === node.summary.isDisabled ? 'COMPLIANT' : 'DRIFTED',
        lastActionExecutionId: null, updatedAt: '2026-09-29T10:00:05Z' }
      nodes = nodes.map(value => value.id === objectId ? { ...value, desiredState: view } : value)
      return json(view)
    }
    if (url.endsWith('/desired-state') && method === 'DELETE') {
      const objectId = url.split('/').at(-2)!
      nodes = nodes.map(value => value.id === objectId ? { ...value, desiredState: null } : value)
      return new Response(null, { status: 204 })
    }
    if (url === `${base}/actions?limit=50`) return json({ items: options.hideActionHistory ? [] : executions })
    if (url.startsWith(`${base}/actions/`)) return json(executions.find(value => url.endsWith(value.id)))
    if (url.endsWith('/actions') && method === 'POST') {
      const objectId = url.split('/').at(-2)!
      const action = body!.action as IntegrationActionExecution['action']
      // Idempotent by request ID, as the backend is.
      const existing = executions.find(value => value.requestId === body!.requestId)
      if (existing) return json(existing, 200)
      const value: IntegrationActionExecution = { id: `action-${executions.length}`, requestId: body!.requestId as string,
        integrationId: 'one', inventoryObjectId: objectId, displayName: objectId === 'obj-a' ? 'Frankfurt' : 'Idle',
        requestedByUserId: 'user', requestedByName: 'Operator', action, status: 'QUEUED',
        createdAt: '2026-09-29T10:00:03Z', startedAt: null, finishedAt: null, errorCode: null,
        source: 'MANUAL', desiredStateId: null, desiredStateVersion: null }
      executions = [value, ...executions]
      // The intent is recorded, but the answer never reaches the browser.
      if (lostResponses > 0) { lostResponses -= 1; throw new TypeError('Failed to fetch') }
      return json(value, 202)
    }
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

  it('confirms one node action with one request ID and shows action history', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { activeDisabled: true })
    expect(await screen.findByRole('button', { name: 'Enable' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Disable' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Restart' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Restart' }))
    expect(calls.filter(call => call.url.endsWith('/actions') && call.method === 'POST')).toHaveLength(0)
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Connections may be interrupted during restart.')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(calls.filter(call => call.url.endsWith('/actions') && call.method === 'POST')).toHaveLength(1))
    const posted = calls.find(call => call.url.endsWith('/actions') && call.method === 'POST')!
    expect(posted.body?.action).toBe('NODE_RESTART')
    expect(posted.body?.requestId).toMatch(/^[a-f0-9-]{36}$/)
    fireEvent.click(screen.getByRole('tab', { name: 'Actions' }))
    expect(await screen.findByText('Operator')).toBeTruthy()
    expect(await screen.findByText('NODE_RESTART')).toBeTruthy()
  })

  it('after a lost response the same request ID must be checked; it cannot be cancelled and forgotten', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER',
      { activeDisabled: true, loseFirstActionResponse: true })
    fireEvent.click(await screen.findByRole('button', { name: 'Restart' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    // Until the request is resolved it can only be checked with its own ID, never cancelled and forgotten.
    const dialog = screen.queryByRole('dialog')
    if (dialog && within(dialog).queryByText(/The response was lost/)) {
      expect((within(dialog).getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true)
      fireEvent.click(within(dialog).getByRole('button', { name: 'Check request' }))
    }
    // Resolved either by the retry or by finding the request ID in the action history.
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const posts = calls.filter(call => call.url.endsWith('/actions') && call.method === 'POST')
    expect(new Set(posts.map(call => call.body?.requestId)).size).toBe(1)
    expect(await screen.findByText('Queued')).toBeTruthy()
    expect(Object.keys(window.sessionStorage).some(key => key.startsWith('integration-action-request:'))).toBe(false)
  })

  it('while the lost request is not visible yet, Cancel is locked and the retry reuses its request ID', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER',
      { activeDisabled: true, loseFirstActionResponse: true, hideActionHistory: true })
    fireEvent.click(await screen.findByRole('button', { name: 'Restart' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    const dialog = screen.getByRole('dialog')
    expect(await within(dialog).findByText(/The response was lost/)).toBeTruthy()
    expect((within(dialog).getByRole('button', { name: 'Cancel' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Check request' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    const posts = calls.filter(call => call.url.endsWith('/actions') && call.method === 'POST')
    expect(posts).toHaveLength(2)
    expect(posts[1].body?.requestId).toBe(posts[0].body?.requestId)
  })

  it('the list links each integration to its detail and summarizes its last sync', async () => {
    setup('/organizations/org/integrations')
    expect(await screen.findByRole('link', { name: 'Main Remnawave' })).toBeTruthy()
    expect(screen.getByText(/2 nodes · last sync/)).toBeTruthy()
    fireEvent.click(screen.getByRole('link', { name: 'Open' }))
    expect(await screen.findByText('Automatic synchronization is disabled')).toBeTruthy()
  })
})

describe('desired state of selected nodes', () => {
  const nodesUrl = '/organizations/org/integrations/one?tab=nodes'
  const managedNode = (id: string, name: string, state: RemnawaveNodeSummary['state'], view: DesiredStateView | null,
    active = true): InventoryObject<RemnawaveNodeSummary> => ({ ...frankfurt, id, externalId: id, displayName: name, active,
    summary: nodeSummary(state), binding: null, desiredState: view })
  const modeCalls = (calls: Call[]) => calls.filter(call => call.url.endsWith('/management-mode'))
  const desiredCalls = (calls: Call[]) => calls.filter(call => call.url.endsWith('/desired-state'))

  it('observes by default: nothing is managed and no node offers management', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true })
    expect(await screen.findByText('Observe')).toBeTruthy()
    expect(screen.getByText(/does not automatically change node state/)).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: 'Nodes' }))
    const table = await screen.findByRole('table')
    expect(within(table).getAllByText('Not managed')).toHaveLength(2)
    expect(within(table).queryByRole('button', { name: 'Manage' })).toBeNull()
    expect(modeCalls(calls)).toHaveLength(0)
  })

  it('cannot start managing while automatic synchronization is off', async () => {
    setup('/organizations/org/integrations/one')
    const start = await screen.findByRole('button', { name: 'Manage selected nodes' }) as HTMLButtonElement
    expect(start.disabled).toBe(true)
    expect(screen.getByText(/requires automatic synchronization/)).toBeTruthy()
  })

  it('turns automation on only after an explicit confirmation', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true })
    fireEvent.click(await screen.findByRole('button', { name: 'Manage selected nodes' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/This turns automation on/)).toBeTruthy()
    expect(modeCalls(calls)).toHaveLength(0)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(modeCalls(calls).map(call => call.body)).toEqual([{ mode: 'MANAGED_SELECTED' }])
    expect(await screen.findByRole('button', { name: 'Stop managing nodes' })).toBeTruthy()
    expect(screen.getByText(/keeps the state of explicitly managed nodes/)).toBeTruthy()
  })

  it('manages a node by choice, with the automation spelled out, then changes and stops it', async () => {
    const { calls } = setup(nodesUrl, 'OWNER', { enabled: true, managed: true, activeDisabled: true })
    const table = await screen.findByRole('table')
    fireEvent.click(within(table).getAllByRole('button', { name: 'Manage' })[0])
    let dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/persistent automation, not a one-time command/)).toBeTruthy()
    expect(desiredCalls(calls)).toHaveLength(0)
    fireEvent.click(within(dialog).getByLabelText('Disabled'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Manage node' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(desiredCalls(calls).map(call => [call.method, call.url.split('/').at(-2), call.body]))
      .toEqual([['PUT', 'obj-a', { state: 'DISABLED' }]])
    // Frankfurt is observed enabled and wanted disabled: the drift is named, not just coloured.
    expect(await screen.findByText('Drift detected')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Change desired state' }))
    dialog = screen.getByRole('dialog')
    // The same state again is not a change.
    expect((within(dialog).getByRole('button', { name: 'Change desired state' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(dialog).getByLabelText('Enabled'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Change desired state' }))
    expect(await screen.findByText('Compliant')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Change desired state' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Stop managing' }))
    await waitFor(() => expect(desiredCalls(calls).some(call => call.method === 'DELETE')).toBe(true))
    await waitFor(() => expect(screen.getAllByText('Not managed')).toHaveLength(2))
  })

  it('names every status in words and hides manual actions that would fight the intent', async () => {
    setup(nodesUrl, 'OWNER', { enabled: true, managed: true, nodes: [
      managedNode('n1', 'Alpha', 'CONNECTED', desired('ENABLED', 'COMPLIANT')),
      managedNode('n2', 'Bravo', 'DISABLED', desired('ENABLED', 'DRIFTED')),
      managedNode('n3', 'Charlie', 'DISABLED', desired('ENABLED', 'APPLYING')),
      managedNode('n4', 'Delta', 'CONNECTED', desired('DISABLED', 'WAITING_REFRESH')),
      managedNode('n5', 'Echo', 'CONNECTED', desired('DISABLED', 'REMEDIATION_FAILED')),
      managedNode('n6', 'Foxtrot', 'DISABLED', desired('DISABLED', 'UNAVAILABLE'), false),
      managedNode('n7', 'Golf', 'CONNECTED', null)] })
    const table = await screen.findByRole('table')
    for (const label of ['Compliant', 'Drift detected', 'Applying', 'Waiting for observation', 'Remediation failed',
      'Node unavailable', 'Not managed']) expect(within(table).getByText(label)).toBeTruthy()
    const row = (name: string) => within(table).getByText(name).closest('tr')!
    // Wanted enabled and observed enabled: Restart stays, Disable is not offered.
    expect(within(row('Alpha')).getByRole('button', { name: 'Restart' })).toBeTruthy()
    expect(within(row('Alpha')).queryByRole('button', { name: 'Disable' })).toBeNull()
    // Wanted enabled, observed disabled: Enable goes the same way as the intent.
    expect(within(row('Bravo')).getByRole('button', { name: 'Enable' })).toBeTruthy()
    // Wanted disabled, observed enabled: neither Enable nor a contradiction, only Disable and Restart.
    expect(within(row('Echo')).getByRole('button', { name: 'Disable' })).toBeTruthy()
    expect(within(row('Echo')).queryByRole('button', { name: 'Enable' })).toBeNull()
    // An unmanaged node behaves exactly as before.
    expect(within(row('Golf')).getByRole('button', { name: 'Disable' })).toBeTruthy()
    expect(within(row('Golf')).getByRole('button', { name: 'Manage' })).toBeTruthy()
    // A node Remnawave no longer reports can only be released.
    expect(within(row('Foxtrot')).getByRole('button', { name: 'Change desired state' })).toBeTruthy()
  })

  it('shows managed counters and removes intents, not remote state, when returning to Observe', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true, managed: true, nodes: [
      managedNode('n1', 'Alpha', 'CONNECTED', desired('ENABLED', 'COMPLIANT')),
      managedNode('n2', 'Bravo', 'DISABLED', desired('ENABLED', 'DRIFTED'))] })
    const counters = (await screen.findByText('Managed nodes')).closest('dl')!
    expect(within(counters).getByText('Managed nodes').nextElementSibling?.textContent).toBe('2')
    expect(within(counters).getByText('Drifted').nextElementSibling?.textContent).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: 'Stop managing nodes' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Desired states for 2 nodes will be removed. Their current state in Remnawave will not be changed.')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(modeCalls(calls).map(call => call.body)).toEqual([{ mode: 'OBSERVE' }])
    expect(await screen.findByText('Observe')).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/actions') && call.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('tab', { name: 'Nodes' }))
    await waitFor(() => expect(screen.getAllByText('Not managed')).toHaveLength(2))
  })

  it('action history says whether a person or a desired state caused an action', async () => {
    const execution = (id: string, source: IntegrationActionExecution['source'], version: number | null): IntegrationActionExecution => ({
      id, requestId: id, integrationId: 'one', inventoryObjectId: 'obj-a', displayName: 'Frankfurt',
      requestedByUserId: 'user', requestedByName: 'Dmitriy', action: 'NODE_DISABLE', status: 'SUCCEEDED',
      createdAt: '2026-09-29T10:00:03Z', startedAt: '2026-09-29T10:00:04Z', finishedAt: '2026-09-29T10:00:05Z',
      errorCode: null, source, desiredStateId: version === null ? null : 'desired-1', desiredStateVersion: version })
    setup('/organizations/org/integrations/one?tab=actions', 'OWNER', { enabled: true, managed: true,
      executions: [execution('auto', 'DESIRED_STATE', 3), execution('manual', 'MANUAL', null)] })
    expect(await screen.findByText('Desired state v3')).toBeTruthy()
    expect(screen.getByText('Manual')).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: 'Source' })).toBeTruthy()
  })

  it('a refused mode change keeps the dialog open and explains why', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true })
    // The integration is disabled by someone else after the page was loaded.
    fireEvent.click(await screen.findByRole('button', { name: 'Manage selected nodes' }))
    const original = vi.mocked(fetch).getMockImplementation()!
    vi.mocked(fetch).mockImplementation(async (input, init) => String(input).endsWith('/management-mode')
      ? new Response(JSON.stringify({ code: 'INTEGRATION_MANAGEMENT_REQUIRES_SYNC', message: 'x' }),
        { status: 409, headers: { 'Content-Type': 'application/json' } })
      : original(input, init))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    expect(await screen.findByText('Managing nodes requires automatic synchronization.')).toBeTruthy()
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(modeCalls(calls)).toHaveLength(0)
  })

  it('a member sees no management controls and sends nothing', async () => {
    const { calls } = setup(nodesUrl, 'MEMBER', { enabled: true, managed: true })
    expect(await screen.findByText('Integration settings require owner access.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Manage' })).toBeNull()
    expect(calls.some(call => call.url.includes('/integrations/one'))).toBe(false)
  })
})
