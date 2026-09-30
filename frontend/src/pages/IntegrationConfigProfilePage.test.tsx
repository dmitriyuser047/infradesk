// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../app/queryClient'
import { I18nProvider } from '../i18n'
import { IntegrationDetailPage } from './IntegrationDetailPage'
import { IntegrationConfigProfilePage } from './IntegrationConfigProfilePage'

const root = '/api/v1/organizations/org/integrations/one'
const path = `${root}/inventory/objects/profile/config-management`
const route = '/organizations/org/integrations/one'
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value),
  { status, headers: { 'Content-Type': 'application/json' } })
const configProfile = { id: 'profile', objectType: 'CONFIG_PROFILE', externalId: '33333333-3333-3333-3333-333333333333',
  displayName: 'Default-Profile', active: true, firstSeenAt: '', lastSeenAt: '2026-09-30T10:00:00Z',
  summary: { viewPosition: 1, createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-30T10:00:00Z',
    nodeUuids: ['node-1', 'node-2'], inbounds: [], configSha256: 'a'.repeat(64) } }
const integration = { id: 'one', name: 'Panel', providerType: 'REMNAWAVE', baseUrl: 'https://panel.example.test',
  enabled: true, credential: { apiTokenConfigured: true, caddyApiKeyConfigured: false },
  createdAt: '', updatedAt: '', managementMode: 'OBSERVE' }

