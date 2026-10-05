// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../../app/queryClient'
import { I18nProvider } from '../../i18n'
import { NodeVersionsPanel } from './NodeVersionsPanel'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
const release = { releaseId: 'node-3.4.1', nodeVersion: '3.4.1', imageRepository: 'ghcr.io/remnawave/node', manifestDigest: `sha256:${'a'.repeat(64)}`,
  minimumPanelVersion: '3.4.0', maximumPanelVersionExclusive: '3.5.0', status: 'AVAILABLE', releasedAt: '2026-08-30T21:46:14Z',
  compatibility: { state: 'COMPATIBLE', reasons: [] }, platforms: [{ platform: 'linux/amd64', manifestDigest: `sha256:${'b'.repeat(64)}`, configDigest: `sha256:${'c'.repeat(64)}` }] }
const previous = { ...release, releaseId: 'node-3.4.0', nodeVersion: '3.4.0' }
const member = { membershipId: 'm1', nodeName: 'edge-01', status: 'UPDATE_AVAILABLE', releaseId: previous.releaseId, reportedVersion: '3.4.0',
  observation: { platform: 'linux/amd64', actualImageId: `sha256:${'d'.repeat(64)}`, configuredImage: `ghcr.io/remnawave/node@sha256:${'e'.repeat(64)}`, managedFiles: true } }
const plan = { target: release, waveCount: 2, automaticRollback: false,
  members: [{ membershipId: 'm1', nodeName: 'edge-01', wave: 0, skipped: false, previousImageReference: member.observation.configuredImage,
    baseline: { platform: 'linux/amd64', actualImageId: member.observation.actualImageId } }] }
const current = (over: Record<string, unknown> = {}) => ({ id: 'upgrade-1', state: 'RUNNING', phase: 'UPGRADE_CANARY', targetVersion: '3.4.1',
  currentWave: 0, waveCount: 2, failureCode: null, pauseReason: null, pauseRequested: false, rollbackRequested: false,
  rollbackIncomplete: false, createdAt: '2026-10-01T00:00:00Z', expiresAt: '2026-10-02T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z',
  snapshot: plan, members: [{ id: 'x1', membershipId: 'm1', wave: 0, state: 'RUNNING', failureCode: null }], actions: [], ...over })
