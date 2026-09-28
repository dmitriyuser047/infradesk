// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { ConfigurationProfile, ConfigurationRevision } from '../types/configuration'
import type { ConfigurationAssignment } from '../types/configurationAssignment'
import type { ConfigurationRule, RuleTarget } from '../types/configurationRule'
import type { ResourceResponse } from '../types/resource'
import { ConfigurationAssignmentEditPage } from './ConfigurationAssignmentPages'
import { ConfigurationProfilePage } from './ConfigurationProfilePage'
import { ConfigurationRulePage } from './ConfigurationRulePage'
import { ResourcePage } from './ResourcePage'

const owner = { id: 'user', displayName: 'Dmitriy' }
const v1: ConfigurationRevision = { revisionNumber: 1, template: 'server {{ domain }}', createdAt: '2026-09-21T10:00:00Z', createdBy: owner,
  variables: [{ name: 'domain', type: 'STRING', required: true, defaultValue: 'vpn.example', description: null }] }
const v2: ConfigurationRevision = { revisionNumber: 2, template: 'server {{ domain }} {{ server_id }}', createdAt: '2026-09-22T10:00:00Z', createdBy: owner,
  variables: [{ name: 'domain', type: 'STRING', required: true, defaultValue: 'vpn.example', description: null },
    { name: 'server_id', type: 'STRING', required: true, defaultValue: null, description: null }] }
const profile: ConfigurationProfile = { id: 'p1', code: 'xray-default', name: 'Xray Default', description: null, archived: false,
  latestRevisionNumber: 2, latestRevisionCreatedAt: v2.createdAt, createdAt: v1.createdAt, updatedAt: v2.createdAt }

function rule(overrides: Partial<ConfigurationRule> = {}): ConfigurationRule {
  return { id: 'r1', code: 'vpn-production', name: 'VPN Production', description: null,
    profile: { id: 'p1', code: 'xray-default', name: 'Xray Default', archived: false, latestRevisionNumber: 2 },
    profileRevisionNumber: 1, targetPath: '/etc/xray/config.json',
    selector: { projects: [], environments: ['env'], requiredLabels: [{ key: 'role', value: 'vpn' }], excludedLabels: [] },
    enabled: true, archived: false, version: 4, counts: { matched: 4, managed: 2, issues: 2, excluded: 1 },
    createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:00:00Z',
    lastReconciledAt: '2026-09-28T15:10:00Z', nextReconcileAt: '2026-09-28T15:11:00Z', ...overrides }
}

const place = { project: { id: 'project', name: 'VPN' }, environment: { id: 'env', name: 'Production' } }
function target(id: string, name: string, status: RuleTarget['status'], extra: Partial<RuleTarget> = {}): RuleTarget {
  return { resource: { id, name, code: id, active: true }, ...place, matchesSelector: status !== 'NO_LONGER_MATCHING',
    excluded: status === 'EXCLUDED', assignment: null, status, issue: null, ...extra }
}
const managed = (id: string, version: number) => ({ id, version, revision: 1, managedByRule: true, sourceRuleId: 'r1' })
const targets: RuleTarget[] = [
  target('fi', 'Finland', 'ASSIGNED', { assignment: managed('a-fi', 2) }),
  target('fr', 'France', 'TARGET_PATH_CONFLICT', { assignment: { id: 'm-fr', version: 5, revision: 1, managedByRule: false, sourceRuleId: null },
    issue: { code: 'TARGET_PATH_CONFLICT', variableName: null, conflictingAssignmentId: 'm-fr' } }),
  target('de', 'Germany', 'NO_LONGER_MATCHING', { assignment: managed('a-de', 3) }),
  target('nl', 'Netherlands', 'EXCLUDED'),
  target('se', 'Sweden', 'NEEDS_VALUES', { issue: { code: 'NEEDS_VALUES', variableName: 'server_id', conflictingAssignmentId: null } }),
]

