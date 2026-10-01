// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import type { IntegrationResponse } from '../types/integration'
import { IntegrationFormPage, IntegrationsPage } from './IntegrationsPage'

const existing: IntegrationResponse = { id: 'one', name: 'Main Remnawave', providerType: 'REMNAWAVE',
  baseUrl: 'https://panel.example.test', enabled: false,
  credential: { apiTokenConfigured: true, caddyApiKeyConfigured: false }, createdAt: '', updatedAt: '',
  managementMode: 'OBSERVE' }
type Call = { url: string; method: string; body?: Record<string, unknown> }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value),
  { status, headers: { 'Content-Type': 'application/json' } })

function setup(entry = '/organizations/org/integrations', role: 'OWNER' | 'MEMBER' = 'OWNER',
  initial: IntegrationResponse[] = [existing]) {
  const calls: Call[] = []
  let items = [...initial]
  const client = createAppQueryClient()
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    calls.push({ url, method, body })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role }])
    if (url.includes('/projects') || url.includes('/environments')) return json([])
    if (url.endsWith('/integration-providers')) return json([{ type: 'REMNAWAVE', displayName: 'Remnawave', capabilities: ['CONNECTIVITY_TEST'] }])
    if (url.endsWith('/integrations') && method === 'GET') return json(items)
    if (url.endsWith('/integrations') && method === 'POST') {
      const value = { ...existing, id: 'created', name: body!.name as string, baseUrl: body!.baseUrl as string }
      items = [...items, value]; return json(value, 201)
    }
    if (url.endsWith('/integrations/one') && method === 'GET') return json(items[0])
    if (url.endsWith('/integrations/one') && method === 'PUT') {
      const value = { ...items[0], name: body!.name as string, baseUrl: body!.baseUrl as string }
      items = [value]; return json(value)
    }
    if (url.endsWith('/enable') || url.endsWith('/disable')) {
      const value = { ...items[0], enabled: url.endsWith('/enable') }
      items = [value]; return json(value)
    }
    if (url.endsWith('/test')) return json({ ok: true, providerType: 'REMNAWAVE', latencyMs: 5 })
    if (url.endsWith('/integrations/one') && method === 'DELETE') { items = []; return new Response(null, { status: 204 }) }
    throw new Error(`Unexpected request ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/organizations/:organizationId/integrations" element={<IntegrationsPage />} />
      <Route path="/organizations/:organizationId/integrations/new" element={<IntegrationFormPage />} />
      <Route path="/organizations/:organizationId/integrations/:integrationId/edit" element={<IntegrationFormPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return { calls, client }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('integrations settings', () => {
  it('hides privileged settings from members without requesting them', async () => {
    const { calls } = setup(undefined, 'MEMBER')
    expect(await screen.findByText('Integration settings require owner access.')).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/integrations'))).toBe(false)
  })

  it('shows localized inventory counts separately from sync state, omitting unavailable counts', async () => {
    const withInventory: IntegrationResponse = { ...existing, enabled: true, overview: {
      lastSync: { id: 'sync-1', integrationId: 'one', trigger: 'MANUAL', status: 'COMPLETED',
        startedAt: '2026-10-01T10:00:00Z', finishedAt: '2026-10-01T10:00:01Z', errorCode: null, errorMessage: null, counts: null },
      lastSuccessfulSyncAt: '2026-10-01T10:00:01Z', nextRunAt: null,
      inventory: { nodes: { active: 2, inactive: 0 }, hosts: { active: 2, inactive: 0 }, configProfiles: { active: 2, inactive: 0 } },
      desiredState: { managed: 0, compliant: 0, drifted: 0, applying: 0, needsAttention: 0 },
    } }
    setup('/organizations/org/integrations', 'OWNER', [withInventory])
    const card = within((await screen.findByText('Main Remnawave')).closest('article')!)
    expect(card.getByText('2 nodes · 2 hosts · 2 configuration profiles')).toBeTruthy()
    expect(card.getByText('Last sync:')).toBeTruthy()
    expect(card.getByText('Successful')).toBeTruthy()
    expect(document.querySelector('.integration-sync-when')?.textContent).toBeTruthy()
    expect(card.getByRole('link', { name: 'Open' })).toBeTruthy()
    fireEvent.click(card.getByRole('button', { name: 'Actions' }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: 'Test connection' })).toBeTruthy()
    expect(within(menu).getByRole('menuitem', { name: 'Edit' })).toBeTruthy()
    expect(within(menu).getByRole('menuitem', { name: 'Disable' })).toBeTruthy()
    cleanup(); vi.unstubAllGlobals()

    const nodeOnly = { ...existing, overview: {
      lastSync: null, lastSuccessfulSyncAt: null, nextRunAt: null,
      inventory: { nodes: { active: 1, inactive: 0 } }, desiredState: { managed: 0, compliant: 0, drifted: 0, applying: 0, needsAttention: 0 },
    } as unknown as NonNullable<IntegrationResponse['overview']> }
    setup('/organizations/org/integrations', 'OWNER', [nodeOnly])
    await screen.findByText('1 node')
    const nodeArticle = screen.getByText('1 node').closest('article')!
    expect(nodeArticle.querySelector('.integration-card-statuses')?.textContent).toContain('Last sync: Never')
    expect(nodeArticle.querySelector('.integration-overview-line')?.textContent).toBe('1 node')
  })

  it('tests and switches an integration, then deletes it without exposing credentials', async () => {
    const { calls } = setup()
    expect(await screen.findByText('Main Remnawave')).toBeTruthy()
    expect(screen.queryByText('API token configured')).toBeNull()
    expect(screen.getByText('Not checked')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Test connection' }))
    expect(await screen.findByText('Connection successful')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Enable' }))
    expect(await screen.findByText('Enabled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Disable' }))
    const dialog = screen.getByRole('dialog', { name: 'Disable integration?' })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Disable' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(await screen.findByText('Disabled')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Actions' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Delete' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }))
    expect(await screen.findByText('No integrations yet')).toBeTruthy()
    expect(calls.filter(call => call.url.endsWith('/test'))).toHaveLength(1)
    expect(calls.filter(call => call.url.endsWith('/enable'))).toHaveLength(1)
    expect(calls.filter(call => call.url.endsWith('/disable'))).toHaveLength(1)
  })

  it('creates disabled with token and optional Caddy key, while keeping secrets out of query cache', async () => {
    const { calls, client } = setup('/organizations/org/integrations/new', 'OWNER', [])
    fireEvent.change(await screen.findByLabelText('Name'), { target: { value: 'New panel' } })
    fireEvent.change(screen.getByLabelText('Base URL'), { target: { value: 'https://panel.example.test' } })
    fireEvent.change(screen.getByLabelText('API token'), { target: { value: 'private-api-token' } })
    fireEvent.change(screen.getByLabelText('Additional access key'), { target: { value: 'private-caddy-key' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('New panel')).toBeTruthy()
    const write = calls.find(call => call.method === 'POST' && call.url.endsWith('/integrations'))
    expect(write?.body).toEqual({ name: 'New panel', providerType: 'REMNAWAVE', baseUrl: 'https://panel.example.test',
      credentials: { apiToken: 'private-api-token', caddyApiKey: 'private-caddy-key' } })
    expect(JSON.stringify(client.getQueryCache().getAll())).not.toContain('private-api-token')
    await waitFor(() => expect(JSON.stringify(client.getMutationCache().getAll())).not.toContain('private-api-token'))
  })

  it('edits metadata without sending credentials', async () => {
    const { calls } = setup('/organizations/org/integrations/one/edit')
    expect(await screen.findByDisplayValue('Main Remnawave')).toBeTruthy()
    expect(screen.queryByDisplayValue('private-api-token')).toBeNull()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Renamed' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Renamed')).toBeTruthy()
    const write = calls.find(call => call.method === 'PUT')
    expect(write?.body).toEqual({ name: 'Renamed', baseUrl: 'https://panel.example.test' })
  })

  it('replaces the whole credential when requested, so omitting Caddy clears its key', async () => {
    const { calls } = setup('/organizations/org/integrations/one/edit')
    expect(await screen.findByDisplayValue('Main Remnawave')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Replace credentials' }))
    fireEvent.change(screen.getByLabelText('API token'), { target: { value: 'new-private-token' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await screen.findByText('Main Remnawave')).toBeTruthy()
    const write = calls.find(call => call.method === 'PUT')
    expect(write?.body).toEqual({ name: 'Main Remnawave', baseUrl: 'https://panel.example.test',
      credentials: { apiToken: 'new-private-token', caddyApiKey: null } })
  })
})