type Options = { locale?: 'en' | 'ru'; manage?: boolean; control?: boolean; run?: ReturnType<typeof current>; unsupported?: boolean }
function mount(options: Options = {}, extra: (url: string, method: string) => Response | undefined = () => undefined) {
  const calls: { url: string; method: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const custom = extra(url, method); if (custom) return custom
    if (url.endsWith('/node-releases')) return json({ panel: { serverVersion: '3.4.0' }, releases: [release, previous], target: { id: 'revision-1', number: 1, release },
      active: options.run && ['RUNNING', 'PAUSED', 'QUEUED'].includes(String(options.run.state)) ? options.run : null,
      members: [{ ...member, releaseId: options.unsupported ? null : member.releaseId, status: options.unsupported ? 'UNKNOWN' : member.status }] })
    if (url.endsWith('/node-upgrades') && method === 'GET') return json({ items: options.run ? [options.run] : [] })
    if (options.run && url.endsWith('/node-upgrades/upgrade-1')) return json(options.run)
    if (url.endsWith('/node-release-target')) return json({ id: 'revision-2' })
    return json({ code: 'NOT_FOUND', message: 'x' }, 404)
  }))
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={createAppQueryClient()}>
    <NodeVersionsPanel organizationId="org" integrationId="i1" fleetId="f1" canManage={options.manage ?? true} canControl={options.control ?? true} />
  </QueryClientProvider></I18nProvider>)
  return calls
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('NodeVersionsPanel', () => {
  it('shows version, availability and architecture without arbitrary image input', async () => {
    mount()
    expect(await screen.findByText('Update available')).toBeTruthy()
    expect(screen.getByText('linux/amd64')).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
  })
  it('read permission gives history and status with no selection or controls', async () => {
    const calls = mount({ manage: false, control: false })
    await screen.findByText('Update available')
    expect(screen.queryByText('Set desired release')).toBeNull()
    expect(screen.queryByText('Preview upgrade')).toBeNull()
    expect(calls.every(call => call.method === 'GET')).toBe(true)
  })
  it('release selection changes metadata using a release ID only and starts no upgrade', async () => {
    const calls = mount({ control: false })
    const select = await screen.findByText('Set desired release')
    await waitFor(() => expect((select as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(select)
    await waitFor(() => expect(calls.some(c => c.url.endsWith('/node-release-target') && c.method === 'POST')).toBe(true))
    expect(calls.find(c => c.method === 'POST')?.body).toEqual({ releaseId: 'node-3.4.1' })
    expect(calls.filter(c => c.method === 'POST')).toHaveLength(1)
  })
  it('preview pins release revision, canary and wave policy; a READY plan starts by ID with stable request ID', async () => {
    let starts = 0
    const calls = mount({}, (url, method) => {
      if (url.endsWith('/node-upgrades/preview')) return json({ status: 'READY', issues: [], planId: 'plan-1', plan,
        expiresAt: new Date(Date.now() + 3600000).toISOString() })
      if (url.endsWith('/node-upgrades') && method === 'POST') { starts += 1; return json({ code: 'TEMPORARY', message: 'Unavailable' }, 503) }
      return undefined
    })
    await screen.findByText('Update available')
    fireEvent.click(screen.getByLabelText('edge-01'))
    fireEvent.click(screen.getByText('Preview upgrade'))
    fireEvent.click(await screen.findByText('Start upgrade'))
    await waitFor(() => expect(starts).toBe(1))
    await waitFor(() => expect((screen.getByText('Start upgrade') as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByText('Start upgrade'))
    await waitFor(() => expect(starts).toBe(2))
    expect(calls.find(c => c.url.endsWith('/preview'))?.body).toEqual({ releaseRevisionId: 'revision-1', canaryMemberIds: ['m1'], waveSize: 2,
      automaticRollback: false, pauseAfterCanary: true })
    const requests = calls.filter(c => c.url.endsWith('/node-upgrades') && c.method === 'POST')
    expect(requests[0].body).toEqual(requests[1].body)
  })
  it('stale preview offers refresh and cannot start', async () => {
    mount({}, url => url.endsWith('/preview') ? json({ status: 'REFRESH_REQUIRED', issues: ['REMNAWAVE_FLEET_UPGRADE_REFRESH_REQUIRED'], planId: null, plan: null }) : undefined)
    fireEvent.click(await screen.findByText('Preview upgrade'))
    expect(await screen.findByText(/Fresh evidence is required/)).toBeTruthy()
    expect(screen.queryByText('Start upgrade')).toBeNull()
  })
  it('UNKNOWN has inspection guidance, history and no retry, resume or rollback', async () => {
    mount({ run: current({ state: 'UNKNOWN' }) })
    fireEvent.click(await screen.findByText('Details'))
    expect(await screen.findByText(/Outcome unknown/)).toBeTruthy()
    expect(screen.queryByText('Resume')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Roll back' })).toBeNull()
    expect(screen.queryByRole('button', { name: /retry/i })).toBeNull()
  })
  it('a stale paused run has an explicit refresh/resume path in Russian', async () => {
    mount({ locale: 'ru', run: current({ state: 'PAUSED', pauseReason: 'PAUSED_REFRESH_REQUIRED' }) })
    expect(await screen.findByText(/Нужны свежие evidence/)).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Продолжить' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Обновить evidence Fleet' })).toBeTruthy()
  })
  it('unknown previous software disables automatic rollback', async () => {
    mount({ unsupported: true })
    await screen.findByText('Preview upgrade')
    expect((screen.getByLabelText('Roll back the current wave automatically after a known failure') as HTMLInputElement).disabled).toBe(true)
    expect(screen.getByText(/No reviewed previous digest/)).toBeTruthy()
  })
})