function assignment(overrides: Partial<ConfigurationAssignment> = {}): ConfigurationAssignment {
  return { id: 'a-fi', version: 2, targetPath: '/etc/xray/config.json', profileRevisionNumber: 1, removedAt: null,
    createdAt: '2026-09-23T10:00:00Z', updatedAt: '2026-09-23T10:00:00Z',
    resource: { id: 'node', name: 'Finland', code: 'fi', resourceTypeCode: 'NODE', active: true,
      project: { id: 'project', name: 'VPN' }, environment: { id: 'env', name: 'Production', kind: 'PROD' } },
    profile: { id: 'p1', code: 'xray-default', name: 'Xray Default', archived: false, latestRevisionNumber: 2 },
    rule: { id: 'r1', code: 'vpn-production', name: 'VPN Production' }, ...overrides }
}

const node = { id: 'node', organizationId: 'org', environmentId: 'env', resourceTypeId: 'type', parentResourceId: null, createdAt: '',
  updatedAt: '', code: 'fi', name: 'Finland', resourceTypeCode: 'NODE', active: true,
  data: { kind: 'NODE', spec: null, status: { online: true, cpuUsagePercent: 4, memoryUsagePercent: 30, uptimeSeconds: 100 } } } as ResourceResponse

interface State {
  rule: ConfigurationRule
  labels: { version: number; labels: { key: string; value: string }[] }
  labelsStaleOnce?: boolean
  ruleStaleOnce?: boolean
  incompatible?: boolean
}

