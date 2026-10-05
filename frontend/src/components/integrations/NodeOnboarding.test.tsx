// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, useLocation } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../../app/queryClient'
import { I18nProvider } from '../../i18n'
import type { NodeOnboardingOptions, NodeOnboardingPreview, NodeOnboardingRun, NodeOnboardingRunDetail } from '../../types/nodeOnboarding'
import { NodeOnboarding } from './NodeOnboarding'

const options: NodeOnboardingOptions = { nodeApi: { serverVersion: '2', apiGeneration: 'v2', sourceCommit: null, capabilities: [], provisioningReady: true, blocker: null },
  servers: [{ id: 'resource-1', name: 'Frankfurt VPS', address: '198.51.100.10', environmentName: 'Production', sshStatus: 'READY', serverProfileName: 'Ubuntu', revisionNumber: 3, serverProfileStatus: 'READY', blockingProblems: [] }],
  profiles: [{ id: 'profile-uuid', name: 'Default profile', inbounds: [{ id: 'inbound-uuid', name: 'VLESS TLS' }] }] }
const run = (state: NodeOnboardingRun['state'] = 'QUEUED'): NodeOnboardingRun => ({ id: 'run-1', organizationId: 'org', integrationId: 'integration', resourceId: 'resource-1', requestId: 'request-1', state, phase: state === 'RUNNING' ? 'INSTALL_NODE' : 'VALIDATE', nodeName: 'Frankfurt edge', address: '198.51.100.11', nodePort: 443, externalNodeId: 'external-1', baselineRunId: 'baseline-1', syncSessionId: 'sync-1', failureCode: state === 'FAILED' ? 'SAFE_FAILURE' : null, safeMessage: state === 'FAILED' ? 'A safe failure message.' : null, createdAt: '', updatedAt: '', startedAt: null, finishedAt: null })
const preview: NodeOnboardingPreview = { run: { ...run('PLANNED'), id: 'plan-1', externalNodeId: null, baselineRunId: null, syncSessionId: null }, serverName: 'Frankfurt VPS', serverProfileName: 'Ubuntu', revisionNumber: 3, configProfileName: 'Default profile', inboundNames: ['VLESS TLS'], nodeImage: null, changes: ['Create node'], warnings: [], blockingProblems: [] }
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
  it('blocks mutation entry for a member without the required permissions', async () => {
    const { calls } = mount('/', 'en', url => url === '/api/v1/me/organizations'
      ? json([{ id: 'org', code: 'ORG', name: 'Org', role: 'MEMBER' }]) : undefined)
    await screen.findByText('Adding a node requires manage integrations, manage configurations, and execute operations permissions.')
    expect((screen.getByRole('button', { name: 'Add node' }) as HTMLButtonElement).disabled).toBe(true)
    expect(calls.some(call => call.url.endsWith('/options') || call.method === 'POST')).toBe(false)
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
    expect(screen.getByText('Validate request')).toBeTruthy()
  })
})
