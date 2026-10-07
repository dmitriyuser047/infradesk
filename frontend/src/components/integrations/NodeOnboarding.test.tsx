// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../../app/queryClient'
import { I18nProvider } from '../../i18n'
import type { NodeOnboardingOptions, NodeOnboardingPreview, NodeOnboardingRecoverySummary, NodeOnboardingRun, NodeOnboardingRunDetail } from '../../types/nodeOnboarding'
import { NodeOnboarding } from './NodeOnboarding'

const options: NodeOnboardingOptions = { nodeApi: { serverVersion: '2', apiGeneration: 'v2', sourceCommit: null, capabilities: [], provisioningReady: true, blocker: null },
  servers: [{ id: 'resource-1', name: 'Frankfurt VPS', address: '198.51.100.10', environmentName: 'Production', sshStatus: 'READY', serverProfileName: 'Ubuntu', revisionNumber: 3, serverProfileStatus: 'READY', blockingProblems: [] }],
  profiles: [{ id: 'profile-uuid', name: 'Default profile', inbounds: [{ id: 'inbound-uuid', name: 'VLESS TLS' }] }] }
const run = (state: NodeOnboardingRun['state'] = 'QUEUED'): NodeOnboardingRun => ({ id: 'run-1', organizationId: 'org', integrationId: 'integration', resourceId: 'resource-1', requestId: 'request-1', state, phase: state === 'RUNNING' ? 'INSTALL_NODE' : 'VALIDATE', nodeName: 'Frankfurt edge', address: '198.51.100.11', nodePort: 443, externalNodeId: 'external-1', baselineRunId: 'baseline-1', syncSessionId: 'sync-1', failureCode: state === 'FAILED' ? 'SAFE_FAILURE' : null, safeMessage: state === 'FAILED' ? 'A safe failure message.' : null, createdAt: '', updatedAt: '', startedAt: null, finishedAt: null })
const preview: NodeOnboardingPreview = { run: { ...run('PLANNED'), id: 'plan-1', externalNodeId: null, baselineRunId: null, syncSessionId: null }, serverName: 'Frankfurt VPS', serverProfileName: 'Ubuntu', revisionNumber: 3, configProfileName: 'Default profile', inboundNames: ['VLESS TLS'], nodeImage: null, changes: ['Create node'], warnings: [], blockingProblems: [] }
const recoverySummary = (state: NodeOnboardingRecoverySummary['state'], action: NodeOnboardingRecoverySummary['action'] = 'RECOVER'): NodeOnboardingRecoverySummary => ({ state, action, sourceRunId: 'run-1', previousExternalNodeId: 'external-1', previousCorrelationId: 'correlation-1', installationOwnerId: 'owner-1' })
const recoveryPreview = (recovery: NodeOnboardingRecoverySummary): NodeOnboardingPreview => ({ ...preview, run: { ...preview.run, externalNodeId: recovery.previousExternalNodeId, correlationId: recovery.action==="RECOVER" ? recovery.previousCorrelationId : "new-correlation-1" }, recovery, changes: ['Resume existing node'] })
const getRandomValues = crypto.getRandomValues.bind(crypto)
 beforeEach(() => vi.stubGlobal('crypto', {getRandomValues}))

const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