/** An in-memory backend for the rule endpoints and what the pages around them read. */
function backend(initial: Partial<State> = {}) {
  const state: State = { rule: rule(), labels: { version: 2, labels: [{ key: 'role', value: 'vpn' }] }, ...initial }
  const requests: { method: string; path: string; body?: unknown }[] = []
  const json = (body: unknown, status = 200) => Promise.resolve(new Response(status === 204 ? null : JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace('/api/v1/organizations/org', '')
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    requests.push({ method, path: path + url.search, body })
    const rules = '/configuration-assignment-rules'
    if (path === '/configuration-profiles/p1') return json({ profile, latestRevision: v2 })
    if (path === '/configuration-profiles/p1/revisions') return json([v2, v1].map(item => ({ revisionNumber: item.revisionNumber,
      variableCount: item.variables.length, createdBy: owner, createdAt: item.createdAt })))
    const revision = path.match(/^\/configuration-profiles\/p1\/revisions\/(\d+)$/)
    if (revision) return json(Number(revision[1]) === 1 ? v1 : v2)
    if (path === rules && method === 'GET') return json({ items: [state.rule], nextCursor: null })
    if (path === rules && method === 'POST') return json(rule({ ...body, id: 'r2', version: 1 }), 201)
    if (path === `${rules}/selector-preview`) return json({ matchedCount: 3, eligibleCount: 2, needsValuesCount: 0, conflictCount: 1,
      manualConflictCount: 1, ruleConflictCount: 0, missingVariable: null, items: [
        { resourceId: 'fi', resourceName: 'Finland', environmentName: 'Production', projectName: 'VPN', state: 'ELIGIBLE', assignmentId: null },
        { resourceId: 'fr', resourceName: 'France', environmentName: 'Production', projectName: 'VPN', state: 'TARGET_PATH_CONFLICT', assignmentId: 'm-fr' },
      ] })
    if (path === `${rules}/r1` && method === 'GET') return json(state.rule)
    if (path === `${rules}/r1` && method === 'PATCH') return json({ ...state.rule, ...body, version: state.rule.version + 1 })
    const lifecycle = path.match(/^\/configuration-assignment-rules\/r1\/(enable|disable)$/)
    if (lifecycle || (path === `${rules}/r1` && method === 'DELETE')) {
      if (state.ruleStaleOnce) {
        state.ruleStaleOnce = false
        state.rule = { ...state.rule, version: state.rule.version + 1 }
        return json({ code: 'CONFIGURATION_RULE_CHANGED', message: 'x' }, 409)
      }
      state.rule = { ...state.rule, version: state.rule.version + 1, enabled: lifecycle?.[1] === 'enable',
        archived: method === 'DELETE' }
      return json(state.rule)
    }
    if (path === `${rules}/r1/reconcile`) return json({ scheduled: true }, 202)
    if (path === `${rules}/r1/targets`) {
      const after = url.searchParams.get('afterId')
      return after === null ? json({ items: targets.slice(0, 3), nextCursor: { afterName: 'Germany', afterId: 'de' } })
        : json({ items: targets.slice(3), nextCursor: null })
    }
    if (path.startsWith(`${rules}/r1/exclude/`)) return json(null, 204)
    if (path.match(/^\/configuration-assignment-rules\/r1\/assignments\/[^/]+\/(detach|adopt|exclude-and-remove)$/)) return json(null, 204)
    if (path === `${rules}/r1/targets/se/assignment`) return json({ assignmentId: 'a-se' }, 201)
    if (path === `${rules}/r1/promotion-preview`) return json({ ruleVersion: state.rule.version, fromRevision: 1,
      targetRevision: body.targetRevisionNumber, compatible: !state.incompatible, assignmentCount: 2, items: [
        { assignmentId: 'a-de', expectedVersion: 3, resourceName: 'Germany', compatible: true, issues: [] },
        { assignmentId: 'a-fi', expectedVersion: 2, resourceName: 'Finland', compatible: !state.incompatible,
          issues: state.incompatible ? [{ code: 'CONFIGURATION_VALUE_MISSING', variableName: 'server_id' }] : [] }] })
    if (path === `${rules}/r1/promote`) {
      state.rule = { ...state.rule, profileRevisionNumber: body.targetRevisionNumber, version: state.rule.version + 1 }
      return json({ revisionNumber: body.targetRevisionNumber, assignments: body.assignments.map(
        (item: { assignmentId: string; expectedVersion: number }) => ({ assignmentId: item.assignmentId, version: item.expectedVersion + 1 })) })
    }
    if (path === '/configuration-deployment-summaries') return json(url.searchParams.getAll('assignmentId').map(id => ({
      assignmentId: id, activeDeployment: null,
      lastSuccessfulDeployment: id === 'a-fi' ? { deploymentId: 'd1', revision: 1, desiredSha256: 'a'.repeat(64), finishedAt: '2026-09-27T10:00:00Z' }
        : { deploymentId: 'd2', revision: 0, desiredSha256: 'b'.repeat(64), finishedAt: '2026-09-27T10:00:00Z' } })))
    if (path === '/configuration-assignments' && method === 'GET') return json([assignment(),
      assignment({ id: 'a-man', targetPath: '/etc/other.conf', rule: null })])
    if (path === '/configuration-assignments/a-fi' && method === 'GET') return json({ ...assignment(),
      revision: { revisionNumber: 1, variables: v1.variables, createdBy: owner, createdAt: v1.createdAt }, values: [] })
    if (path === '/resources/node/labels' && method === 'GET') return json(state.labels)
    if (path === '/resources/node/labels' && method === 'PUT') {
      if (state.labelsStaleOnce) {
        state.labelsStaleOnce = false
        state.labels = { ...state.labels, version: state.labels.version + 1 }
        return json({ code: 'RESOURCE_LABELS_CHANGED', message: 'x' }, 409)
      }
      state.labels = { version: state.labels.version + 1, labels: body.labels }
      return json(state.labels)
    }
    if (path === '/resources/node') return json(node)
    if (path === '/resources/node/context') return json({ project: { id: 'project', name: 'VPN' },
      environment: { id: 'env', name: 'Production', kind: 'PROD' }, parentResource: null, sourceConnections: [],
      children: [], activeChildCount: 0, openIncidentCount: 0 })
    if (path === '/resources/node/operations') return json({ operations: [], unavailableReason: null })
    if (path.startsWith('/resources/node/')) return json([])
    if (path === '/configuration-rollouts' || path === '/configuration-deployments') return json({ items: [], nextCursor: null })
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
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'vpn', name: 'VPN', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [
    { id: 'env', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' },
    { id: 'stage', organizationId: 'org', projectId: 'project', code: 'stage', name: 'Staging', kind: 'STAGE' }])
  return render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/organizations/:organizationId/configurations/:profileId" element={<ConfigurationProfilePage />} />
      <Route path="/organizations/:organizationId/configuration-rules/:ruleId" element={<ConfigurationRulePage />} />
      <Route path="/organizations/:organizationId/configuration-assignments/:assignmentId" element={<ConfigurationAssignmentEditPage />} />
      <Route path="/organizations/:organizationId/environments/:environmentId/resources/:resourceId" element={<ResourcePage />} />
    </Routes><Where /></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

const location = () => screen.getByTestId('location').textContent
const input = (label: string) => screen.getByLabelText(label) as HTMLInputElement
const rowOf = (name: string) => screen.getByRole('link', { name }).closest('tr')!

describe('configuration automation rules', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('lists the rules of a profile on its Automation tab, with a newer revision offered but never adopted', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=automation')
    const tab = await screen.findByRole('tab', { name: 'Automation' })
    expect(tab.getAttribute('aria-selected')).toBe('true')
    const row = (await screen.findByRole('link', { name: 'VPN Production' })).closest('tr')!
    expect(within(row).getByText('Enabled')).toBeTruthy()
    expect(within(row).getByText('v1')).toBeTruthy()
    expect(within(row).getByText('New revision v2 available')).toBeTruthy()
    expect(within(row).getByText('Production · role=vpn')).toBeTruthy()
    expect(within(row).getByText('2 managed')).toBeTruthy()
    expect(within(row).getByText('2 blocked')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'VPN Production' }).getAttribute('href')).toBe('/organizations/org/configuration-rules/r1')
  })

  it('creates a rule from a structured selector, previewing its targets before it may be enabled', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configurations/p1?tab=automation')
    fireEvent.click(await screen.findByRole('button', { name: 'Create rule' }))
    expect(screen.getByText('A rule creates desired assignments only. Deploying them stays a separate, explicit rollout.')).toBeTruthy()
    fireEvent.change(input('Name'), { target: { value: 'VPN Production' } })
    fireEvent.change(input('Code'), { target: { value: 'Vpn production' } })
    fireEvent.change(input('Target path'), { target: { value: '/etc/xray/config.json' } })
    fireEvent.click(screen.getByRole('checkbox', { name: 'Production' }))
    const required = screen.getByRole('group', { name: 'Required labels' })
    fireEvent.click(within(required).getByRole('button', { name: 'Add label' }))
    fireEvent.change(input('Required labels: key 1'), { target: { value: 'Role' } })
    fireEvent.change(input('Required labels: value 1'), { target: { value: 'vpn' } })
    // An empty row is left out, never sent as a label.
    fireEvent.click(within(screen.getByRole('group', { name: 'Excluded labels' })).getByRole('button', { name: 'Add label' }))

    expect((screen.getByRole('button', { name: 'Create and enable' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Preview targets' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Use 3–64 lowercase letters')
    expect(requests.some(item => item.path.includes('selector-preview'))).toBe(false)
    fireEvent.change(input('Code'), { target: { value: 'vpn-production' } })

    fireEvent.click(screen.getByRole('button', { name: 'Preview targets' }))
    const france = (await screen.findByText('France')).closest('tr')!
    expect(within(france).getByText('Path already assigned manually')).toBeTruthy()
    expect(screen.getByText('Will be assigned', { selector: 'dt' })).toBeTruthy()
    const selector = { projects: [], environments: ['env'], requiredLabels: [{ key: 'role', value: 'vpn' }], excludedLabels: [] }
    expect(requests.find(item => item.path.endsWith('selector-preview'))?.body)
      .toEqual({ profileId: 'p1', revisionNumber: 2, targetPath: '/etc/xray/config.json', selector })

    fireEvent.click(screen.getByRole('button', { name: 'Create and enable' }))
    await waitFor(() => expect(location()).toBe('/organizations/org/configuration-rules/r2'))
    expect(requests.find(item => item.method === 'POST' && item.path === '/configuration-assignment-rules')?.body).toEqual({
      code: 'vpn-production', name: 'VPN Production', description: null, profileId: 'p1', profileRevisionNumber: 2,
      targetPath: '/etc/xray/config.json', selector, enabled: true })
  })

  it('shows a rule with its reconciliation state, and only schedules a reconcile when asked', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-rules/r1')
    expect(await screen.findByRole('heading', { name: 'VPN Production' })).toBeTruthy()
    expect(screen.getByText('Disabling a rule does not remove existing assignments and does not modify remote servers.')).toBeTruthy()
    expect(screen.getByText('2 targets require attention')).toBeTruthy()
    expect(screen.getByText('/etc/xray/config.json')).toBeTruthy()
    // Not drift detection: nothing here claims the servers are in sync.
    expect(document.body.textContent).not.toMatch(/\b(Synced|In sync)\b/)
    fireEvent.click(screen.getByRole('button', { name: 'Reconcile now' }))
    expect(await screen.findByText('Reconciliation scheduled.')).toBeTruthy()
    expect(requests.find(item => item.path.endsWith('/reconcile'))?.method).toBe('POST')
  })

  it('disables and archives at the version seen, and reports a concurrent change instead of overwriting it', async () => {
    const { requests } = backend({ ruleStaleOnce: true })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderApp('/organizations/org/configuration-rules/r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Disable' }))
    expect(await screen.findByText('Someone else changed this rule. Reload it and make your change again.')).toBeTruthy()
    expect(requests.find(item => item.path.endsWith('/disable'))?.body).toEqual({ expectedVersion: 4 })
    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    await waitFor(() => expect(screen.queryByText(/Someone else changed this rule/)).toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Disable' }))
    expect(await screen.findByRole('button', { name: 'Enable automation' })).toBeTruthy()
    expect(requests.filter(item => item.path.endsWith('/disable')).map(item => item.body)).toEqual([{ expectedVersion: 4 }, { expectedVersion: 5 }])

    fireEvent.click(screen.getByRole('button', { name: 'Archive rule' }))
    expect(await screen.findByText('This rule is archived and read-only. Its assignments are kept.')).toBeTruthy()
    expect(requests.find(item => item.method === 'DELETE')?.path).toBe('/configuration-assignment-rules/r1?expectedVersion=6')
  })

  it('blocks an incompatible promotion and names why', async () => {
    const { requests } = backend({ incompatible: true })
    renderApp('/organizations/org/configuration-rules/r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Preview promotion' }))
    expect(await screen.findByText(/Some managed assignments are not compatible/)).toBeTruthy()
    expect(screen.getByText('Missing variable: server_id')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Promote rule to v2' }) as HTMLButtonElement).disabled).toBe(true)
    expect(requests.find(item => item.path.endsWith('promotion-preview'))?.body).toEqual({ targetRevisionNumber: 2, expectedRuleVersion: 4 })
    expect(requests.some(item => item.path.endsWith('/promote'))).toBe(false)
  })

  it('promotes the rule and every managed assignment together, then offers the existing rollout', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-rules/r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Preview promotion' }))
    expect(await screen.findByText('All 2 managed assignments are compatible.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Promote rule to v2' }))
    expect(await screen.findByText('Rule promoted to v2. 2 nodes require deployment.')).toBeTruthy()
    expect(requests.find(item => item.path.endsWith('/promote'))?.body).toEqual({ targetRevisionNumber: 2, expectedRuleVersion: 4,
      assignments: [{ assignmentId: 'a-de', expectedVersion: 3 }, { assignmentId: 'a-fi', expectedVersion: 2 }] })
    // Promotion changes desired state only; deploying is the Stage 22D rollout, opened with the rule's nodes.
    expect(requests.some(item => item.path.startsWith('/configuration-rollouts') && item.method === 'POST')).toBe(false)
    expect(screen.getByRole('link', { name: 'Roll out v2' }).getAttribute('href'))
      .toBe('/organizations/org/configurations/p1?tab=targets&rolloutRule=r1&rolloutRevision=2')
  })

  it('lists targets a page at a time, each with how it stands and what can be done about it', async () => {
    const { requests } = backend()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderApp('/organizations/org/configuration-rules/r1')
    const finland = (await screen.findByRole('link', { name: 'Finland' })).closest('tr')!
    expect(within(finland).getByText('Assigned')).toBeTruthy()
    expect(await within(finland).findByText('Last deployed desired version: v1')).toBeTruthy()
    const germany = rowOf('Germany')
    expect(within(germany).getByText('No longer matches rule selector')).toBeTruthy()
    expect(within(germany).getByText('Deployment required')).toBeTruthy()
    expect(within(rowOf('France')).getByText('Target path already managed by a manual assignment.')).toBeTruthy()
    // One batched summary request for the managed rows, never one per node.
    expect(requests.filter(item => item.path.startsWith('/configuration-deployment-summaries')).map(item => item.path))
      .toEqual(['/configuration-deployment-summaries?assignmentId=a-de&assignmentId=a-fi'])

    fireEvent.click(screen.getByRole('button', { name: 'Show more' }))
    expect(await screen.findByRole('link', { name: 'Sweden' })).toBeTruthy()
    expect(requests.filter(item => item.path.includes('/targets')).map(item => item.path)).toEqual([
      '/configuration-assignment-rules/r1/targets?limit=25',
      '/configuration-assignment-rules/r1/targets?limit=25&afterName=Germany&afterId=de'])
    expect(within(rowOf('Sweden')).getByText('Missing value: server_id')).toBeTruthy()

    fireEvent.click(screen.getByRole('button', { name: 'Adopt into rule: France' }))
    fireEvent.click(screen.getByRole('button', { name: 'Detach: Germany' }))
    fireEvent.click(screen.getByRole('button', { name: 'Include again: Netherlands' }))
    fireEvent.click(screen.getByRole('button', { name: 'Exclude: Sweden' }))
    await waitFor(() => expect(requests.filter(item => item.method !== 'GET').map(item => [item.method, item.path, item.body])).toEqual([
      ['POST', '/configuration-assignment-rules/r1/assignments/m-fr/adopt', { expectedVersion: 5 }],
      ['POST', '/configuration-assignment-rules/r1/assignments/a-de/detach', { expectedVersion: 3 }],
      ['DELETE', '/configuration-assignment-rules/r1/exclude/nl', undefined],
      ['POST', '/configuration-assignment-rules/r1/exclude/se', undefined],
    ]))
  })

  it('completes a target that needs values into a valid managed assignment', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configuration-rules/r1')
    fireEvent.click(await screen.findByRole('button', { name: 'Show more' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Complete assignment: Sweden' }))
    const form = await screen.findByRole('form', { name: 'Values for Sweden' })
    expect(await within(form).findByText('Using default: vpn.example')).toBeTruthy()
    fireEvent.change(within(form).getByLabelText('domain'), { target: { value: 'se.vpn.example' } })
    fireEvent.click(within(form).getByRole('button', { name: 'Create assignment' }))
    await waitFor(() => expect(screen.queryByRole('form', { name: 'Values for Sweden' })).toBeNull())
    expect(requests.find(item => item.path.endsWith('/targets/se/assignment'))?.body)
      .toEqual({ values: [{ name: 'domain', value: 'se.vpn.example' }] })
  })

  it('edits the labels of a node by compare-and-set and surfaces a concurrent change', async () => {
    const { requests } = backend({ labelsStaleOnce: true })
    renderApp('/organizations/org/environments/env/resources/node?tab=configurations')
    const labels = await screen.findByRole('list', { name: 'Labels' })
    expect(within(labels).getByText('role=vpn')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Edit labels' }))
    expect(screen.getByText('Labels are not secret: do not put passwords, tokens or keys in them.')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Add label' }))
    fireEvent.change(input('Labels: key 2'), { target: { value: 'Region' } })
    fireEvent.change(input('Labels: value 2'), { target: { value: 'EU' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save labels' }))
    expect(await screen.findByText('Someone else changed these labels. Reload them and edit again.')).toBeTruthy()
    expect(requests.find(item => item.method === 'PUT')?.body).toEqual({ expectedVersion: 2,
      labels: [{ key: 'role', value: 'vpn' }, { key: 'region', value: 'EU' }] })

    fireEvent.click(screen.getByRole('button', { name: 'Reload' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Edit labels' }))
    fireEvent.change(input('Labels: key 1'), { target: { value: 'role' } })
    fireEvent.change(input('Labels: value 1'), { target: { value: 'database' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save labels' }))
    expect(await within(await screen.findByRole('list', { name: 'Labels' })).findByText('role=database')).toBeTruthy()
    expect(requests.filter(item => item.method === 'PUT').at(-1)?.body).toEqual({ expectedVersion: 3, labels: [{ key: 'role', value: 'database' }] })
  })

  it('marks managed assignments on a node and never removes one silently', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/environments/env/resources/node?tab=configurations')
    const managedRow = (await screen.findByText('/etc/xray/config.json')).closest('tr')!
    expect(within(managedRow).getByText(/Managed by rule VPN Production/)).toBeTruthy()
    expect(within(managedRow).getByRole('link', { name: 'Open rule' }).getAttribute('href')).toBe('/organizations/org/configuration-rules/r1')
    expect(within(screen.getByText('/etc/other.conf').closest('tr')!).getByText('Manual')).toBeTruthy()

    const confirm = vi.spyOn(window, 'confirm')
    fireEvent.click(within(managedRow).getByRole('button', { name: 'Remove assignment /etc/xray/config.json' }))
    expect(confirm).not.toHaveBeenCalled()
    const dialog = screen.getByRole('alertdialog', { name: 'This assignment is managed by rule VPN Production.' })
    expect(within(dialog).getByText(/If the resource still matches the rule, it will be created again./)).toBeTruthy()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Detach and keep' }))
    await waitFor(() => expect(screen.queryByRole('alertdialog')).toBeNull())
    expect(requests.filter(item => item.method !== 'GET').map(item => [item.method, item.path, item.body])).toEqual([
      ['POST', '/configuration-assignment-rules/r1/assignments/a-fi/detach', { expectedVersion: 2 }]])
  })

  it('keeps the version and path of a managed assignment with its rule on the editor', async () => {
    backend()
    renderApp('/organizations/org/configuration-assignments/a-fi')
    expect(await screen.findByText(/Managed by automation rule VPN Production/)).toBeTruthy()
    expect((screen.getByLabelText('Version') as HTMLSelectElement).disabled).toBe(true)
    expect(input('Target path').readOnly).toBe(true)
    expect(screen.queryByRole('button', { name: /^Use v/ })).toBeNull()
    expect((input('Value of domain')).disabled).toBe(false)
  })

  it('opens the rollout wizard with the rule\'s nodes and version preselected', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=targets&rolloutRule=r1&rolloutRevision=1')
    // Only the assignment the rule manages is chosen; the manual one on the same node is not.
    await waitFor(() => expect(screen.getAllByRole('checkbox', { name: 'Select Finland' })
      .map(box => (box as HTMLInputElement).checked)).toEqual([true, false]))
    expect((screen.getByLabelText('Target version') as HTMLSelectElement).value).toBe('1')
  })

  it('speaks Russian, and refuses those who may not manage configurations', async () => {
    backend()
    renderApp('/organizations/org/configurations/p1?tab=automation', { locale: 'ru' })
    expect(await screen.findByRole('tab', { name: 'Автоматизация' })).toBeTruthy()
    expect(await screen.findByText('Доступна новая версия v2')).toBeTruthy()
    expect(screen.getByText('управляет: 2')).toBeTruthy()
    cleanup()
    renderApp('/organizations/org/configuration-rules/r1', { role: 'MEMBER' })
    expect(await screen.findByText('You do not have permission to manage configurations.')).toBeTruthy()
  })
})
