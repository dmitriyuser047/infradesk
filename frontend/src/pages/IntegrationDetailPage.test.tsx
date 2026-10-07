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
  hideActionHistory?: boolean; enabled?: boolean; managed?: boolean; unresolvedUnknown?: boolean; nodes?: InventoryObject<RemnawaveNodeSummary>[]
  executions?: IntegrationActionExecution[]; locale?: 'ru' | 'en'; testError?: string; testOk?: boolean; readError?: number; neverSynced?: boolean; sessions?: IntegrationSyncSession[] } = {}) {
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
  const currentOverview = () => ({ ...overview, ...(options.neverSynced ? { lastSync: null, lastSuccessfulSyncAt: null } : {}), desiredState: counts() })
  let lostResponses = options.loseFirstActionResponse ? 1 : 0
  const client = createAppQueryClient()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    calls.push({ url, method, body })
    if (url === base && method === 'DELETE') return options.unresolvedUnknown
      ? json({ code: 'INTEGRATION_RECOVERY_REQUIRED', message: 'SECRET-REMOTE-BODY' }, 409)
      : new Response(null, { status: 204 })
    if (url === `${base}/abandon-recovery-and-delete` && method === 'POST') return new Response(null, { status: 204 })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role }])
    if (url.includes('/projects') || url.includes('/environments')) return json([])
    if (url === '/api/v1/organizations/org/integrations' && method === 'GET') return json([{ ...current, overview: currentOverview() }])
    if (url === base && method === 'GET') return options.readError ? json({ code: 'PRIVATE_UNKNOWN', message: 'SECRET-REMOTE-BODY' }, options.readError) : json({ ...current, overview: currentOverview() })
    if (url === `${base}/test`) return options.testError ? json({ code: options.testError, message: 'SECRET-REMOTE-BODY' }, 502) : json({ ok: options.testOk ?? true, providerType: 'REMNAWAVE', latencyMs: 7 })
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
    if (url.startsWith(`${base}/sync-sessions`)) return json({ items: options.sessions ?? [session('FAILED', 'INTEGRATION_AUTH_FAILED'), session('COMPLETED')] })
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
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/organizations/:organizationId/integrations" element={<IntegrationsPage />} />
      <Route path="/organizations/:organizationId/integrations/:integrationId" element={<IntegrationDetailPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { calls, client }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('integration detail', () => {
  it.each(['en', 'ru'] as const)('requires a second explicit abandonment confirmation in %s', async locale => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { locale, unresolvedUnknown: true })
    await screen.findByRole('heading', { name: 'Main Remnawave' })
    fireEvent.click(screen.getByRole('button', { name: locale === 'en' ? 'Actions' : 'Действия' }))
    fireEvent.click(screen.getByRole('menuitem', { name: locale === 'en' ? 'Delete' : 'Удалить' }))
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: locale === 'en' ? 'Confirm delete' : 'Подтвердить удаление' }))
    const label = locale === 'en' ? 'Delete integration and abandon recovery' : 'Удалить интеграцию и отказаться от восстановления'
    fireEvent.click(await screen.findByRole('button', { name: label }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(locale === 'en' ? /will no longer be able to reconcile/ : /больше не сможет проверить/)).toBeTruthy()
    expect(calls.filter(call => call.url.endsWith('/abandon-recovery-and-delete'))).toHaveLength(0)
    expect(screen.queryByText('SECRET-REMOTE-BODY')).toBeNull()
    fireEvent.click(within(dialog).getByRole('button', { name: label }))
    await waitFor(() => expect(calls.filter(call => call.url.endsWith('/abandon-recovery-and-delete') && call.method === 'POST')).toHaveLength(1))
    expect(calls.filter(call => call.url === base && call.method === 'DELETE')).toHaveLength(1)
  })

  it('uses the operational labels in the Russian overview without changing counts', async () => {
    setup('/organizations/org/integrations/one', 'OWNER', { locale: 'ru', enabled: true, managed: true,
      nodes: [{ ...frankfurt, desiredState: desired('ENABLED', 'COMPLIANT') },
        { ...idle, desiredState: desired('DISABLED', 'DRIFTED') }] })
    expect(await screen.findByText('Автоматическое управление включено')).toBeTruthy()
    for (const label of ['Под управлением', 'В нужном состоянии', 'Требуют изменения', 'Изменяются сейчас', 'Проблемы',
      'Следующая синхронизация', 'Профили конфигурации']) expect(screen.getAllByText(label).length).toBeGreaterThan(0)
    expect(screen.getByText('2 · 1 больше не найдено')).toBeTruthy()
    expect(screen.queryByText(/есть$/)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Синхронизировать' }))
    expect(await screen.findByText('Ноды: 2 · Хосты: 1 · Профили конфигурации: 1')).toBeTruthy()
  })

  it('members without ManageIntegrations request nothing about the integration', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'MEMBER')
    expect(await screen.findByText('Integration settings require owner access.')).toBeTruthy()
    expect(calls.some(call => call.url.includes('/integrations/one'))).toBe(false)
  })

  it('shows the overview, says automatic sync is off while disabled and runs Sync now', async () => {
    const { calls } = setup('/organizations/org/integrations/one')
    expect(await screen.findByRole('heading', { name: 'Main Remnawave' })).toBeTruthy()
    expect(screen.getByText('Automatic synchronization is disabled')).toBeTruthy()
    expect(await screen.findByText('2 · 1 no longer found')).toBeTruthy()
    expect(screen.getByText('Not scheduled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Sync now' }))
    expect(await screen.findByText('Synchronization succeeded')).toBeTruthy()
    expect(screen.getByText('Nodes: 2 · Hosts: 1 · Configuration profiles: 1')).toBeTruthy()
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
    expect(within(table).getByText('No server linked')).toBeTruthy()
    expect(within(table).getByText('No longer found')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('State'), { target: { value: 'DISABLED' } })
    await waitFor(() => expect(calls.some(call => call.url.includes('state=DISABLED'))).toBe(true))
    await waitFor(() => expect(screen.queryByText('Frankfurt')).toBeNull())
    fireEvent.change(screen.getByLabelText('State'), { target: { value: '' } })
    await screen.findByText('Frankfurt')
    fireEvent.click(within(screen.getByText('Frankfurt').closest('tr')!).getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Link to server' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Link node to server: Frankfurt')).toBeTruthy()
    fireEvent.click(await within(dialog).findByLabelText(/vps-frankfurt/))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Link to server' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(calls.find(call => call.method === 'PUT')?.body).toEqual({ resourceId: 'res-1' })
    expect(await screen.findByRole('link', { name: 'vps-frankfurt' })).toBeTruthy()
    fireEvent.click(within(screen.getByText('Idle').closest('tr')!).getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Remove link' }))
    expect(calls.some(call => call.method === 'DELETE')).toBe(false)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove link' }))
    await waitFor(() => expect(calls.some(call => call.method === 'DELETE')).toBe(true))
  })

  it('shows specific empty states and explains how to discover nodes', async () => {
    setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { nodes: [] })
    expect(await screen.findByText('No nodes found')).toBeTruthy()
    expect(screen.getByText('No objects were found after the last successful synchronization.')).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: /^Hosts/ }))
    expect(await screen.findByText('No hosts found')).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: /^Configuration profiles/ }))
    expect(await screen.findByText('No configuration profiles found')).toBeTruthy()
    fireEvent.change(screen.getByRole('searchbox', { name: 'Search' }), { target: { value: 'missing' } })
    expect(await screen.findByText('Nothing matches the selected filters')).toBeTruthy()
  })

  it('shows the last synchronizations with their results', async () => {
    setup('/organizations/org/integrations/one?tab=history')
    expect(await screen.findByText('The API token was rejected.')).toBeTruthy()
    expect(screen.getByText('Nodes: 2 · Hosts: 1 · Configuration profiles: 1')).toBeTruthy()
    expect(screen.getAllByText('Manual')).toHaveLength(2)
  })

  it('confirms one node action with one request ID and shows action history', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { activeDisabled: true })
    await screen.findByText('Frankfurt')
    fireEvent.click(within(screen.getByText('Idle').closest('tr')!).getByRole('button', { name: 'Actions' }))
    expect(screen.getByRole('menuitem', { name: 'Enable' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Actions', expanded: true }))
    fireEvent.click(within(screen.getByText('Frankfurt').closest('tr')!).getByRole('button', { name: 'Actions' }))
    expect(screen.getByRole('menuitem', { name: 'Disable' })).toBeTruthy()
    fireEvent.click(screen.getByRole('menuitem', { name: 'Restart' }))
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
    expect(await screen.findByText('Restart node')).toBeTruthy()
    expect(screen.getByRole('cell', { name: 'Queued' })).toBeTruthy()
  })

  it('after a lost response the same request ID must be checked; it cannot be cancelled and forgotten', async () => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER',
      { activeDisabled: true, loseFirstActionResponse: true })
    await screen.findByText('Idle')
    fireEvent.click(within(screen.getByText('Frankfurt').closest('tr')!).getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Restart' }))
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
    await screen.findByText('Idle')
    fireEvent.click(within(screen.getByText('Frankfurt').closest('tr')!).getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Restart' }))
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
    expect(screen.getByText('2 nodes · 1 host · 1 configuration profile')).toBeTruthy()
    expect(screen.getByText('Successful')).toBeTruthy()
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

  it('shows intended and observed state plus distinct binding controls in Russian', async () => {
    setup(nodesUrl, 'OWNER', { locale: 'ru', enabled: true, managed: true, nodes: [
      managedNode('n1', 'Frankfurt', 'CONNECTED', desired('DISABLED', 'DRIFTED')),
      { ...idle, active: true, desiredState: desired('DISABLED', 'COMPLIANT') },
    ] })
    const table = await screen.findByRole('table')
    expect(screen.getByLabelText('Статус обнаружения')).toBeTruthy()
    expect(within(table).getByRole('columnheader', { name: 'Управление' })).toBeTruthy()
    expect(within(table).getByRole('columnheader', { name: 'Связанный сервер InfraDesk' })).toBeTruthy()
    const frankfurtRow = within(table).getByText('Frankfurt').closest('tr')!
    expect(within(frankfurtRow).getByText('Должна быть выключена')).toBeTruthy()
    expect(within(frankfurtRow).getByText('Сейчас включена')).toBeTruthy()
    expect(within(frankfurtRow).getByText('Обнаружено расхождение')).toBeTruthy()
    expect(within(frankfurtRow).getByText('Сервер не связан')).toBeTruthy()
    expect(within(frankfurtRow).getByRole('button', { name: 'Изменить правило' })).toBeTruthy()
    fireEvent.click(within(frankfurtRow).getByRole('button', { name: 'Действия' }))
    expect(screen.getByRole('menuitem', { name: 'Связать с сервером' })).toBeTruthy()
    const linkedRow = within(table).getByText('Idle').closest('tr')!
    expect(within(linkedRow).getByText('Сейчас выключена')).toBeTruthy()
    fireEvent.click(within(linkedRow).getByRole('button', { name: 'Действия' }))
    expect(screen.getByRole('menuitem', { name: 'Изменить привязку' })).toBeTruthy()
    expect(screen.getByRole('menuitem', { name: 'Удалить привязку' })).toBeTruthy()
    fireEvent.click(within(frankfurtRow).getByRole('button', { name: 'Действия' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Связать с сервером' }))
    expect(within(await screen.findByRole('dialog')).getByText('Связать ноду с сервером: Frankfurt')).toBeTruthy()
  })

  it('observes by default: nothing is managed and no node offers management', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true })
    expect(await screen.findByText('Observe')).toBeTruthy()
    expect(screen.getByText(/does not automatically change node state/)).toBeTruthy()
    fireEvent.click(screen.getByRole('tab', { name: /^Nodes/ }))
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
    expect(screen.getByText(/keeps selected nodes in the specified state/)).toBeTruthy()
  })

  it('manages a node by choice, with the automation spelled out, then changes and stops it', async () => {
    const { calls } = setup(nodesUrl, 'OWNER', { enabled: true, managed: true, activeDisabled: true })
    const table = await screen.findByRole('table')
    fireEvent.click(within(table).getAllByRole('button', { name: 'Manage' })[0])
    let dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText(/persistent automation, not a one-time command/)).toBeTruthy()
    expect(desiredCalls(calls)).toHaveLength(0)
    fireEvent.click(within(dialog).getByLabelText('Should be disabled'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Manage node' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(desiredCalls(calls).map(call => [call.method, call.url.split('/').at(-2), call.body]))
      .toEqual([['PUT', 'obj-a', { state: 'DISABLED' }]])
    // Frankfurt is observed enabled and wanted disabled: the drift is named, not just coloured.
    expect(await screen.findByText('Drift detected')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Change rule' }))
    dialog = screen.getByRole('dialog')
    // The same state again is not a change.
    expect((within(dialog).getByRole('button', { name: 'Save rule' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(within(dialog).getByLabelText('Should be enabled'))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(await screen.findByText('Currently enabled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Change rule' }))
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
      managedNode('n7', 'Golf', 'CONNECTED', null),
      managedNode('n8', 'Hotel', 'DISABLED', desired('DISABLED', 'COMPLIANT'))] })
    const table = await screen.findByRole('table')
    for (const label of ['Currently enabled', 'Drift detected', 'Applying', 'Waiting for observation', 'Remediation failed',
      'Node unavailable', 'Not managed']) expect(within(table).getByText(label)).toBeTruthy()
    const row = (name: string) => within(table).getByText(name).closest('tr')!
    // Wanted enabled and observed enabled: Restart stays, Disable is not offered.
    fireEvent.click(within(row('Alpha')).getByRole('button', { name: 'Actions' }))
    expect(within(row('Alpha')).getByRole('menuitem', { name: 'Restart' })).toBeTruthy()
    expect(within(row('Hotel')).getByText('Currently disabled')).toBeTruthy()
    expect(within(row('Alpha')).queryByRole('menuitem', { name: 'Disable' })).toBeNull()
    // Wanted enabled, observed disabled: Enable goes the same way as the intent.
    fireEvent.click(within(row('Bravo')).getByRole('button', { name: 'Actions' }))
    expect(within(row('Bravo')).getByRole('menuitem', { name: 'Enable' })).toBeTruthy()
    // Wanted disabled, observed enabled: neither Enable nor a contradiction, only Disable and Restart.
    fireEvent.click(within(row('Echo')).getByRole('button', { name: 'Actions' }))
    expect(within(row('Echo')).getByRole('menuitem', { name: 'Disable' })).toBeTruthy()
    expect(within(row('Echo')).queryByRole('menuitem', { name: 'Enable' })).toBeNull()
    // An unmanaged node behaves exactly as before.
    fireEvent.click(within(row('Golf')).getByRole('button', { name: 'Actions' }))
    expect(within(row('Golf')).getByRole('menuitem', { name: 'Disable' })).toBeTruthy()
    expect(within(row('Golf')).getByRole('button', { name: 'Manage' })).toBeTruthy()
    // A node Remnawave no longer reports can only be released.
    expect(within(row('Foxtrot')).getByRole('button', { name: 'Change rule' })).toBeTruthy()
  })

  it('shows managed counters and removes intents, not remote state, when returning to Observe', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true, managed: true, nodes: [
      managedNode('n1', 'Alpha', 'CONNECTED', desired('ENABLED', 'COMPLIANT')),
      managedNode('n2', 'Bravo', 'DISABLED', desired('ENABLED', 'DRIFTED'))] })
    const counters = (await screen.findByText('Under management')).closest('dl')!
    expect(within(counters).getByText('Under management').nextElementSibling?.textContent).toBe('2')
    expect(within(counters).getByText('Need changes').nextElementSibling?.textContent).toBe('1')
    fireEvent.click(screen.getByRole('button', { name: 'Stop managing nodes' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('Management rules for 2 nodes will be removed. Their current state in Remnawave will not be changed.')).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(modeCalls(calls).map(call => call.body)).toEqual([{ mode: 'OBSERVE' }])
    expect(await screen.findByText('Observe')).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/actions') && call.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('tab', { name: /^Nodes/ }))
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
    expect(await screen.findByText('Automatic management v3')).toBeTruthy()
    expect(screen.getByText('Manual')).toBeTruthy()
    expect(screen.getByRole('columnheader', { name: 'Source' })).toBeTruthy()
    expect(screen.getAllByRole('cell', { name: 'Stop node' })).toHaveLength(2)
    expect(screen.getAllByRole('cell', { name: 'Successful' })).toHaveLength(2)
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

describe('UI-5 operational states', () => {
  it('keeps enabled, sync success and untested connectivity separate until an explicit test', async () => {
    const { calls } = setup('/organizations/org/integrations/one', 'OWNER', { enabled: true })
    expect(await screen.findByText('Not checked')).toBeTruthy()
    expect(screen.getByText('Enabled')).toBeTruthy()
    expect(screen.getByText('Successful')).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/test'))).toBe(false)
    expect(screen.queryByText('Last checked')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText('Available')).toBeTruthy()
    expect(screen.getByText(/Last checked:/)).toBeTruthy()
    expect(screen.getByText('Remnawave responds and is available to InfraDesk.')).toBeTruthy()
  })

  it('shows a failed connectivity test independently of a successful sync, without remote response text', async () => {
    setup('/organizations/org/integrations/one', 'OWNER', { enabled: true, testError: 'INTEGRATION_AUTH_FAILED' })
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText('Unavailable')).toBeTruthy()
    expect(screen.getByText('Successful')).toBeTruthy()
    expect(screen.getByText('The API token was rejected.')).toBeTruthy()
    expect(screen.queryByText('SECRET-REMOTE-BODY')).toBeNull()
  })

  it('does not turn ok=false into successful connectivity', async () => {
    setup('/organizations/org/integrations/one', 'OWNER', { testOk: false })
    fireEvent.click(await screen.findByRole('button', { name: 'Test connection' }))
    expect(await screen.findByText('Unavailable')).toBeTruthy()
    expect(screen.getByText('Unable to connect to Remnawave')).toBeTruthy()
    expect(screen.queryByText('Connection successful')).toBeNull()
  })

  it('keeps the last successful data on a failed background refresh and offers retry', async () => {
    const options: { readError?: number } = {}
    const { client } = setup('/organizations/org/integrations/one', 'OWNER', options)
    await screen.findByText('Not checked')
    options.readError = 502
    await client.invalidateQueries({ queryKey: ['integration', 'org', 'one'] })
    expect(await screen.findByText('Unable to refresh data')).toBeTruthy()
    expect(screen.getByRole('heading', { name: 'Main Remnawave' })).toBeTruthy()
    expect(screen.queryByText('SECRET-REMOTE-BODY')).toBeNull()
    options.readError = undefined
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(screen.queryByText('Unable to refresh data')).toBeNull())
  })

  it.each([401, 403, 404])('hides a cached integration after access or availability failure %s', async readError => {
    const options: { readError?: number } = {}
    const { client } = setup('/organizations/org/integrations/one', 'OWNER', options)
    await screen.findByText('Not checked')
    options.readError = readError
    await client.invalidateQueries({ queryKey: ['integration', 'org', 'one'] })
    await waitFor(() => expect(screen.queryByRole('heading', { name: 'Main Remnawave' })).toBeNull())
    expect(screen.queryByRole('button', { name: 'Sync now' })).toBeNull()
  })

  it('distinguishes the first synchronization from a successfully synchronized empty inventory', async () => {
    setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { nodes: [], neverSynced: true })
    expect(await screen.findByText('Run synchronization to get nodes from Remnawave.')).toBeTruthy()
    expect(screen.queryByText('No objects were found after the last successful synchronization.')).toBeNull()
  })

  it('preserves project and environment when following a linked server and displays stale discovery neutrally', async () => {
    setup('/organizations/org/integrations/one?tab=nodes&project=p&environment=env')
    const link = await screen.findByRole('link', { name: 'vps-2' })
    expect(link.getAttribute('href')).toBe('/organizations/org/environments/env/resources/res-2?project=p&environment=env')
    const row = screen.getByText('Idle').closest('tr')!
    expect(within(row).getByText('No longer found').className).toContain('status-neutral')
    expect(within(row).getByText(/Last seen:/)).toBeTruthy()
  })
})

describe('UI-5 existing node operations and history', () => {
  it.each([
    ['Enable', 'Idle', 'NODE_ENABLE', 'Disabled'], ['Disable', 'Frankfurt', 'NODE_DISABLE', 'Connected'],
    ['Restart', 'Frankfurt', 'NODE_RESTART', 'Connected'],
  ])('confirms %s and keeps actual state unchanged while the request is queued', async (label, node, action, actual) => {
    const { calls } = setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { activeDisabled: true })
    await screen.findByText(node)
    const row = screen.getByText(node).closest('tr')!
    fireEvent.click(within(row).getByRole('button', { name: 'Actions' }))
    fireEvent.click(within(row).getByRole('menuitem', { name: label }))
    expect(calls.some(call => call.url.endsWith('/actions') && call.method === 'POST')).toBe(false)
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(calls.find(call => call.url.endsWith('/actions') && call.method === 'POST')?.body?.action).toBe(action))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(within(row).getByText(actual)).toBeTruthy()
  })

  it.each([['RUNNING', 'Running'], ['COMPLETED', 'Successful'], ['FAILED', 'Error']] as const)(
    'shows sync history %s in words with a safe failure reason', async (status, label) => {
      setup('/organizations/org/integrations/one?tab=history', 'OWNER', { sessions: [session(status, status === 'FAILED' ? 'INTEGRATION_TIMEOUT' : null)] })
      const table = await screen.findByRole('table')
      expect(within(table).getByText(label)).toBeTruthy()
      expect(within(table).queryByText(status)).toBeNull()
      if (status === 'FAILED') expect(within(table).getByText('The panel did not respond in time.')).toBeTruthy()
    })

  it('marks unknown node results as a safety warning and offers sync instead of another operation', async () => {
    const execution: IntegrationActionExecution = { id: 'unknown-action', requestId: 'old-id', integrationId: 'one',
      inventoryObjectId: 'obj-a', displayName: 'Frankfurt', requestedByUserId: 'operator', requestedByName: null, action: 'NODE_RESTART',
      status: 'UNKNOWN', source: 'MANUAL', desiredStateId: null, desiredStateVersion: null,
      createdAt: '2026-09-29T10:00:03Z', startedAt: '2026-09-29T10:00:04Z', finishedAt: '2026-09-29T10:00:05Z',
      errorCode: 'INTEGRATION_ACTION_RESULT_UNKNOWN' }
    setup('/organizations/org/integrations/one?tab=nodes', 'OWNER', { executions: [execution] })
    const row = (await screen.findByText('Frankfurt')).closest('tr')!
    await within(row).findByText(/operation result is unknown/)
    expect(within(row).getByRole('button', { name: 'Sync now' })).toBeTruthy()
    fireEvent.click(within(row).getByRole('button', { name: 'Actions' }))
    expect(within(row).queryByRole('menuitem', { name: 'Restart' })).toBeNull()
    expect(within(row).getByRole('menuitem', { name: 'Link to server' })).toBeTruthy()
  })
})