function mount(entry = '/', locale: 'en' | 'ru' = 'en', configure: (url: string, method: string, body: unknown) => Response | undefined = () => undefined) {
  const calls: { url: string; method: string; body: unknown }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(String(init.body)) : undefined
    calls.push({ url, method, body })
    const custom = configure(url, method, body)
    if (custom) return custom
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
    if (url.endsWith('/remnawave-node-onboarding/options')) return json(options)
    if (url.endsWith('/remnawave-node-onboarding/runs') && method === 'GET') return json({ items: [] })
    if (url.endsWith('/remnawave-node-onboarding/preview')) return json(preview)
    if (url.endsWith('/remnawave-node-onboarding/runs') && method === 'POST') return json(run())
    if (url.endsWith('/remnawave-node-onboarding/runs/run-1')) return json({ run: run('RUNNING'), phases: [{ phase: 'VALIDATE', state: 'SUCCEEDED', startedAt: null, finishedAt: null, failureCode: null }] } satisfies NodeOnboardingRunDetail)
    if (url.endsWith('/resources/resource-1')) return json({ id: 'resource-1', organizationId: 'org', environmentId: 'env-1', resourceTypeId: 'type', parentResourceId: null, code: 'vps', name: 'Frankfurt VPS', resourceTypeCode: 'VPS', active: true, data: { kind: 'NODE', spec: null, status: null }, createdAt: '', updatedAt: '' })
    return configure(url, method, body) ?? json({ code: 'NOT_FOUND', message: 'not found' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  const client = createAppQueryClient()
  function SearchProbe() { return <output data-testid="search">{useLocation().search}</output> }
  render(<I18nProvider initialLocale={locale}><QueryClientProvider client={client}><MemoryRouter initialEntries={[entry]}>
    <SearchProbe /><NodeOnboarding organizationId="org" integrationId="integration" />
  </MemoryRouter></QueryClientProvider></I18nProvider>)
  return { calls, fetchMock }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); sessionStorage.clear() })

