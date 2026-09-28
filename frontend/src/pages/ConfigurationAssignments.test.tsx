// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { ConfigurationProfile, ConfigurationRevision } from '../types/configuration'
import type { ConfigurationAssignment, ConfigurationAssignmentDetail } from '../types/configurationAssignment'
import type { ResourceResponse } from '../types/resource'
import { ConfigurationAssignmentCreatePage, ConfigurationAssignmentEditPage } from './ConfigurationAssignmentPages'
import { ConfigurationProfilePage } from './ConfigurationProfilePage'
import { ResourcePage } from './ResourcePage'

const owner = { id: 'user', displayName: 'Dmitriy' }
const v1: ConfigurationRevision = {
  revisionNumber: 1, template: 'server_name {{ domain }}; listen {{ port }}; # {{ legacy }}', createdAt: '2026-09-21T10:00:00Z', createdBy: owner,
  variables: [
    { name: 'domain', type: 'STRING', required: true, defaultValue: null, description: 'Public name' },
    { name: 'port', type: 'INTEGER', required: true, defaultValue: '443', description: null },
    { name: 'legacy', type: 'STRING', required: false, defaultValue: null, description: null },
  ],
}
const v2: ConfigurationRevision = {
  revisionNumber: 2, template: 'server_name {{ domain }}; listen {{ port }}; http2 {{ http2 }};', createdAt: '2026-09-22T10:00:00Z', createdBy: owner,
  variables: [
    { name: 'domain', type: 'STRING', required: true, defaultValue: null, description: 'Public name' },
    { name: 'port', type: 'INTEGER', required: true, defaultValue: '443', description: null },
    { name: 'http2', type: 'BOOLEAN', required: false, defaultValue: 'true', description: null },
  ],
}

function profile(overrides: Partial<ConfigurationProfile> = {}): ConfigurationProfile {
  return { id: 'p1', code: 'nginx-main', name: 'Nginx main', description: null, archived: false, latestRevisionNumber: 2,
    latestRevisionCreatedAt: v2.createdAt, createdAt: v1.createdAt, updatedAt: v2.createdAt, ...overrides }
}

function assignment(overrides: Partial<ConfigurationAssignment> = {}, resource: Partial<ConfigurationAssignment['resource']> = {}): ConfigurationAssignment {
  return {
    id: 'a1', version: 3, targetPath: '/etc/nginx/nginx.conf', profileRevisionNumber: 1, removedAt: null,
    createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:00:00Z',
    resource: { id: 'node', name: 'prod-vps-01', code: 'prod-vps-01', resourceTypeCode: 'NODE', active: true,
      project: { id: 'project', name: 'App' }, environment: { id: 'env', name: 'Production', kind: 'PROD' }, ...resource },
    profile: { id: 'p1', code: 'nginx-main', name: 'Nginx main', archived: false, latestRevisionNumber: 2 },
    ...overrides,
  }
}

function detail(base: ConfigurationAssignment = assignment()): ConfigurationAssignmentDetail {
  const revision = base.profileRevisionNumber === 1 ? v1 : v2
  return { ...base, revision: { revisionNumber: revision.revisionNumber, variables: revision.variables, createdBy: owner, createdAt: revision.createdAt },
    values: [{ name: 'domain', value: 'example.com' }, { name: 'legacy', value: 'x' }] }
}

