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
  loseDeployResponse = false, loseRolloutResponse = false, options: { locale?: 'ru' | 'en'; status?: string; deployment?: string; rollout?: string } = {}) {
  const calls: { url: string; method: string; body?: Record<string, unknown> }[] = []
  let managed = adopted
  let revision = 1
  let status = options.status ?? 'IN_SYNC'
  let latestDeployment: Record<string, unknown> | null = options.deployment ? {
    id: 'prior', requestId: 'prior-request', revisionNumber: 1, status: options.deployment,
    createdAt: '2026-09-30T10:00:00Z', startedAt: null, finishedAt: null, errorCode: options.deployment === 'FAILED' ? 'INTEGRATION_CONFIG_REMOTE_CHANGED' : null,
  } : null
  let content: Record<string, unknown> = { a: 1, privateKey: 'PRIVATE-CONTENT' }
  const snapshots = new Map([[1, content]])
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
    if (new RegExp('/revisions/[0-9]+$').test(url) && method === 'GET') {
      const selected = Number(url.split('/').at(-1)); return json({ revisionNumber: selected, sha256: 'a'.repeat(64), config: snapshots.get(selected) })
    }
    if (url === `${path}/revisions` && method === 'POST') { revision += 1; content = body!.config as Record<string, unknown>
      snapshots.set(revision, content); status = 'LOCAL_CHANGES'; return json({ revisionNumber: revision }, 201) }
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
    if (url === `${path}/rollouts?limit=50`) return json({ items: options.rollout ? [{
      id: 'safe-prior', requestId: 'safe-request', status: options.rollout, automaticRollback: true,
      baselineRevisionNumber: 1, targetRevisionNumber: 2, affectedNodes: 2, preexistingUnhealthyNodes: 1,
      errorCode: options.rollout === 'FAILED' ? 'INTEGRATION_CONFIG_ROLLOUT_VERIFICATION_TIMEOUT' : null,
    }] : [] })
    if (url === `${path}/revisions/${revision}/rollout-preview` && method === 'POST') return json({
      baselineRevisionNumber: 1, targetRevisionNumber: revision, baselineSha256: 'a'.repeat(64),
      targetSha256: 'b'.repeat(64), affectedNodes: 2, healthyNodes: 1,
      preexistingUnhealthyNodes: 1, automaticRollbackSupported: true, nodeCanarySupported: false })
    if (url === `${path}/revisions/${revision}/rollouts` && method === 'POST') {
      if (loseRolloutResponse) throw new TypeError('Network response lost')
      return json({
      id: 'rollout', requestId: body!.requestId, status: 'PREPARING', automaticRollback: body!.automaticRollback,
      baselineRevisionNumber: null, targetRevisionNumber: revision, baselineSha256: null,
      targetSha256: 'b'.repeat(64), targetDeploymentId: null, rollbackDeploymentId: null,
      createdAt: '2026-09-30T10:00:00Z', startedAt: null, finishedAt: null,
      verificationDeadlineAt: null, errorCode: null, errorMessage: null, affectedNodes: 0,
      healthyNodes: 0, preexistingUnhealthyNodes: 0, nodes: [] }, 202)
    }
    throw new Error(`Unexpected ${method} ${url}`)
  }))
  render(<I18nProvider initialLocale={options.locale ?? "en"}><QueryClientProvider client={createAppQueryClient()}>
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
  it('shows the integration and profile hierarchy and preserves the workspace on return', async () => {
    setup(`${route}/config-profiles/profile?project=p&environment=e`, 'OWNER', true)
    const breadcrumb = await screen.findByRole('navigation', { name: 'Location in the infrastructure' })
    expect(within(breadcrumb).getAllByRole('link').map(link => [link.textContent, link.getAttribute('href')]))
      .toEqual([
        ['Integrations', '/organizations/org/integrations?project=p&environment=e'],
        ['Panel', '/organizations/org/integrations/one?project=p&environment=e&tab=profiles'],
      ])
    expect(within(breadcrumb).getByText('Default-Profile').getAttribute('aria-current')).toBe('page')
    expect(document.querySelector('.workspace-back')).toBeNull()
  })

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
    fireEvent.click(screen.getByRole('button', { name: 'Preview deployment' }))
    await screen.findByText(/Current Remnawave configuration/)
    fireEvent.click(screen.getByRole('button', { name: 'Deploy' }))
    const dialog = screen.getByRole('dialog', { name: /Deploy revision/ })
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

  it('explains profile-wide guarded rollout and starts it with automatic rollback', async () => {
    const calls = setup(`${route}/config-profiles/profile`, 'OWNER', true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Safe deployment' }))
    const dialog = await screen.findByRole('dialog', { name: /Safe deployment of revision/ })
    expect(within(dialog).getByText(/all enabled nodes/)).toBeTruthy()
    expect(within(dialog).getByText('Affected nodes')).toBeTruthy()
    expect(within(dialog).getByText('2')).toBeTruthy()
    expect(within(dialog).getByText(/Nodes unhealthy before deployment/)).toBeTruthy()
    expect(within(dialog).getByText(/Deployment to individual nodes in stages is unavailable/)).toBeTruthy()
    const checkbox = within(dialog).getByRole('checkbox') as HTMLInputElement
    expect(checkbox.checked).toBe(true)
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start deployment' }))
    await waitFor(() => expect(calls.some(call => call.url === `${path}/revisions/1/rollouts`)).toBe(true))
    const start = calls.find(call => call.url === `${path}/revisions/1/rollouts`)
    expect(start?.body?.automaticRollback).toBe(true)
    expect(typeof start?.body?.requestId).toBe('string')
    expect(screen.queryByRole('combobox', { name: /canary/i })).toBeNull()
  })

  it('reuses an unresolved deployment request ID after a page reload', async () => {
    const entry = `${route}/config-profiles/profile`
    const first = setup(entry, 'OWNER', true, true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Preview deployment' }))
    await screen.findByText(/Current Remnawave configuration/)
    fireEvent.click(screen.getByRole('button', { name: 'Deploy' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: /Deploy revision/ }))
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

  it('reuses an unresolved rollout request ID after a page reload', async () => {
    const entry = `${route}/config-profiles/profile`
    const first = setup(entry, 'OWNER', true, false, true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Safe deployment' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: /Safe deployment of revision/ }))
      .getByRole('button', { name: 'Start deployment' }))
    await waitFor(() => expect(first.some(call => call.url === `${path}/revisions/1/rollouts`)).toBe(true))
    const firstId = first.find(call => call.url === `${path}/revisions/1/rollouts`)?.body?.requestId
    expect(firstId).toBe(window.sessionStorage.getItem('integration-config-rollout-request:org:one:profile:1'))
    cleanup()
    const second = setup(entry, 'OWNER', true)
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    fireEvent.click(await screen.findByRole('button', { name: 'Check the same rollout request' }))
    await waitFor(() => expect(second.some(call => call.url === `${path}/revisions/1/rollouts`)).toBe(true))
    expect(second.find(call => call.url === `${path}/revisions/1/rollouts`)?.body?.requestId).toBe(firstId)
  })
})

describe('UI-5 configuration presentation', () => {
  it.each([
    ['IN_SYNC', 'In sync'], ['LOCAL_CHANGES', 'Local changes'], ['REMOTE_DRIFT', 'Configuration changed in Remnawave'],
    ['DEPLOYING', 'Deploying'], ['WAITING_REFRESH', 'Waiting for synchronization'],
    ['DEPLOYMENT_FAILED', 'Deployment failed'], ['UNAVAILABLE', 'Profile unavailable'],
  ])('presents profile status %s without exposing the enum or configuration on the list', async (status, label) => {
    setup(`${route}?tab=profiles`, 'OWNER', true, false, false, { status })
    expect(await screen.findByText(label)).toBeTruthy()
    expect(screen.queryByText(/PRIVATE-CONTENT/)).toBeNull()
    expect(screen.queryByText(status)).toBeNull()
  })

  it('explains remote drift in Russian as a warning and requires a diff before deployment', async () => {
    const calls = setup(`${route}/config-profiles/profile`, 'OWNER', true, false, false, { locale: 'ru', status: 'REMOTE_DRIFT' })
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
    expect(screen.getAllByText('Конфигурация изменена в Remnawave')[0].className).toContain('status-warning')
    expect(screen.getByText(/Синхронизируйте интеграцию и проверьте изменения/)).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Развернуть' }) as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(call => call.url.endsWith('/deploy'))).toBe(false)
  })

  it('creates a new revision without modifying the old one, validates size and selects the old revision', async () => {
    setup(`${route}/config-profiles/profile`, 'OWNER', true)
    await screen.findByText(/PRIVATE-CONTENT/)
    fireEvent.click(screen.getByRole('button', { name: 'New revision' }))
    expect(screen.getByText(/existing revision remains unchanged/)).toBeTruthy()
    fireEvent.change(screen.getByLabelText('JSON configuration'), { target: { value: JSON.stringify({ x: 'a'.repeat(262144) }) } })
    fireEvent.click(screen.getByRole('button', { name: 'Save revision' }))
    expect(screen.getByText('Exceeds the 256 KiB limit')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('JSON configuration'), { target: { value: '{"a":2}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save revision' }))
    await screen.findByText(/"a": 2/)
    await waitFor(() => expect(screen.getByRole('option', { name: 'Revision 1' })).toBeTruthy())
    fireEvent.change(screen.getByRole('combobox'), { target: { value: '1' } })
    expect(await screen.findByText(/PRIVATE-CONTENT/)).toBeTruthy()
  })

  it.each([['QUEUED', 'Queued'], ['RUNNING', 'Running'], ['SUCCEEDED', 'Successful'], ['FAILED', 'Failed'], ['UNKNOWN', 'Result unknown']])(
    'shows readable deployment history for %s', async (deployment, label) => {
      setup(`${route}/config-profiles/profile`, 'OWNER', true, false, false, { deployment })
      await screen.findByText(/PRIVATE-CONTENT/)
      if (deployment === 'UNKNOWN') expect(screen.getByText(/cannot safely determine whether Remnawave applied/)).toBeTruthy()
      fireEvent.click(screen.getByRole('tab', { name: 'Deployments' }))
      expect(await screen.findByText(label)).toBeTruthy()
      if (deployment === 'FAILED') expect(screen.getAllByText(/Synchronize and review the new diff/).length).toBeGreaterThan(0)
      expect(screen.queryByText('INTEGRATION_CONFIG_REMOTE_CHANGED')).toBeNull()
    })

  it.each([
    ['PREPARING', 'Preparing baseline'], ['APPLYING', 'Applying configuration'], ['VERIFYING', 'Verifying node health'],
    ['ROLLBACK_APPLYING', 'Restoring baseline'], ['ROLLBACK_VERIFYING', 'Verifying rollback'], ['SUCCEEDED', 'Successful'],
    ['ROLLED_BACK', 'Rolled back'], ['FAILED', 'Failed'], ['UNKNOWN', 'Result unknown'], ['CANCELLED', 'Cancelled'],
  ])('shows the actual safe deployment stage %s without inventing completed stages', async (rollout, label) => {
    setup(`${route}/config-profiles/profile`, 'OWNER', true, false, false, { rollout })
    await screen.findByText(/PRIVATE-CONTENT/)
    fireEvent.click(screen.getByRole('tab', { name: 'Safe deployments' }))
    expect((await screen.findAllByText(label)).length).toBeGreaterThan(0)
    expect(screen.getByText(/Deployment to individual nodes in stages is unavailable/)).toBeTruthy()
    expect(screen.queryByText(/Preparing fresh baseline/)).toBeNull()
    expect(screen.queryByText(rollout)).toBeNull()
  })

  it('allows automatic rollback to be turned off explicitly', async () => {
    const calls = setup(`${route}/config-profiles/profile`, 'OWNER', true)
    await screen.findByText(/PRIVATE-CONTENT/)
    fireEvent.click(screen.getByRole('button', { name: 'Safe deployment' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('checkbox', { name: 'Automatic rollback' }))
    fireEvent.click(within(dialog).getByRole('button', { name: 'Start deployment' }))
    await waitFor(() => expect(calls.find(call => call.url.endsWith('/rollouts') && call.method === 'POST')?.body?.automaticRollback).toBe(false))
  })
})