describe('NodeOnboarding', () => {
  it('recovers saved onboarding identity after reload without automatically submitting', async () => {
    const identity={planId:'plan-1',requestId:'d1111111-1111-4111-8111-111111111111'}
    sessionStorage.setItem('node-onboarding:org:integration',JSON.stringify(identity))
    const {calls}=mount('/','en',url=>url.endsWith('/runs/plan-1') ? json({run:preview.run,phases:[]}) : undefined)
    const retry=await screen.findByRole('button',{name:'Retry unconfirmed submission'}) as HTMLButtonElement
    await waitFor(()=>expect(retry.disabled).toBe(false))
    expect(calls.filter(c=>c.method==='POST')).toHaveLength(0)
    fireEvent.click(retry)
    await waitFor(()=>expect(calls.some(c=>c.method==='POST' && c.url.endsWith('/runs'))).toBe(true))
    expect(calls.find(c=>c.method==='POST' && c.url.endsWith('/runs'))?.body).toEqual(identity)
    await waitFor(()=>expect(sessionStorage.length).toBe(0))
  })
  it.each([
    ['en', 'ReadOrganization members can view onboarding history and details without loading provisioning options', 'Frankfurt edge · 198.51.100.11 · FAILED', 'Run: FAILED'],
    ['ru', 'Участники с правом чтения организации могут просматривать историю и запуски без параметров подготовки', 'Frankfurt edge · 198.51.100.11 · FAILED', 'Запуск: FAILED'],
  ] as const)('%s: %s', async (locale, _description, historyLabel, detailLabel) => {
    const secret = 'RAW_BACKEND_SAFE_MESSAGE'
    const { calls } = mount('/', locale, (url, method) => {
      if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role: 'MEMBER' }])
      if (url.endsWith('/remnawave-node-onboarding/runs') && method === 'GET') return json({ items: [run('FAILED')] })
      if (url.endsWith('/runs/run-1')) return json({ run: { ...run('FAILED'), safeMessage: secret }, phases: [] })
      return undefined
    })
    expect(await screen.findByRole('button', { name: historyLabel })).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/options'))).toBe(false)
    expect(calls.some(call => call.url.endsWith('/runs') && call.method === 'GET')).toBe(true)
    expect((screen.getByRole('button', { name: locale === 'ru' ? 'Добавить узел' : 'Add node' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: historyLabel }))
    expect(await screen.findByText(detailLabel)).toBeTruthy()
    expect(calls.some(call => call.url.endsWith('/runs/run-1') && call.method === 'GET')).toBe(true)
    expect(calls.some(call => call.method === 'POST')).toBe(false)
    expect(document.body.textContent).not.toContain(secret)
  })

  it('requires inbounds and rejects empty, broad, hostname, and duplicate panel sources', async () => {
    mount()
    await waitFor(() => expect((screen.getByRole('button', { name: 'Add node' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Add node' }))
    fireEvent.change(await screen.findByLabelText('Server'), { target: { value: 'resource-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Configuration profile'), { target: { value: 'profile-uuid' } })
    expect((screen.getByRole('button', { name: 'Continue' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.click(screen.getByLabelText('VLESS TLS'))
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    for (const value of ['', '0.0.0.0/0', '::/0', 'panel.example/24', '203.0.113.1', '203.0.113.0/24,203.0.113.0/24']) {
      fireEvent.change(screen.getByLabelText('Panel CIDRs'), { target: { value } })
      expect((screen.getByRole('button', { name: 'Continue' }) as HTMLButtonElement).disabled).toBe(true)
    }
    fireEvent.change(screen.getByLabelText('Panel CIDRs'), { target: { value: '203.0.113.0/24' } })
    expect((screen.getByRole('button', { name: 'Continue' }) as HTMLButtonElement).disabled).toBe(false)
  })
  it('sends only the reviewed basic-flow fields and retains the run in the URL', async () => {
    const { calls } = mount()
    await waitFor(() => expect((screen.getByRole('button', { name: 'Add node' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Add node' }))
    fireEvent.change(await screen.findByLabelText('Server'), { target: { value: 'resource-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Node name'), { target: { value: 'Frankfurt edge' } })
    fireEvent.change(screen.getByLabelText('Node address'), { target: { value: '198.51.100.11' } })
    fireEvent.change(screen.getByLabelText('Configuration profile'), { target: { value: 'profile-uuid' } })
    fireEvent.click(screen.getByLabelText('VLESS TLS'))
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Panel CIDRs'), { target: { value: '203.0.113.0/24' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    expect(await screen.findByText('Create node')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Start onboarding' }))
    await waitFor(() => expect(calls.some(value => value.method === 'POST' && value.url.endsWith('/runs'))).toBe(true))
    expect(calls.find(value => value.url.endsWith('/preview'))?.body).toEqual({ resourceId: 'resource-1', nodeName: 'Frankfurt edge', address: '198.51.100.11', nodePort: 2222, configProfileId: 'profile-uuid', activeInboundIds: ['inbound-uuid'], panelCidrs: ['203.0.113.0/24'], desiredState: 'ENABLED' })
    expect(calls.find(value => value.method === 'POST' && value.url.endsWith('/runs'))?.body).toEqual({ planId: 'plan-1', requestId: expect.any(String) })
    await waitFor(() => expect(screen.getByTestId('search').textContent).toContain('onboardingRun=run-1'))
  })

  it('reuses the same request ID after an uncertain submit and prevents applying preview blockers', async () => {
    let submits = 0
    const { calls } = mount('/', 'en', (url, method) => {
      if (url.endsWith('/runs') && method === 'POST' && submits++ === 0) return json({ code: 'HTTP_ERROR', message: 'x' }, 503)
      return undefined
    })
    await waitFor(() => expect((screen.getByRole('button', { name: 'Add node' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Add node' }))
    fireEvent.change(await screen.findByLabelText('Server'), { target: { value: 'resource-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Node name'), { target: { value: 'Frankfurt edge' } })
    fireEvent.change(screen.getByLabelText('Node address'), { target: { value: '198.51.100.11' } })
    fireEvent.change(screen.getByLabelText('Configuration profile'), { target: { value: 'profile-uuid' } })
    fireEvent.click(screen.getByLabelText('VLESS TLS'))
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Panel CIDRs'), { target: { value: '203.0.113.0/24' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' })); await screen.findByText('Create node')
    fireEvent.click(screen.getByRole('button', { name: 'Start onboarding' }))
    const retry = await screen.findByRole('button', { name: 'Start onboarding' })
    fireEvent.click(retry)
    await waitFor(() => expect(calls.filter(value => value.method === 'POST' && value.url.endsWith('/runs'))).toHaveLength(2))
    const ids = calls.filter(value => value.method === 'POST' && value.url.endsWith('/runs')).map(value => (value.body as { requestId: string }).requestId)
    expect(ids[0]).toBe(ids[1])
  })

  it('keeps preview blockers visible and disables the apply action', async () => {
    const blocked = { ...preview, blockingProblems: ['The selected profile cannot be applied.'] }
    const { calls } = mount('/', 'en', url => url.endsWith('/preview') ? json(blocked) : undefined)
    await waitFor(() => expect((screen.getByRole('button', { name: 'Add node' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Add node' }))
    fireEvent.change(await screen.findByLabelText('Server'), { target: { value: 'resource-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Node name'), { target: { value: 'Frankfurt edge' } })
    fireEvent.change(screen.getByLabelText('Node address'), { target: { value: '198.51.100.11' } })
    fireEvent.change(screen.getByLabelText('Configuration profile'), { target: { value: 'profile-uuid' } })
    fireEvent.click(screen.getByLabelText('VLESS TLS'))
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.change(screen.getByLabelText('Panel CIDRs'), { target: { value: '203.0.113.0/24' } })
    fireEvent.click(screen.getByRole('button', { name: 'Continue' }))
    fireEvent.click(screen.getByRole('button', { name: 'Review changes' }))
    expect(await screen.findByText('The selected profile cannot be applied.')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Start onboarding' }) as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(value => value.method === 'POST' && value.url.endsWith('/runs'))).toBe(false)
  })

  it('shows unsupported provisioning in RU and leaves controls gated', async () => {
    const unsupported: NodeOnboardingOptions = { ...options, nodeApi: { ...options.nodeApi!, provisioningReady: false, blocker: 'not supported' } }
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
      if (url.endsWith('/remnawave-node-onboarding/options')) return json(unsupported)
      if (url.endsWith('/remnawave-node-onboarding/runs')) return json({ items: [] })
      return json({ code: 'NOT_FOUND', message: 'x' }, 404)
    }))
    const client = createAppQueryClient()
    render(<I18nProvider initialLocale="ru"><QueryClientProvider client={client}><MemoryRouter><NodeOnboarding organizationId="org" integrationId="integration" /></MemoryRouter></QueryClientProvider></I18nProvider>)
    expect(await screen.findByText('not supported')).toBeTruthy()
    expect((screen.getByRole('button', { name: 'Добавить узел' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it.each([
    ['SUCCEEDED', 'Node onboarding completed.'], ['FAILED', 'Onboarding failed. Review the completed phases'], ['UNKNOWN', 'The outcome is unknown. Check Remnawave']
  ] as const)('shows terminal %s results and partial progress on return by URL', async (state, message) => {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role: 'OWNER' }])
      if (url.endsWith('/remnawave-node-onboarding/options')) return json(options)
      if (url.endsWith('/remnawave-node-onboarding/runs')) return json({ items: [] })
      if (url.endsWith('/remnawave-node-onboarding/runs/run-1')) return json({ run: run(state), phases: [{ phase: 'VALIDATE', state: 'SUCCEEDED', startedAt: null, finishedAt: null, failureCode: null }] })
      return json({ code: 'NOT_FOUND', message: 'x' }, 404)
    }))
    const client = createAppQueryClient()
    render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><MemoryRouter initialEntries={['/?onboardingRun=run-1']}><NodeOnboarding organizationId="org" integrationId="integration" /></MemoryRouter></QueryClientProvider></I18nProvider>)
    expect(await screen.findByText(new RegExp(message))).toBeTruthy()
    expect(screen.getByText('external-1')).toBeTruthy()
    expect(screen.getByText('baseline-1')).toBeTruthy()
    expect(screen.getByText('sync-1')).toBeTruthy()
    expect(screen.getByText(/Validate request/)).toBeTruthy()
  })
  it.each(['FIREWALL_RULE_UNSUPPORTED', 'PROVISIONING_FIREWALL_RULE_UNSUPPORTED'])('localizes %s beside the failed firewall phase without rendering backend prose', async code => {
    const failed = { ...run('FAILED'), phase: 'CONFIGURE_NODE_FIREWALL', failureCode: code, safeMessage: 'RAW_EXCEPTION_SECRET English fallback' }
    mount('/?onboardingRun=run-1', 'ru', url => url.endsWith('/runs/run-1') ? json({ run: failed, phases: [
      { phase: 'CREATE_NODE', state: 'SUCCEEDED', startedAt: null, finishedAt: null, failureCode: null },
      { phase: 'CONFIGURE_NODE_FIREWALL', state: 'FAILED', startedAt: null, finishedAt: null, failureCode: code },
    ] }) : undefined)
    const phase = await screen.findByText('Настройка межсетевого экрана — FAILED')
    const alert = phase.closest('[role="alert"]')!
    expect(within(alert as HTMLElement).getByText('Не удалось безопасно обработать текущую конфигурацию UFW.')).toBeTruthy()
    expect(within(alert as HTMLElement).getByText(`Код: ${code}`)).toBeTruthy()
    expect(screen.getByText('external-1')).toBeTruthy()
    expect(document.body.textContent).not.toContain('RAW_EXCEPTION_SECRET')
    expect(document.body.textContent).not.toContain('English fallback')
  })
  it('uses a safe localized fallback for unknown failure codes and hides raw safeMessage', async () => {
    mount('/?onboardingRun=run-1', 'en', url => url.endsWith('/runs/run-1') ? json({ run: { ...run('FAILED'), safeMessage: 'RAW_EXCEPTION_SECRET' }, phases: [] }) : undefined)
    await screen.findByText('Run: FAILED')
    expect(screen.getAllByText('Code: SAFE_FAILURE').length).toBeGreaterThan(0)
    expect(document.body.textContent).not.toContain('RAW_EXCEPTION_SECRET')
  })
  it('repeats UNKNOWN recovery as read-only reconciliation against the original source run', async () => {
    let reads = 0
    const unknown = recoveryPreview(recoverySummary('UNKNOWN'))
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('UNKNOWN'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') { reads++; return json(unknown) }
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    await screen.findByText('The remote state is still unknown. Check again to repeat the read-only observation.')
    fireEvent.click(screen.getByRole('button', { name: 'Check local installation' }))
    await waitFor(() => expect(reads).toBe(2))
    expect(calls.filter(call => call.method === 'POST' && call.url.endsWith('/reconcile')).map(call => [call.url, call.body])).toEqual([
      ['/api/v1/organizations/org/integrations/integration/remnawave-node-onboarding/runs/run-1/reconcile', { action: 'RECOVER' }],
      ['/api/v1/organizations/org/integrations/integration/remnawave-node-onboarding/runs/run-1/reconcile', { action: 'RECOVER' }],
    ])
    expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(false)
  })
  it('restores an exact existing node without offering a create action', async () => {
    const exact = recoveryPreview(recoverySummary('PRESENT_EXACT'))
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('FAILED'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') return json(exact)
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    expect(await screen.findByRole('button', { name: 'Restore existing node' })).toBeTruthy()
    expect(screen.getByText('Previous node UUID')).toBeTruthy()
    expect(screen.getByText('external-1')).toBeTruthy()
    expect(document.body.textContent).not.toContain('Create node')
    fireEvent.click(screen.getByRole('button', { name: 'Restore existing node' }))
    await waitFor(() => expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(true))
    const startCall = calls.find(call => call.method === 'POST' && call.url.endsWith('/runs'))!
    expect(startCall.body).toEqual({ planId: 'plan-1', requestId: expect.any(String) })
  })
  it('requires explicit approval before starting a confirmed-missing node recreation', async () => {
    const missing = recoveryPreview(recoverySummary('CONFIRMED_NOT_FOUND', 'RECREATE'))
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('FAILED'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') return json(missing)
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    expect(await screen.findByText('The node was not found in Remnawave.')).toBeTruthy()
    expect(screen.getByText('New correlation ID')).toBeTruthy()
    expect(screen.getByText('new-correlation-1')).toBeTruthy()
    const start = screen.getByRole('button', { name: 'Recreate node' }) as HTMLButtonElement
    expect(start.disabled).toBe(true)
    expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(false)
    fireEvent.click(screen.getByLabelText('I reviewed the previous node UUID and approve creating a new Remnawave node with a new correlation ID.'))
    expect(start.disabled).toBe(false)
    fireEvent.click(start)
    await waitFor(() => expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(true))
    expect(calls.find(call => call.method === 'POST' && call.url.endsWith('/runs'))?.body).toEqual({ planId: 'plan-1', requestId: expect.any(String), confirmRecreate: true })
  })
  it.each(['ABSENT', 'OWNED_COMPLETE', 'OWNED_PARTIAL', 'OWNED_DAMAGED', 'FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'] as const)('keeps Panel absence visible with local %s and rechecks without starting', async state => {
    const unsafe = ['FOREIGN', 'PORT_CONFLICT', 'UNKNOWN'].includes(state)
    const proof = { ...recoverySummary('CONFIRMED_NOT_FOUND', 'RECREATE'), localInstallation: {
      state, diagnosis: state === 'UNKNOWN' ? 'REMNAWAVE_LOCAL_INSTALLATION_SSH_UNAVAILABLE' : null,
    } }
    let checks = 0
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('FAILED'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') {
        checks++
        return json({ ...recoveryPreview(proof), changes: unsafe ? [] : ['CREATE_NODE'], blockingProblems: unsafe ? ['REMNAWAVE_LOCAL_INSTALLATION_SSH_UNAVAILABLE'] : [] })
      }
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    expect(await screen.findByText('The node was not found in Remnawave.')).toBeTruthy()
    expect(screen.getByText('Local installation')).toBeTruthy()
    const approval = screen.queryByLabelText('I reviewed the previous node UUID and approve creating a new Remnawave node with a new correlation ID.')
    expect(approval !== null).toBe(!unsafe)
    if (approval) fireEvent.click(approval)
    fireEvent.click(screen.getByRole('button', { name: 'Check local installation' }))
    await waitFor(() => expect(checks).toBe(2))
    expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(false)
    if (!unsafe) await waitFor(() => expect((screen.getByRole('button', { name: 'Recreate node' }) as HTMLButtonElement).disabled).toBe(true))
  })
  it.each([
    ['RECREATE', 'CONFIRMED_NOT_FOUND'],
    ['DELETE_RECREATE', 'PRESENT_UNHEALTHY'],
  ] as const)('retries an uncertain %s start after reload with the same identity and confirmation', async (action, state) => {
    const storageKey = 'node-onboarding:org:integration'
    const rawSafeMessage = 'RAW_BACKEND_SAFE_MESSAGE_SECRET'
    const plannedRecovery = recoverySummary(state, action)
    let submits = 0
    let reconciles = 0
    const configure = (url: string, method: string, body: unknown) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: { ...run('FAILED'), safeMessage: rawSafeMessage }, phases: [] })
      if (url.endsWith('/runs/plan-1') && method === 'GET') return json({
        run: { ...preview.run, recovery: plannedRecovery, safeMessage: rawSafeMessage }, phases: [],
      })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') {
        reconciles++
        if (action === 'DELETE_RECREATE') return json(reconciles === 1
          ? recoveryPreview(recoverySummary('PRESENT_UNHEALTHY'))
          : recoveryPreview(plannedRecovery))
        return json(recoveryPreview(plannedRecovery))
      }
      if (url.endsWith('/runs') && method === 'POST') {
        submits++
        return submits === 1 ? json({ code: 'HTTP_ERROR', message: rawSafeMessage }, 503) : json(run('QUEUED'))
      }
      return undefined
    }
    const { calls: firstCalls } = mount('/?onboardingRun=run-1', 'en', configure)
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    if (action === 'DELETE_RECREATE') fireEvent.click(await screen.findByRole('button', { name: 'Delete and recreate' }))
    const approval = action === 'RECREATE'
      ? 'I reviewed the previous node UUID and approve creating a new Remnawave node with a new correlation ID.'
      : 'I approve deleting the existing Remnawave node and creating a replacement with a new correlation ID.'
    fireEvent.click(await screen.findByLabelText(approval))
    fireEvent.click(screen.getByRole('button', { name: action === 'RECREATE' ? 'Recreate node' : 'Delete and recreate' }))
    await screen.findByText('The request could not be confirmed. Retry with the same request ID to safely recover the result.')
    const saved = JSON.parse(sessionStorage.getItem(storageKey)!)
    expect(Object.keys(saved).sort()).toEqual(['planId', 'requestId'])
    expect(saved.planId).toBe('plan-1')
    expect(saved.requestId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(JSON.stringify(saved)).not.toContain(rawSafeMessage)
    const firstStart = firstCalls.find(call => call.method === 'POST' && call.url.endsWith('/runs'))!
    expect(firstStart.body).toEqual({ planId: 'plan-1', requestId: saved.requestId, confirmRecreate: true })

    cleanup()
    const { calls: retryCalls } = mount('/', 'en', configure)
    const retry = await screen.findByRole('button', { name: 'Retry unconfirmed submission' })
    await waitFor(() => expect(retryCalls.some(call => call.method === 'GET' && call.url.endsWith('/runs/plan-1'))).toBe(true))
    await waitFor(() => expect((retry as HTMLButtonElement).disabled).toBe(false))
    expect(screen.queryByLabelText(approval)).toBeNull()
    expect(retry).toBeTruthy()
    expect(document.body.textContent).not.toContain(rawSafeMessage)
    fireEvent.click(retry)
    await waitFor(() => expect(retryCalls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(true))
    const retryStart = retryCalls.find(call => call.method === 'POST' && call.url.endsWith('/runs'))!
    expect(retryStart.body).toEqual({ planId: 'plan-1', requestId: saved.requestId, confirmRecreate: true })
    expect(sessionStorage.getItem(storageKey)).toBeNull()
    expect(document.body.textContent).not.toContain(rawSafeMessage)
  })
  it('blocks a conflicting identity and requires a second confirmation for delete and recreate', async () => {
    const exact = recoveryPreview(recoverySummary('PRESENT_UNHEALTHY'))
    const destructive = recoveryPreview(recoverySummary('PRESENT_UNHEALTHY', 'DELETE_RECREATE'))
    let reconciles = 0
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('FAILED'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') return json(++reconciles === 1 ? exact : destructive)
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete and recreate' }))
    expect(await screen.findByLabelText('I approve deleting the existing Remnawave node and creating a replacement with a new correlation ID.')).toBeTruthy()
    const start = screen.getByRole('button', { name: 'Delete and recreate' }) as HTMLButtonElement
    expect(start.disabled).toBe(true)
    expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(false)
    fireEvent.click(screen.getByLabelText('I approve deleting the existing Remnawave node and creating a replacement with a new correlation ID.'))
    fireEvent.click(start)
    await waitFor(() => expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(true))
    expect(calls.filter(call => call.method === 'POST' && call.url.endsWith('/reconcile')).map(call => call.body)).toEqual([{ action: 'RECOVER' }, { action: 'DELETE_RECREATE' }])
    expect(calls.find(call => call.method === 'POST' && call.url.endsWith('/runs'))?.body).toEqual({ planId: 'plan-1', requestId: expect.any(String), confirmRecreate: true })
  })
  it('blocks conflicting recovery without starting a run', async () => {
    const conflict = recoveryPreview(recoverySummary('PRESENT_CONFLICT'))
    const { calls } = mount('/?onboardingRun=run-1', 'en', (url, method) => {
      if (url.endsWith('/runs/run-1') && method === 'GET') return json({ run: run('FAILED'), phases: [] })
      if (url.endsWith('/runs/run-1/reconcile') && method === 'POST') return json(conflict)
      return undefined
    })
    fireEvent.click(await screen.findByRole('button', { name: 'Check again' }))
    expect(await screen.findByText('The Remnawave node conflicts with the previous installation identity. No action is available.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Start onboarding' })).toBeNull()
    expect(calls.some(call => call.method === 'POST' && call.url.endsWith('/runs'))).toBe(false)
  })
  it('shows delete phases only for a delete-recreate run', async () => {
    mount('/?onboardingRun=run-1', 'en', url => url.endsWith('/runs/run-1') ? json({ run: { ...run('FAILED'), recovery: recoverySummary('PRESENT_UNHEALTHY', 'DELETE_RECREATE') }, phases: [] }) : undefined)
    expect(await screen.findByText(/Delete existing Remnawave node/)).toBeTruthy()
    expect(screen.getByText(/Confirm node deletion/)).toBeTruthy()
    expect(screen.getByText('Retire previous local node installation')).toBeTruthy()
  })
  it.each([
    ['en', 'Retire previous node firewall rule', 'Retire previous local node installation'],
    ['ru', 'Удаление правила межсетевого экрана предыдущего узла', 'Удаление предыдущей локальной установки узла'],
  ] as const)('%s: shows the durable firewall retirement phase before local retirement for new recovery history', async (locale, firewallLabel, localLabel) => {
    mount('/?onboardingRun=run-1', locale, url => url.endsWith('/runs/run-1') ? json({
      run: { ...run('FAILED'), phase: 'RETIRE_NODE_FIREWALL', failureCode: null, recovery: recoverySummary('PRESENT_UNHEALTHY', 'RECREATE') },
      phases: [{ phase: 'RETIRE_NODE_FIREWALL', state: 'SUCCEEDED', startedAt: null, finishedAt: null, failureCode: null }],
    }) : undefined)
    const firewall = await screen.findByText(firewallLabel)
    const local = screen.getByText(localLabel)
    expect(firewall.compareDocumentPosition(local) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
  it('shows local retirement before create for a recreate run only', async () => {
    mount('/?onboardingRun=run-1', 'en', url => url.endsWith('/runs/run-1') ? json({ run: { ...run('FAILED'), recovery: recoverySummary('CONFIRMED_NOT_FOUND', 'RECREATE') }, phases: [] }) : undefined)
    expect(await screen.findByText('Retire previous local node installation')).toBeTruthy()
    expect(screen.queryByText('Delete existing Remnawave node')).toBeNull()
  })
  it('shows the previously created node identity in a newly reviewed recovery plan', async () => {
    mount('/', 'ru', url => url.endsWith('/preview') ? json({ ...preview,
      run: { ...preview.run, externalNodeId: '195bf2a3-1f0e-425e-81cd-6310e04a76a0' },
      warnings: ['REMNAWAVE_ONBOARDING_REUSE_EXISTING_NODE'] }) : undefined)
    await waitFor(() => expect((screen.getByRole('button', { name: 'Добавить узел' }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'Добавить узел' }))
    fireEvent.change(await screen.findByLabelText('Сервер'), { target: { value: 'resource-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }))
    fireEvent.change(screen.getByLabelText('Профиль конфигурации'), { target: { value: 'profile-uuid' } })
    fireEvent.click(screen.getByLabelText('VLESS TLS'))
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }))
    fireEvent.change(screen.getByLabelText('CIDR панели'), { target: { value: '2.27.26.18/32' } })
    fireEvent.click(screen.getByRole('button', { name: 'Далее' }))
    fireEvent.click(screen.getByRole('button', { name: 'Проверить изменения' }))
    expect(await screen.findByText('Будет использован уже созданный узел')).toBeTruthy()
    expect(screen.getByText('ID узла Remnawave: 195bf2a3-1f0e-425e-81cd-6310e04a76a0')).toBeTruthy()
    expect(document.body.textContent).not.toContain('REMNAWAVE_ONBOARDING_REUSE_EXISTING_NODE')
    expect((screen.getByRole('button', { name: 'Запустить добавление' }) as HTMLButtonElement).disabled).toBe(false)
  })
})