const resourceBase = { organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', parentResourceId: null, createdAt: '', updatedAt: '' }
const node: ResourceResponse = { ...resourceBase, id: 'node', code: 'prod-vps-01', name: 'prod-vps-01', resourceTypeCode: 'NODE', active: true,
  data: { kind: 'NODE', spec: null, status: { online: true, cpuUsagePercent: 4, memoryUsagePercent: 30, uptimeSeconds: 100 } } } as ResourceResponse
const retired: ResourceResponse = { ...node, id: 'retired', code: 'retired', name: 'retired-node', active: false }
const container: ResourceResponse = { ...resourceBase, id: 'nginx', code: 'nginx', name: 'nginx-container', resourceTypeCode: 'CONTAINER', active: true,
  data: { kind: 'CONTAINER', spec: { image: 'nginx' }, status: { state: 'running' } } } as ResourceResponse

interface State {
  profile: ConfigurationProfile
  assignments: ConfigurationAssignment[]
  detail: ConfigurationAssignmentDetail
  conflictOnce?: boolean
  incompatible?: boolean
}

/** A small in-memory backend for the assignment endpoints and what the pages around them read. */
function backend(initial: Partial<State> = {}) {
  const state: State = { profile: profile(), assignments: [assignment(), assignment({ id: 'a2', targetPath: '/etc/app.conf', profileRevisionNumber: 2 },
    { id: 'retired', name: 'retired-node', active: false })], detail: detail(), ...initial }
  const requests: { method: string; path: string; body?: unknown }[] = []
  const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace('/api/v1/organizations/org', '')
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    requests.push({ method, path: path + url.search, body })
    if (path === '/configuration-profiles' && method === 'GET') return json([state.profile])
    if (path === '/configuration-profiles/p1') return json({ profile: state.profile, latestRevision: v2 })
    if (path === '/configuration-profiles/p1/revisions') return json([v2, v1].map(item => ({ revisionNumber: item.revisionNumber,
      variableCount: item.variables.length, createdBy: owner, createdAt: item.createdAt })))
    const revision = path.match(/^\/configuration-profiles\/p1\/revisions\/(\d+)$/)
    if (revision) return json(Number(revision[1]) === 1 ? v1 : v2)
    if (path === '/configuration-assignments' && method === 'GET') return json(state.assignments)
    if (path === '/configuration-assignments/preview') {
      const domain = body.values.find((value: { name: string }) => value.name === 'domain')?.value
      return json(domain ? { valid: true, resolvedRevisionNumber: body.profileRevisionNumber, error: null,
        renderedPreview: `server_name ${domain}; listen 443; http2 true;` }
        : { valid: false, resolvedRevisionNumber: body.profileRevisionNumber, renderedPreview: null,
          error: { code: 'CONFIGURATION_VALUE_MISSING', variableName: 'domain' } })
    }
    if (path === '/configuration-assignments' && method === 'POST') return json(detail(assignment({ id: 'created' })), 201)
    if (path === '/configuration-assignments/a1' && method === 'GET') return json(state.detail)
    if (path === '/configuration-assignments/a1/deployment-preview') return json({
      assignmentVersion: state.detail.version, profileRevisionNumber: 1, targetPath: state.detail.targetPath,
      remote: { exists: true, sha256: 'a'.repeat(64) }, desired: { sha256: 'b'.repeat(64) }, changed: true,
      diff: { text: '--- remote\n+++ desired\n-old\n+new\n', truncated: false, addedLines: 1, removedLines: 1 },
      connection: { id: 'ssh-source', name: 'Production SSH', updatedAt: '2026-09-28T10:00:00Z' },
    })
    if (path === '/configuration-assignments/a1/deployments' && method === 'POST')
      return json({ deploymentId: 'deployment-1', state: 'QUEUED' }, 202)
    if (path === '/configuration-deployments/deployment-1') return json({
      id: 'deployment-1', assignmentId: 'a1', assignmentVersion: state.detail.version, resourceId: 'node',
      profileId: 'p1', profileRevisionNumber: 1, targetPath: state.detail.targetPath, connectionId: 'ssh-source',
      desiredSha256: 'b'.repeat(64), expectedRemoteSha256: 'a'.repeat(64), state: 'SUCCEEDED', phase: 'CLEANUP',
      failureCode: null, createdAt: '2026-09-28T10:00:00Z', startedAt: '2026-09-28T10:00:01Z',
      finishedAt: '2026-09-28T10:00:02Z', rolloutId: null,
    })
    if (path === '/configuration-assignments/a1' && method === 'PATCH') {
      if (state.conflictOnce) {
        state.conflictOnce = false
        state.detail = { ...state.detail, version: state.detail.version + 1 }
        return json({ code: 'CONFIGURATION_ASSIGNMENT_CHANGED', message: 'x', variableName: null }, 409)
      }
      return json({ ...state.detail, version: state.detail.version + 1 })
    }
    if (path === '/configuration-assignments/a1' && method === 'DELETE') {
      state.assignments = state.assignments.filter(item => item.id !== 'a1')
      return json({ ...state.detail, removedAt: '2026-09-28T10:00:00Z' })
    }
    if (path === '/configuration-profiles/p1/assignment-promotions/preview') return json({
      revisionNumber: body.revisionNumber, compatible: !state.incompatible,
      items: body.assignments.map((item: { assignmentId: string; expectedVersion: number }) => ({ ...item,
        compatible: !state.incompatible, errorCode: state.incompatible ? 'INCOMPATIBLE_OVERRIDE' : null,
        variableName: state.incompatible ? 'legacy' : null })) })
    if (path === '/configuration-profiles/p1/assignment-promotions') return json({ revisionNumber: body.revisionNumber,
      assignments: body.assignments.map((item: { assignmentId: string; expectedVersion: number }) =>
        ({ assignmentId: item.assignmentId, version: item.expectedVersion + 1 })) })
    if (path === '/configuration-rollouts/preflight') return json(body.targets.map((target: { assignmentId: string;
      expectedVersion: number; connectionId: string }) => ({ ...target, ready: true, desiredSha256: 'b'.repeat(64),
      connectionUpdatedAt: '2026-09-28T10:00:00Z', remote: { exists: true, sha256: 'a'.repeat(64) },
      addedLines: 6, removedLines: 2, errorCode: null })))
    if (path === '/configuration-rollouts' && method === 'POST') return json({ rolloutId: 'r1', state: 'QUEUED' }, 202)
    const rolloutSummary = { id: 'r1', profileId: 'p1', profileRevisionNumber: 2, state: 'SUCCEEDED', canaryCount: 1,
      batchSize: 1, pauseSeconds: 0, stopOnFailure: true, rollbackMode: 'FAILED_TARGET_ONLY',
      createdAt: '2026-09-28T10:00:00Z', startedAt: '2026-09-28T10:00:01Z', finishedAt: '2026-09-28T10:02:00Z' }
    if (path === '/configuration-rollouts' && method === 'GET') return json([rolloutSummary])
    if (path === '/configuration-rollouts/r1') return json({ rollout: rolloutSummary, items: [{ id: 'i1', position: 0,
      assignmentId: 'a1', assignmentVersion: 4, resourceId: 'node', targetPath: '/etc/nginx/nginx.conf',
      state: 'SUCCEEDED', deploymentId: 'deployment-1' }] })
    if (path === '/environments/env/resources') return json([node, retired, container])
    if (path === '/resources/node') return json(node)
    if (path === '/resources/node/context') return json({ project: { id: 'project', name: 'App' },
      environment: { id: 'env', name: 'Production', kind: 'PROD' }, parentResource: null,
      sourceConnections: [{ id: 'ssh-source', name: 'Production SSH', connectorType: 'SSH', active: true }],
      children: [], activeChildCount: 0, openIncidentCount: 0 })
    if (path === '/resources/node/operations') return json({ operations: [], unavailableReason: null })
    if (path.startsWith('/resources/node/')) return json([])
    return json({ code: 'NOT_FOUND', message: 'x' }, 404)
  }))
  return { state, requests }
}

