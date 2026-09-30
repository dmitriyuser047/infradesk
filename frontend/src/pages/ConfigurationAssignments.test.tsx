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
  return { id: 'p1', kind: 'FILE_TEMPLATE', code: 'nginx-main', name: 'Nginx main', description: null, archived: false, latestRevisionNumber: 2,
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
  deploymentState?: string
  rolloutFailed?: boolean
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
      assignmentId: 'a1', profileId: 'p1', resourceId: 'node',
      assignmentVersion: state.detail.version, profileRevisionNumber: 1, targetPath: state.detail.targetPath,
      remote: { exists: true, sha256: 'a'.repeat(64), text: true }, desired: { sha256: 'b'.repeat(64) }, changed: true,
      atomicReplaceSupported: true,
      diff: { text: '--- remote\n+++ desired\n@@ -1,1 +1,1 @@\n-old <b>bold</b>\n+new\n', truncated: false, approximate: false,
        addedLines: 1, removedLines: 1 },
      connection: { id: 'ssh-source', name: 'Production SSH', updatedAt: '2026-09-28T10:00:00Z' },
    })
    if (path === '/configuration-assignments/a1/deployments' && method === 'POST')
      return json({ deploymentId: 'deployment-1', state: 'QUEUED' }, 202)
    const deployment = {
      id: 'deployment-1', assignmentId: 'a1', assignmentVersion: state.detail.version, resource: { id: 'node', name: 'prod-vps-01' },
      profileId: 'p1', profileRevisionNumber: 1, targetPath: state.detail.targetPath,
      connection: { id: 'ssh-source', name: 'Production SSH' }, desiredSha256: 'b'.repeat(64),
      expectedRemoteSha256: 'a'.repeat(64), expectedRemoteMissing: false,
      execution: { activation: 'SYSTEMD_RELOAD', unitName: 'nginx.service', validator: null, newFileMode: 420 },
      state: state.deploymentState ?? 'SUCCEEDED', phase: 'CLEANUP', rollbackFromPhase: null,
      failureCode: state.deploymentState === 'ROLLBACK_FAILED' ? 'CONFIGURATION_HEALTH_CHECK_FAILED' : null,
      cancelRequested: false, backupRetained: false, actor: { id: 'user', name: 'Dmitriy' },
      createdAt: '2026-09-28T10:00:00Z', startedAt: '2026-09-28T10:00:01Z', finishedAt: '2026-09-28T10:00:02Z',
      rolloutId: null, retryOfDeploymentId: null,
    }
    if (path === '/configuration-deployments/deployment-1') return json({ ...deployment, events: [
      { sequence: 1, type: 'QUEUED', occurredAt: '2026-09-28T10:00:00Z' }] })
    if (path === '/configuration-deployments') return json({ items: [deployment], nextCursor: null })
    if (path === '/configuration-deployment-summaries') return json(url.searchParams.getAll('assignmentId').map(id => ({
      assignmentId: id,
      lastSuccessfulDeployment: id === 'a1' ? { deploymentId: 'd0', revision: 1, desiredSha256: 'c'.repeat(64),
        finishedAt: '2026-09-27T10:00:00Z' } : null,
      activeDeployment: null })))
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
        resourceName: 'prod-vps-01', currentRevisionNumber: 1, compatible: !state.incompatible,
        issues: state.incompatible ? [{ code: 'CONFIGURATION_INCOMPATIBLE_OVERRIDE', variableName: 'legacy' }] : [] })) })
    if (path === '/configuration-profiles/p1/assignment-promotions') return json({ revisionNumber: body.revisionNumber,
      assignments: body.assignments.map((item: { assignmentId: string; expectedVersion: number }) =>
        ({ assignmentId: item.assignmentId, version: item.expectedVersion + 1 })) })
    if (path === '/configuration-rollouts/preflight') return json({ ready: true, items: body.targets.map((target: {
      assignmentId: string; expectedVersion: number; connectionId: string }) => ({ ...target, connectionName: 'Production SSH',
      ready: true, desiredSha256: 'b'.repeat(64), connectionUpdatedAt: '2026-09-28T10:00:00Z',
      remote: { exists: true, sha256: 'a'.repeat(64) }, changed: true, addedLines: 6, removedLines: 2, errorCode: null })) })
    if (path === '/configuration-rollouts' && method === 'POST') return json({ rolloutId: 'r1', state: 'QUEUED' }, 202)
    const rollout = { id: 'r1', profileId: 'p1', profileRevisionNumber: 2, state: state.rolloutFailed ? 'FAILED' : 'SUCCEEDED',
      strategy: { canaryCount: 1, batchSize: 1, pauseSeconds: 0, stopOnFailure: true, rollbackMode: 'FAILED_TARGET_ONLY' },
      cancelRequested: false, rollbackRequested: false, actor: { id: 'user', name: 'Dmitriy' },
      counts: { items: 2, succeeded: state.rolloutFailed ? 1 : 2, failed: state.rolloutFailed ? 1 : 0 },
      createdAt: '2026-09-28T10:00:00Z', startedAt: '2026-09-28T10:00:01Z', finishedAt: '2026-09-28T10:02:00Z', nextActionAt: null }
    if (path === '/configuration-rollouts' && method === 'GET') return json({ items: [rollout], nextCursor: null })
    if (path === '/configuration-rollouts/r1') return json({ ...rollout, items: [
      { id: 'i1', position: 0, assignmentId: 'a1', assignmentVersion: 4, resource: { id: 'node', name: 'prod-vps-01' },
        targetPath: '/etc/nginx/nginx.conf', state: 'SUCCEEDED', deployment: { id: 'deployment-1', state: 'SUCCEEDED',
          phase: 'CLEANUP', failureCode: null, startedAt: null, finishedAt: null } },
      { id: 'i2', position: 1, assignmentId: 'a2', assignmentVersion: 2, resource: { id: 'other', name: 'prod-vps-02' },
        targetPath: '/etc/nginx/nginx.conf', state: state.rolloutFailed ? 'ROLLED_BACK' : 'SUCCEEDED',
        deployment: { id: 'deployment-2', state: state.rolloutFailed ? 'ROLLED_BACK' : 'SUCCEEDED', phase: 'ROLLBACK',
          failureCode: state.rolloutFailed ? 'CONFIGURATION_VALIDATION_FAILED' : null, startedAt: null, finishedAt: null } }] })
    if (path === '/environments/env/resources') return json([node, retired, container])
    if (path === '/resources/node') return json(node)
    if (path === '/resources/node/context') return json({ project: { id: 'project', name: 'App' },
      environment: { id: 'env', name: 'Production', kind: 'PROD' }, parentResource: null,
      sourceConnections: [{ id: 'ssh-source', name: 'Production SSH', connectorType: 'SSH', active: true }],
      children: [], activeChildCount: 0, openIncidentCount: 0 })
    if (path === '/resources/node/operations') return json({ operations: [], unavailableReason: null })
    if (path === '/resources/node/labels') return json({ version: 0, labels: [] })
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
      .toEqual(['Resource', 'Environment', 'Target path', 'Assigned version', 'Status', 'Deployment', 'Actions'])
    expect(await within(row).findByText('Last deployed desired version: v1')).toBeTruthy()
    expect(within(inactive).getByText('Not deployed by InfraDesk yet')).toBeTruthy()
    expect(screen.getByText(/does not detect later manual changes/)).toBeTruthy()
    // What InfraDesk deployed is history, never a claim that the server is in sync.
    expect(document.body.textContent).not.toMatch(/\b(Synced|In sync|Up to date)\b/)
    expect(screen.getByRole('link', { name: 'Assign to resource' }).getAttribute('href'))
      .toBe('/organizations/org/configuration-assignments/new?profileId=p1')
    expect(requests.filter(item => item.path.startsWith('/configuration-assignments')).map(item => item.path))
      .toEqual(['/configuration-assignments?limit=50&profileId=p1'])
    // One batched summary request for the whole page, never one per assignment.
    expect(requests.filter(item => item.path.startsWith('/configuration-deployment-summaries')).map(item => item.path))
      .toEqual(['/configuration-deployment-summaries?assignmentId=a1&assignmentId=a2'])
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
    expect(screen.getByRole('button', { name: 'Deploy this version' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(preview)
    expect(await screen.findByText('b'.repeat(64))).toBeTruthy()
    expect(screen.getByText('a'.repeat(64))).toBeTruthy()
    const diff = screen.getByLabelText('Changes from the server file to the desired file')
    expect(diff.tagName).toBe('PRE')
    // Remote content is text, never markup.
    expect(within(diff).getByText('-old <b>bold</b>').className).toContain('diff-removed')
    expect(diff.querySelector('b')).toBeNull()
    expect(within(diff).getByText('+new').className).toContain('diff-added')
    expect(screen.getByText('+1 / −1 lines')).toBeTruthy()
    fireEvent.change(select('Service action'), { target: { value: 'SYSTEMD_RELOAD' } })
    fireEvent.change(input('Systemd unit'), { target: { value: 'nginx.service; reboot' } })
    expect(screen.getByText('Enter a unit name ending in .service.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Deploy this version' }).hasAttribute('disabled')).toBe(true)
    fireEvent.change(input('Systemd unit'), { target: { value: 'nginx.service' } })
    fireEvent.change(input('Validator executable (optional)'), { target: { value: '/usr/sbin/nginx' } })
    fireEvent.change(screen.getByLabelText('Validator arguments, one per line'), { target: { value: '-t\n-c\n{candidate}' } })
    fireEvent.click(screen.getByRole('button', { name: 'Deploy this version' }))
    expect(await screen.findByText('Succeeded')).toBeTruthy()
    expect(screen.getByText('The file on the server is the desired version and the service is active.')).toBeTruthy()
    expect(requests.find(item => item.path === '/configuration-assignments/a1/deployment-preview')?.body)
      .toEqual({ expectedAssignmentVersion: 3, connectionId: 'ssh-source' })
    const created = requests.find(item => item.path === '/configuration-assignments/a1/deployments')?.body
    expect(created).toMatchObject({ expectedAssignmentVersion: 3, connectionId: 'ssh-source',
      expectedRemoteSha256: 'a'.repeat(64), expectedRemoteMissing: false, retryOfDeploymentId: null,
      execution: { activation: 'SYSTEMD_RELOAD', unitName: 'nginx.service',
        validator: { executable: '/usr/sbin/nginx', args: ['-t', '-c', '{candidate}'] }, newFileMode: 420 } })
    expect((created as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('shows a failed rollback as an emergency, and a retry starts from a new preview', async () => {
    const { requests } = backend({ deploymentState: 'ROLLBACK_FAILED' })
    renderApp('/organizations/org/configuration-assignments/a1')
    const preview = await screen.findByRole('button', { name: 'Preview remote changes' })
    await waitFor(() => expect(preview.hasAttribute('disabled')).toBe(false))
    fireEvent.click(preview)
    fireEvent.click(await screen.findByRole('button', { name: 'Deploy this version' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Rollback failed')
    expect(alert.textContent).toContain('Inspect the server now.')
    expect(alert.textContent).toContain('The service was not active after the change.')
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    // The old precondition is never reused: a new preview is required before deploying again.
    expect(await screen.findByText('A retry is a new deployment: preview the server again first.')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Deploy this version' }).hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Preview remote changes' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Deploy this version' }))
    await waitFor(() => expect(requests.filter(item => item.path === '/configuration-assignments/a1/deployments')).toHaveLength(2))
    expect(requests.filter(item => item.path === '/configuration-assignments/a1/deployment-preview')).toHaveLength(2)
    expect(requests.filter(item => item.path === '/configuration-assignments/a1/deployments')[1].body)
      .toMatchObject({ retryOfDeploymentId: 'deployment-1' })
  })

  it('hides deployment from a member, and the editor refuses the member anyway', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=targets', { role: 'MEMBER' })
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Start rollout' })).toBeNull())
    expect(screen.queryByRole('tab', { name: 'Rollouts' })).toBeNull()
  })

  it('rolls out a new version step by step: nodes, compatibility, promotion, server check, strategy', async () => {
    const { requests } = backend({ assignments: [assignment()] })
    renderApp('/organizations/org/configurations/p1?tab=targets')
    const next = () => screen.getByRole('button', { name: 'Next' })
    expect(await screen.findByText('Step 1 of 5')).toBeTruthy()
    expect(next().hasAttribute('disabled')).toBe(true)
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select prod-vps-01' }))
    await waitFor(() => expect(next().hasAttribute('disabled')).toBe(false))
    fireEvent.click(next())
    // The node still pins v1: compatibility must be checked before the desired state moves.
    expect(next().hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Check compatibility' }))
    expect(await screen.findByText('Ready')).toBeTruthy()
    fireEvent.click(next())
    expect(screen.getByText('1 assignments → v2')).toBeTruthy()
    expect(next().hasAttribute('disabled')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Promote desired state' }))
    await waitFor(() => expect(next().hasAttribute('disabled')).toBe(false))
    fireEvent.click(next())
    fireEvent.click(screen.getByRole('button', { name: 'Check servers' }))
    const matrix = (await screen.findByText('+6 / −2 lines')).closest('table')!
    expect(within(matrix).getByText('Ready')).toBeTruthy()
    expect(within(matrix).getByText('Production SSH')).toBeTruthy()
    fireEvent.click(next())
    fireEvent.change(input('Canary nodes'), { target: { value: '2' } })
    expect(screen.getByText(/Canary 0–20 and not more than the nodes/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Start rollout' }).hasAttribute('disabled')).toBe(true)
    fireEvent.change(input('Canary nodes'), { target: { value: '1' } })
    fireEvent.change(input('Pause between batches, seconds'), { target: { value: '30' } })
    fireEvent.change(select('On failure, roll back'), { target: { value: 'ALL_APPLIED' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start rollout' }))
    expect(await screen.findByText('2 / 2 succeeded')).toBeTruthy()
    expect(screen.getByRole('region', { name: 'Canary' })).toBeTruthy()

    expect(requests.find(item => item.path === '/configuration-profiles/p1/assignment-promotions')?.body)
      .toEqual({ revisionNumber: 2, assignments: [{ assignmentId: 'a1', expectedVersion: 3 }] })
    expect(requests.find(item => item.path === '/configuration-rollouts/preflight')?.body).toEqual({ profileId: 'p1',
      revisionNumber: 2, targets: [{ assignmentId: 'a1', expectedVersion: 4, connectionId: 'ssh-source',
        execution: { activation: 'NONE', unitName: null, validator: null, newFileMode: 420 } }] })
    const created = requests.find(item => item.method === 'POST' && item.path === '/configuration-rollouts')?.body
    expect(created).toMatchObject({ profileId: 'p1', revisionNumber: 2,
      strategy: { canaryCount: 1, batchSize: 1, pauseSeconds: 30, stopOnFailure: true, rollbackMode: 'ALL_APPLIED' },
      targets: [{ assignmentId: 'a1', expectedVersion: 4, connectionId: 'ssh-source',
        connectionUpdatedAt: '2026-09-28T10:00:00Z', desiredSha256: 'b'.repeat(64),
        expectedRemoteSha256: 'a'.repeat(64), expectedRemoteMissing: false }] })
    expect((created as { requestId: string }).requestId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('blocks promotion while an old override is incompatible with the target version', async () => {
    const { requests } = backend({ assignments: [assignment()], incompatible: true })
    renderApp('/organizations/org/configurations/p1?tab=targets')
    fireEvent.click(await screen.findByRole('checkbox', { name: 'Select prod-vps-01' }))
    const next = screen.getByRole('button', { name: 'Next' })
    await waitFor(() => expect(next.hasAttribute('disabled')).toBe(false))
    fireEvent.click(next)
    fireEvent.click(screen.getByRole('button', { name: 'Check compatibility' }))
    expect(await screen.findByText('Value for a removed variable: legacy')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Next' }).hasAttribute('disabled')).toBe(true)
    expect(requests.some(item => item.path === '/configuration-profiles/p1/assignment-promotions')).toBe(false)
  })

  it('lists rollouts by page and shows why a node failed and that it was rolled back', async () => {
    const { requests } = backend({ rolloutFailed: true })
    renderApp('/organizations/org/configurations/p1?tab=rollouts')
    const table = (await screen.findByText('2 nodes')).closest('table')!
    const row = within(table).getByText('v2').closest('tr')!
    expect(within(row).getByText('Failed')).toBeTruthy()
    expect(within(row).getByText('Dmitriy')).toBeTruthy()
    fireEvent.click(within(row).getByRole('button', { name: 'Details' }))
    expect(await screen.findByText('1 / 2 succeeded')).toBeTruthy()
    expect(screen.getByText('The validator rejected the new file. The server file was not changed.')).toBeTruthy()
    expect(screen.getAllByText('Rolled back').length).toBeGreaterThan(0)
    expect(requests.filter(item => item.path.startsWith('/configuration-rollouts?')).map(item => item.path))
      .toEqual(['/configuration-rollouts?limit=25&profileId=p1'])
  })

  it('lists deployments with node, version, state and author', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=deployments')
    const row = (await screen.findByText('/etc/nginx/nginx.conf')).closest('tr')!
    expect(within(row).getByText('prod-vps-01')).toBeTruthy()
    expect(within(row).getByText('Succeeded')).toBeTruthy()
    expect(within(row).getByText('Dmitriy')).toBeTruthy()
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
      .toEqual(['Configuration', 'Target path', 'Desired version', 'Status', 'Deployment', 'Actions'])
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
    expect(await within(row).findByText('Последняя развёрнутая желаемая версия: v1')).toBeTruthy()
    expect(screen.getByText(/не отслеживает последующие ручные изменения/)).toBeTruthy()
    cleanup()
    backend()
    renderApp('/organizations/org/configuration-assignments/a1', { locale: 'ru' })
    expect(await screen.findByRole('button', { name: 'Сохранить назначение' })).toBeTruthy()
    expect(screen.getByRole('note').textContent)
      .toBe('Переменные конфигурации — не хранилище секретов. Не указывайте здесь пароли, токены и закрытые ключи.')
  })
})