function setup(entry = `${route}?tab=profiles`, role: 'OWNER' | 'MEMBER' = 'OWNER', adopted = false,
  loseDeployResponse = false) {
  const calls: { url: string; method: string; body?: Record<string, unknown> }[] = []
  let managed = adopted
  let revision = 1
  let status = 'IN_SYNC'
  let latestDeployment: Record<string, unknown> | null = null
  let content: Record<string, unknown> = { a: 1, privateKey: 'PRIVATE-CONTENT' }
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) as Record<string, unknown> : undefined
    calls.push({ url, method, body })
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'org', name: 'Org', role }])
    if (url === root) return json(integration)
    if (url.startsWith(`${root}/inventory/config-profiles`))
      return json({ items: [{ ...configProfile, configManagement: managed ? {
        configurationProfileId: 'local', name: 'Main', revisionNumber: revision, status } : null }],
        total: 1, limit: 100, offset: 0 })
    if (url === path && method === 'GET') return managed ? json({ bindingId: 'binding',
      profile: { id: 'local', code: 'remnawave-main', name: 'Main', description: null,
        kind: 'REMNAWAVE_CONFIG', latestRevisionNumber: revision },
      latestSha256: 'a'.repeat(64), remoteSha256: 'a'.repeat(64), latestDeployedSha256: null,
      status, nodesUsingProfile: 2, latestDeployment }) : json({ code: 'INTEGRATION_CONFIG_PROFILE_NOT_FOUND', message: 'missing' }, 404)
    if (url === `${path}/adopt` && method === 'POST') { managed = true; status = 'WAITING_REFRESH'; return json({ profileId: 'local' }, 201) }
    if (url === `${path}/revisions?limit=100`) return json({ items: Array.from({ length: revision }, (_, index) => ({
      revisionNumber: index + 1, createdBy: 'Owner', createdAt: '2026-09-30T10:00:00Z' })) })
    if (url === `${path}/revisions/${revision}` && method === 'GET')
      return json({ revisionNumber: revision, sha256: 'a'.repeat(64), config: content })
    if (url === `${path}/revisions` && method === 'POST') { revision += 1; content = body!.config as Record<string, unknown>
      status = 'LOCAL_CHANGES'; return json({ revisionNumber: revision }, 201) }
    if (url === `${path}/revisions/${revision}/preview` && method === 'POST') return json({ revisionNumber: revision,
      localSha256: 'b'.repeat(64), remoteSha256: 'a'.repeat(64), remoteUpdatedAt: null, changed: true,
      diff: { text: '- old\n+ new', truncated: false, approximate: false, addedLines: 1, removedLines: 1 } })
    if (url === `${path}/revisions/${revision}/deploy` && method === 'POST') {
      status = 'DEPLOYING'; latestDeployment = { id: 'deployment', requestId: body!.requestId, revisionNumber: revision,
        status: 'QUEUED', requestedByUserId: 'owner', createdAt: '2026-09-30T10:00:00Z', startedAt: null,
        finishedAt: null, expectedRemoteSha256: 'a'.repeat(64), desiredSha256: 'b'.repeat(64), errorCode: null }
      if (loseDeployResponse) throw new TypeError('Network response lost')
      return json(latestDeployment, 202)
    }
    if (url === `${path}/deployments?limit=50`) return json({ items: latestDeployment ? [latestDeployment] : [] })
    throw new Error(`Unexpected ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={createAppQueryClient()}>
    <MemoryRouter initialEntries={[entry]}><Routes>
      <Route path="/organizations/:organizationId/integrations/:integrationId" element={<IntegrationDetailPage />} />
      <Route path="/organizations/:organizationId/integrations/:integrationId/config-profiles/:objectId"
        element={<IntegrationConfigProfilePage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return calls
}

afterEach(() => { cleanup(); vi.unstubAllGlobals(); window.sessionStorage.clear() })

describe('Remnawave config management', () => {
  it('adopts an unmanaged profile and loads content only after opening its page', async () => {
    const calls = setup()
    fireEvent.click(await screen.findByRole('button', { name: 'Adopt' }))
    const dialog = screen.getByRole('dialog', { name: 'Adopt Remnawave profile' })
    expect(within(dialog).getByText(/encrypted at rest/)).toBeTruthy()
    expect(within(dialog).getByLabelText('InfraDesk code')).toBeTruthy()
    expect(calls.some(call => /revisions\/1$/.test(call.url))).toBe(false)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Adopt' }))
    expect(await screen.findByRole('link', { name: 'Open' })).toBeTruthy()
    expect(calls.find(call => call.url === `${path}/adopt`)?.body?.code).toBe('default-profile')
    expect(calls.some(call => call.url === path && call.method === 'GET')).toBe(false)
    fireEvent.click(screen.getByRole('link', { name: 'Open' }))
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    expect(calls.some(call => call.url === `${path}/revisions/1`)).toBe(true)
  })

  it('creates an immutable revision, previews remote diff and confirms a durable deploy', async () => {
    const calls = setup(`${route}/config-profiles/profile`, 'OWNER', true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'New revision' }))
    const editor = screen.getByLabelText('JSON configuration')
    fireEvent.change(editor, { target: { value: '{' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save revision' }))
    expect(screen.getByText('Invalid JSON')).toBeTruthy()
    fireEvent.change(editor, { target: { value: '{"a":2}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save revision' }))
    expect(await screen.findByText(/"a": 2/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Preview deployment' }))
    expect(await screen.findByText(/Current Remnawave configuration/)).toBeTruthy()
    expect(screen.getByText(/- old/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Deploy' }))
    const dialog = screen.getByRole('dialog', { name: 'Confirm deployment' })
    expect(within(dialog).getByText(/Nodes using profile: 2/)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Deploy' }))
    await waitFor(() => expect(calls.some(call => call.url === `${path}/revisions/2/deploy`)).toBe(true))
    const deployCall = calls.find(call => call.url === `${path}/revisions/2/deploy`)
    expect(typeof deployCall?.body?.requestId).toBe('string')
    expect(JSON.stringify(deployCall?.body)).not.toContain('PRIVATE-CONTENT')
  })

  it('keeps management requests and decrypted content hidden from members', async () => {
    const calls = setup(`${route}/config-profiles/profile`, 'MEMBER', true)
    expect(await screen.findByText('Access denied')).toBeTruthy()
    expect(calls.some(call => call.url.includes('/integrations/one'))).toBe(false)
  })

  it('reuses an unresolved deployment request ID after a page reload', async () => {
    const entry = `${route}/config-profiles/profile`
    const first = setup(entry, 'OWNER', true, true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Deploy' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: 'Confirm deployment' }))
      .getByRole('button', { name: 'Deploy' }))
    await waitFor(() => expect(first.some(call => call.url === `${path}/revisions/1/deploy`)).toBe(true))
    const firstId = first.find(call => call.url === `${path}/revisions/1/deploy`)?.body?.requestId
    expect(firstId).toBe(window.sessionStorage.getItem('integration-config-deploy-request:org:one:profile:1'))
    cleanup()
    const second = setup(entry, 'OWNER', true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(await screen.findByRole('button', { name: 'Check the same request' }))
    await waitFor(() => expect(second.some(call => call.url === `${path}/revisions/1/deploy`)).toBe(true))
    expect(second.find(call => call.url === `${path}/revisions/1/deploy`)?.body?.requestId).toBe(firstId)
  })
})