function Where() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderApp(path: string, options: { role?: 'OWNER' | 'MEMBER'; locale?: Locale } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: options.role ?? 'OWNER' }])
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [{ id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' }])
  return render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/organizations/:organizationId/configurations/:profileId" element={<ConfigurationProfilePage />} />
      <Route path="/organizations/:organizationId/configuration-assignments/new" element={<ConfigurationAssignmentCreatePage />} />
      <Route path="/organizations/:organizationId/configuration-assignments/:assignmentId" element={<ConfigurationAssignmentEditPage />} />
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId" element={<ResourcePage />} />
    </Routes><Where /></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

const location = () => screen.getByTestId('location').textContent
const select = (label: string) => screen.getByLabelText(label) as HTMLSelectElement
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement

describe('configuration assignments', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('lists the targets of a profile with their pinned version and status, never claiming anything was applied', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configurations/p1?tab=targets')
    const row = (await screen.findByRole('link', { name: 'prod-vps-01' })).closest('tr')!
    expect(within(row).getByText('/etc/nginx/nginx.conf')).toBeTruthy()
    expect(within(row).getByText('v1')).toBeTruthy()
    expect(within(row).getByText('latest v2')).toBeTruthy()
    expect(within(row).getByText('Assigned')).toBeTruthy()
    expect(within(row).getByText('Newer version available')).toBeTruthy()
    const inactive = screen.getByRole('link', { name: 'retired-node' }).closest('tr')!
    expect(within(inactive).getByText('Target inactive')).toBeTruthy()
    expect(within(inactive).queryByText('Newer version available')).toBeNull()
    expect(within(row.closest('table')!).getAllByRole('columnheader').map(cell => cell.textContent))
      .toEqual(['Resource', 'Environment', 'Target path', 'Assigned version', 'Status', 'Actions'])
    expect(screen.getByText(/InfraDesk has not applied this configuration to the server yet\./)).toBeTruthy()
    expect(document.body.textContent).not.toMatch(/\b(Applied|Synced|Up to date|Deployed)\b/)
    expect(screen.getByRole('link', { name: 'Assign to resource' }).getAttribute('href'))
      .toBe('/organizations/org/configuration-assignments/new?profileId=p1')
    expect(requests.filter(item => item.path.startsWith('/configuration-assignments')).map(item => item.path))
      .toEqual(['/configuration-assignments?limit=50&profileId=p1'])
  })

  it('creates an assignment on an active node: defaults are shown, never sent, and the exact version is saved', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-assignments/new?profileId=p1')
    expect(await screen.findByText('nginx-main')).toBeTruthy()
    expect(screen.getByRole('note').textContent)
      .toBe('Configuration variables are not a secret store. Do not put passwords, tokens or private keys here.')
    fireEvent.change(select('Target project'), { target: { value: 'project' } })
    fireEvent.change(select('Target environment'), { target: { value: 'env' } })
    // Only active nodes: a container or an inactive node cannot take a configuration.
    await waitFor(() => expect([...select('Target node').options].map(option => option.textContent)).toEqual(['Choose a node', 'prod-vps-01']))
    fireEvent.change(select('Target node'), { target: { value: 'node' } })
    // A new assignment starts from the latest version.
    await waitFor(() => expect(select('Version').value).toBe('2'))
    expect(await screen.findByText('Using default: 443')).toBeTruthy()
    expect(input('Value of port').value).toBe('')
    fireEvent.change(input('Value of domain'), { target: { value: 'example.com' } })

    fireEvent.change(input('Target path'), { target: { value: 'etc/nginx/nginx.conf' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save assignment' }))
    expect(screen.getByRole('alert').textContent).toContain('normalized absolute file path')
    expect(requests.some(item => item.method === 'POST')).toBe(false)
    fireEvent.change(input('Target path'), { target: { value: '/etc/nginx/nginx.conf' } })

    fireEvent.click(screen.getByRole('button', { name: 'Preview desired configuration' }))
    const preview = await screen.findByRole('region', { name: 'Desired configuration' })
    expect(within(preview).getByText('server_name example.com; listen 443; http2 true;').tagName).toBe('PRE')
    expect(requests.find(item => item.path === '/configuration-assignments/preview')?.body)
      .toEqual({ profileId: 'p1', profileRevisionNumber: 2, values: [{ name: 'domain', value: 'example.com' }] })

    fireEvent.click(screen.getByRole('button', { name: 'Save assignment' }))
    await waitFor(() => expect(location()).toBe('/organizations/org/configurations/p1?tab=targets'))
    expect(requests.find(item => item.method === 'POST' && item.path === '/configuration-assignments')?.body).toEqual({
      resourceId: 'node', profileId: 'p1', profileRevisionNumber: 2, targetPath: '/etc/nginx/nginx.conf',
      values: [{ name: 'domain', value: 'example.com' }] })
  })

  it('switches versions by variable name, leaves out what the new version lacks, and saves on explicit request only', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-assignments/a1?from=profile')
    expect(await screen.findByText(/Assigned version: v1 · Latest available: v2/)).toBeTruthy()
    expect(select('Version').value).toBe('1')
    expect(input('Value of legacy').value).toBe('x')
    expect(screen.getByText('Newer version available')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Use v2' }))
    expect(select('Version').value).toBe('2')
    expect(await screen.findByLabelText('Value of http2')).toBeTruthy()
    expect(input('Value of domain').value).toBe('example.com')
    expect(screen.queryByLabelText('Value of legacy')).toBeNull()
    expect(screen.getByText('Not in this version, and will not be saved: legacy')).toBeTruthy()
    // Nothing is sent until Save.
    expect(requests.some(item => item.method === 'PATCH')).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Save assignment' }))
    await waitFor(() => expect(location()).toBe('/organizations/org/configurations/p1?tab=targets'))
    expect(requests.find(item => item.method === 'PATCH')?.body).toEqual({ expectedVersion: 3, profileRevisionNumber: 2,
      targetPath: '/etc/nginx/nginx.conf', values: [{ name: 'domain', value: 'example.com' }] })
  })

  it('surfaces a concurrent change and reloads the current desired state', async () => {
    const { requests } = backend({ conflictOnce: true })
    renderApp('/organizations/org/configuration-assignments/a1')
    fireEvent.change(await screen.findByLabelText('Value of domain'), { target: { value: 'example.org' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save assignment' }))
    const alert = await screen.findByText(/Someone else changed this assignment/)
    expect(alert).toBeTruthy()
    expect(location()).toBe('/organizations/org/configuration-assignments/a1')
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    await waitFor(() => expect(requests.filter(item => item.method === 'GET' && item.path === '/configuration-assignments/a1')).toHaveLength(2))
    await waitFor(() => expect(input('Value of domain').value).toBe('example.com'))
    expect(screen.queryByText(/Someone else changed this assignment/)).toBeNull()
  })

  it('previews a remote diff before deploying the exact hashes through the selected SSH source', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-assignments/a1')
    const preview = await screen.findByRole('button', { name: 'Preview remote changes' })
    await waitFor(() => expect(preview.hasAttribute('disabled')).toBe(false))
    expect(screen.getByRole('button', { name: 'Deploy previewed version' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(preview)
    expect(await screen.findByText(/Remote SHA-256/)).toBeTruthy()
    expect(screen.getByText(/-old/).tagName).toBe('PRE')
    fireEvent.change(select('Activation'), { target: { value: 'SYSTEMD_RELOAD' } })
    fireEvent.change(input('Systemd unit'), { target: { value: 'nginx.service' } })
    fireEvent.click(screen.getByRole('button', { name: 'Deploy previewed version' }))
    expect(await screen.findByText('Succeeded')).toBeTruthy()
    expect(requests.find(item => item.path === '/configuration-assignments/a1/deployment-preview')?.body)
      .toEqual({ expectedAssignmentVersion: 3, connectionId: 'ssh-source' })
    expect(requests.find(item => item.path === '/configuration-assignments/a1/deployments')?.body)
      .toMatchObject({ expectedAssignmentVersion: 3, connectionId: 'ssh-source',
        expectedRemoteSha256: 'a'.repeat(64), expectedRemoteMissing: false,
        execution: { activation: 'SYSTEMD_RELOAD', unitName: 'nginx.service', validator: null, newFileMode: 420 } })
  })

  it('rolls out a new version: compatibility, promotion, remote preflight, then the approved hashes', async () => {
    const { requests } = backend({ assignments: [assignment()] })
    renderApp('/organizations/org/configurations/p1?tab=targets')
    const start = await screen.findByRole('button', { name: 'Start rollout' })
    expect(start.hasAttribute('disabled')).toBe(true)
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select prod-vps-01' }))
    // The node still pins v1: its desired state must be promoted before anything is checked remotely.
    expect(screen.getByRole('button', { name: 'Check remote files' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Check compatibility' }))
    expect(await screen.findByText('All selected assignments are compatible.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Promote desired versions' }))
    const preflight = screen.getByRole('button', { name: 'Check remote files' })
    await waitFor(() => expect(preflight.hasAttribute('disabled')).toBe(false))
    fireEvent.click(preflight)
    expect(await screen.findByText(/prod-vps-01: Ready/)).toBeTruthy()
    expect(screen.getByText(/\+6\/-2/)).toBeTruthy()
    fireEvent.change(input('Canary nodes'), { target: { value: '1' } })
    fireEvent.click(start)
    expect(await screen.findByText('Rollout status')).toBeTruthy()

    expect(requests.find(item => item.path === '/configuration-profiles/p1/assignment-promotions')?.body)
      .toEqual({ revisionNumber: 2, assignments: [{ assignmentId: 'a1', expectedVersion: 3 }] })
    expect(requests.find(item => item.path === '/configuration-rollouts/preflight')?.body).toEqual({ targets: [
      { assignmentId: 'a1', expectedVersion: 4, connectionId: 'ssh-source',
        execution: { activation: 'NONE', unitName: null, validator: null, newFileMode: 420 } }] })
    const created = requests.find(item => item.method === 'POST' && item.path === '/configuration-rollouts')?.body
    expect(created).toMatchObject({ profileId: 'p1', revisionNumber: 2, canaryCount: 1, batchSize: 1, pauseSeconds: 0,
      stopOnFailure: true, rollbackMode: 'FAILED_TARGET_ONLY', targets: [{ assignmentId: 'a1', expectedVersion: 4,
        connectionId: 'ssh-source', connectionUpdatedAt: '2026-09-28T10:00:00Z', desiredSha256: 'b'.repeat(64),
        expectedRemoteSha256: 'a'.repeat(64), expectedRemoteMissing: false }] })
    expect((created as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('blocks promotion while an old override is incompatible with the target version', async () => {
    const { requests } = backend({ assignments: [assignment()], incompatible: true })
    renderApp('/organizations/org/configurations/p1?tab=targets')
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select prod-vps-01' }))
    fireEvent.click(screen.getByRole('button', { name: 'Check compatibility' }))
    expect(await screen.findByText('Resolve incompatible values before continuing.')).toBeTruthy()
    expect(screen.getByText(/INCOMPATIBLE_OVERRIDE legacy/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Promote desired versions' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Check remote files' }).hasAttribute('disabled')).toBe(true)
    expect(requests.some(item => item.path === '/configuration-profiles/p1/assignment-promotions')).toBe(false)
  })

  it('lists the rollouts of the profile with a bounded, profile-scoped page', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configurations/p1?tab=rollouts')
    const table = (await screen.findByText('Succeeded')).closest('table')!
    expect(within(table).getByText('v2')).toBeTruthy()
    expect(requests.filter(item => item.path.startsWith('/configuration-rollouts')).map(item => item.path))
      .toEqual(['/configuration-rollouts?limit=50&profileId=p1'])
  })

  it('removes an assignment only after a confirmation that says the server file is untouched', async () => {
    const { requests } = backend()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderApp('/organizations/org/configurations/p1?tab=targets')
    fireEvent.click(await screen.findByRole('button', { name: 'Remove assignment /etc/nginx/nginx.conf' }))
    expect(confirm).toHaveBeenLastCalledWith('Remove configuration assignment?\n\nThis removes the desired configuration from InfraDesk. ' +
      'It does not delete or change the file on the server.')
    expect(requests.some(item => item.method === 'DELETE')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Remove assignment /etc/nginx/nginx.conf' }))
    await waitFor(() => expect(requests.find(item => item.method === 'DELETE')?.path).toBe('/configuration-assignments/a1?expectedVersion=3'))
    await waitFor(() => expect(screen.queryByRole('link', { name: 'prod-vps-01' })).toBeNull())
  })

  it('keeps an assignment of an archived profile, and warns about an inactive target', async () => {
    const archived = assignment({}, { active: false })
    archived.profile = { ...archived.profile, archived: true }
    backend({ profile: profile({ archived: true }), assignments: [archived], detail: detail(archived) })
    renderApp('/organizations/org/configurations/p1?tab=targets')
    const row = (await screen.findByRole('link', { name: 'prod-vps-01' })).closest('tr')!
    expect(within(row).getByText('Profile archived')).toBeTruthy()
    expect(within(row).getByText('Target inactive')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Assign to resource' })).toBeNull()
    cleanup()

    backend({ profile: profile({ archived: true }), assignments: [archived], detail: detail(archived) })
    renderApp('/organizations/org/configuration-assignments/a1')
    expect(await screen.findByText('Target inactive. The desired configuration is kept, but the node is not active.')).toBeTruthy()
    expect(screen.getByText('Profile archived. The pinned version still exists and stays assigned.')).toBeTruthy()
    expect(select('Version').value).toBe('1')
  })

  it('shows the configurations of a node on its page, and only to those who manage configurations', async () => {
    const { requests } = backend({ assignments: [assignment()] })
    renderApp('/organizations/org/environments/env/resources/node?tab=configurations')
    const row = (await screen.findByRole('link', { name: 'Nginx main' })).closest('tr')!
    expect(within(row).getByText('/etc/nginx/nginx.conf')).toBeTruthy()
    expect(screen.getAllByRole('columnheader').map(cell => cell.textContent))
      .toEqual(['Configuration', 'Target path', 'Desired version', 'Status', 'Actions'])
    expect(screen.getByRole('link', { name: 'Assign configuration' }).getAttribute('href'))
      .toBe('/organizations/org/configuration-assignments/new?resourceId=node')
    expect(requests.filter(item => item.path.startsWith('/configuration-assignments')).map(item => item.path))
      .toEqual(['/configuration-assignments?limit=50&resourceId=node'])
    cleanup()

    const member = backend()
    renderApp('/organizations/org/environments/env/resources/node', { role: 'MEMBER' })
    await screen.findByRole('tab', { name: 'Overview' })
    expect(screen.queryByRole('tab', { name: 'Configurations' })).toBeNull()
    expect(member.requests.some(item => item.path.startsWith('/configuration-assignments'))).toBe(false)
    cleanup()

    // Hidden navigation is not the security: the editor itself refuses a member, and asks for nothing.
    const refused = backend()
    renderApp('/organizations/org/configuration-assignments/a1', { role: 'MEMBER' })
    expect(screen.getByText('You do not have permission to manage configurations.')).toBeTruthy()
    expect(refused.requests.some(item => item.path.startsWith('/configuration-assignments'))).toBe(false)
  })

  it('speaks Russian', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=targets', { locale: 'ru' })
    expect(await screen.findByRole('tab', { name: 'Цели' })).toBeTruthy()
    const row = (await screen.findByRole('link', { name: 'prod-vps-01' })).closest('tr')!
    expect(within(row).getByText('Назначена')).toBeTruthy()
    expect(within(row).getByText('Доступна новая версия')).toBeTruthy()
    expect(screen.getByText(/InfraDesk ещё не применял эту конфигурацию на сервере\./)).toBeTruthy()
    cleanup()
    backend()
    renderApp('/organizations/org/configuration-assignments/a1', { locale: 'ru' })
    expect(await screen.findByRole('button', { name: 'Сохранить назначение' })).toBeTruthy()
    expect(screen.getByRole('note').textContent)
      .toBe('Переменные конфигурации — не хранилище секретов. Не указывайте здесь пароли, токены и закрытые ключи.')
  })
})
