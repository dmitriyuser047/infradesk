// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import type { ConfigurationAssignmentDetail } from '../../types/configurationAssignment'
import { ConfigurationDeploymentPanel } from './ConfigurationDeploymentPanel'
import { ConfigurationRolloutPanel } from './ConfigurationRolloutPanel'

const assignment: ConfigurationAssignmentDetail = { id: 'a1', version: 3, profileRevisionNumber: 1,
  targetPath: '/etc/app.conf', removedAt: null, createdAt: '', updatedAt: '', values: [],
  revision: { revisionNumber: 1, variables: [], createdAt: '', createdBy: { id: 'u1', displayName: 'Operator' } },
  profile: { id: 'p1', code: 'profile', name: 'Profile', archived: false, latestRevisionNumber: 1 },
  resource: { id: 'n1', name: 'Edge', code: 'edge', resourceTypeCode: 'NODE', active: true,
    project: { id: 'p', name: 'Project' }, environment: { id: 'e', name: 'Production', kind: 'PROD' } } }
const execution = { activation: 'NONE', unitName: null, validator: null, newFileMode: 420 }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), {
  status, headers: { 'Content-Type': 'application/json' } })
const preview = { assignmentVersion: 3, profileRevisionNumber: 1, targetPath: '/etc/app.conf',
  remote: { exists: true, sha256: 'a'.repeat(64), text: true }, desired: { sha256: 'b'.repeat(64) },
  changed: true, atomicReplaceSupported: true, diff: { text: 'SECRET_REMOTE_CONTENT', truncated: false,
    approximate: false, addedLines: 1, removedLines: 0 }, connection: { id: 'ssh', name: 'SSH', updatedAt: '2026-10-05T10:00:00Z' } }

type Call = { path: string; body: any }
function backend(custom: (call: Call) => Response | Promise<Response> | undefined = () => undefined) {
  const calls: Call[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = new URL(String(input), 'http://localhost').pathname
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    const call = { path, body }; calls.push(call)
    const value = custom(call)
    if (value) return value
    if (path.endsWith('/deployment-preview')) return json(preview)
    if (path.endsWith('/configuration-assignments')) return json([assignment])
    if (path.endsWith('/preflight')) return json({ ready: true, items: body.targets.map((target: any) => ({ ...target,
      ready: true, desiredSha256: 'b'.repeat(64), connectionUpdatedAt: '2026-10-05T10:00:00Z',
      remote: { exists: true, sha256: 'a'.repeat(64) }, connectionName: 'SSH', changed: true,
      addedLines: 1, removedLines: 0, errorCode: null })) })
    if (path.endsWith('/deployments')) return json({ deploymentId: 'd1', state: 'QUEUED' }, 202)
    if (path.endsWith('/configuration-deployments/d1')) return json({ id: 'd1', state: 'SUCCEEDED', phase: 'CLEANUP',
      actor: { id: 'u1', name: 'Operator' }, failureCode: null, events: [], execution })
    if (path.endsWith('/configuration-rollouts')) return json({ rolloutId: 'r1', state: 'QUEUED' }, 202)
    if (path.endsWith('/configuration-rollouts/r1')) return json({ id: 'r1', profileRevisionNumber: 1,
      state: 'SUCCEEDED', strategy: { canaryCount: 1, batchSize: 1 }, counts: { succeeded: 1, items: 1 }, items: [] })
    return json({ code: 'NOT_FOUND', message: 'missing' }, 404)
  }))
  return calls
}

function mount(kind: 'deployment' | 'rollout', value = assignment) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['resource-context', 'org', 'n1'], { sourceConnections: [
    { id: 'ssh', name: 'SSH', active: true, connectorType: 'SSH' },
  ] })
  const panel = (current: ConfigurationAssignmentDetail) => kind === 'deployment'
    ? <ConfigurationDeploymentPanel organizationId="org" assignment={current} />
    : <ConfigurationRolloutPanel organizationId="org" profileId="p1" latestRevisionNumber={1} />
  const wrap = (current: ConfigurationAssignmentDetail) => <I18nProvider initialLocale="en">
    <QueryClientProvider client={client}>{panel(current)}</QueryClientProvider></I18nProvider>
  const view = render(wrap(value))
  return { ...view, changeAssignment: (next: ConfigurationAssignmentDetail) => view.rerender(wrap(next)) }
}

async function approveDeployment() {
  const button = await screen.findByRole('button', { name: 'Preview remote changes' })
  await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false))
  fireEvent.click(button)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Deploy this version' }).hasAttribute('disabled')).toBe(false))
}

async function rolloutPreflightStep() {
  fireEvent.click(await screen.findByRole('checkbox', { name: 'Select Edge' }))
  const next = () => screen.getByRole('button', { name: 'Next' })
  await waitFor(() => expect(next().hasAttribute('disabled')).toBe(false))
  fireEvent.click(next()); fireEvent.click(next()); fireEvent.click(next())
  return next
}
async function approveRollout() {
  const next = await rolloutPreflightStep()
  fireEvent.click(screen.getByRole('button', { name: 'Check servers' }))
  await waitFor(() => expect(next().hasAttribute('disabled')).toBe(false))
  fireEvent.click(next())
}

afterEach(() => { cleanup(); sessionStorage.clear(); vi.unstubAllGlobals() })

describe('configuration submission identity', () => {
  it.each(['deployment', 'rollout'] as const)('recovers the same %s request after a lost response and reload, without an automatic POST', async kind => {
    let attempts = 0
    const calls = backend(call => {
      if (call.body?.requestId) {
        attempts += 1
        if (attempts === 1) return json({ code: 'HTTP_ERROR', message: 'lost response' }, 503)
      }
      return undefined
    })
    const view = mount(kind)
    if (kind === 'deployment') await approveDeployment(); else await approveRollout()
    fireEvent.click(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }))
    expect(await screen.findByRole('button', { name: 'Retry unconfirmed submission' })).toBeTruthy()
    const first = calls.find(call => call.body?.requestId)!.body
    const key = kind === 'deployment' ? 'configuration-deployment-request:org:a1' : 'configuration-rollout-request:org:p1'
    expect(JSON.parse(sessionStorage.getItem(key)!)).toEqual(first)
    expect(sessionStorage.getItem(key)).not.toContain('SECRET_REMOTE_CONTENT')
    view.unmount()
    mount(kind)
    await screen.findByRole('button', { name: 'Retry unconfirmed submission' })
    expect(calls.filter(call => call.body?.requestId)).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Retry unconfirmed submission' }))
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(2))
    expect(calls.filter(call => call.body?.requestId)[1].body).toEqual(first)
    await waitFor(() => expect(sessionStorage.getItem(key)).toBeNull())
  })

  it.each(['deployment', 'rollout'] as const)('retains an unresolved %s while inputs change and blocks a replacement approval', async kind => {
    const calls = backend(call => call.body?.requestId ? json({ code: 'HTTP_ERROR', message: 'lost' }, 500) : undefined)
    mount(kind)
    if (kind === 'deployment') await approveDeployment(); else await approveRollout()
    fireEvent.click(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }))
    await screen.findByRole('button', { name: 'Retry unconfirmed submission' })
    if (kind === 'deployment') {
      fireEvent.change(screen.getByLabelText('Service action'), { target: { value: 'SYSTEMD_RESTART' } })
      expect(screen.getByRole('button', { name: 'Preview remote changes' }).hasAttribute('disabled')).toBe(true)
    } else {
      fireEvent.change(screen.getByLabelText('Pause between batches, seconds'), { target: { value: '30' } })
      fireEvent.click(screen.getByRole('button', { name: 'Back' }))
      expect(screen.getByRole('button', { name: 'Check servers' }).hasAttribute('disabled')).toBe(true)
    }
    fireEvent.click(screen.getByRole('button', { name: 'Retry unconfirmed submission' }))
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(2))
    const submissions = calls.filter(call => call.body?.requestId)
    expect(submissions[1].body).toEqual(submissions[0].body)
  })

  it('rejects a deployment preview that finishes after its assignment changes', async () => {
    let finish!: (response: Response) => void
    backend(call => call.path.endsWith('/deployment-preview') ? new Promise(resolve => { finish = resolve }) : undefined)
    const view = mount('deployment')
    fireEvent.click(await screen.findByRole('button', { name: 'Preview remote changes' }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    view.changeAssignment({ ...assignment, version: 4 })
    finish(json(preview))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Preview remote changes' }).hasAttribute('disabled')).toBe(false))
    expect(screen.getByRole('button', { name: 'Deploy this version' }).hasAttribute('disabled')).toBe(true)
  })

  it('rejects a rollout preflight that finishes after execution changes', async () => {
    let finish!: (response: Response) => void
    let pendingTargets: any[] = []
    backend(call => {
      if (!call.path.endsWith('/preflight')) return undefined
      pendingTargets = call.body.targets
      return new Promise(resolve => { finish = resolve })
    })
    mount('rollout')
    const next = await rolloutPreflightStep()
    fireEvent.click(screen.getByRole('button', { name: 'Check servers' }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    fireEvent.change(screen.getByLabelText('Service action'), { target: { value: 'SYSTEMD_RELOAD' } })
    fireEvent.change(screen.getByLabelText('Systemd unit'), { target: { value: 'nginx.service' } })
    finish(json({ ready: true, items: pendingTargets.map(target => ({ ...target, ready: true,
      desiredSha256: 'b'.repeat(64), connectionUpdatedAt: '2026-10-05T10:00:00Z',
      remote: { exists: true, sha256: 'a'.repeat(64) } })) }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Check servers' }).hasAttribute('disabled')).toBe(false))
    expect(next().hasAttribute('disabled')).toBe(true)
  })

  it.each(['deployment', 'rollout'] as const)('prevents repeated %s submissions while pending', async kind => {
    let finish!: (response: Response) => void
    const calls = backend(call => call.body?.requestId ? new Promise(resolve => { finish = resolve }) : undefined)
    mount(kind)
    if (kind === 'deployment') await approveDeployment(); else await approveRollout()
    const start = screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' })
    fireEvent.click(start); fireEvent.click(start)
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(1))
    expect(start.hasAttribute('disabled')).toBe(true)
    finish(json({ code: 'HTTP_ERROR', message: 'lost' }, 500))
    await screen.findByRole('button', { name: 'Retry unconfirmed submission' })
  })

  it.each(['deployment', 'rollout'] as const)('uses a new identity after a definitive rejection and a new %s approval', async kind => {
    const calls = backend(call => call.body?.requestId ? json({ code: 'CONFIGURATION_REMOTE_DRIFT', message: 'drift' }, 409) : undefined)
    mount(kind)
    if (kind === 'deployment') await approveDeployment(); else await approveRollout()
    fireEvent.click(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }))
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(1))
    await waitFor(() => expect(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }).hasAttribute('disabled')).toBe(true))
    if (kind === 'deployment') await approveDeployment()
    else {
      fireEvent.click(screen.getByRole('button', { name: 'Back' }))
      fireEvent.click(screen.getByRole('button', { name: 'Check servers' }))
      await waitFor(() => expect(screen.getByRole('button', { name: 'Next' }).hasAttribute('disabled')).toBe(false))
      fireEvent.click(screen.getByRole('button', { name: 'Next' }))
      fireEvent.change(screen.getByLabelText('Pause between batches, seconds'), { target: { value: '30' } })
    }
    fireEvent.click(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }))
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(2))
    const submissions = calls.filter(call => call.body?.requestId)
    expect(submissions[1].body.requestId).not.toBe(submissions[0].body.requestId)
    await waitFor(() => expect(sessionStorage.getItem(kind === 'deployment'
      ? 'configuration-deployment-request:org:a1' : 'configuration-rollout-request:org:p1')).toBeNull())
  })

  it.each(['deployment', 'rollout'] as const)('retries a disconnected %s submission using the original identity', async kind => {
    let rejected = false
    const calls = backend(call => {
      if (call.body?.requestId && !rejected) { rejected = true; throw new TypeError('network disconnected') }
      return undefined
    })
    mount(kind)
    if (kind === 'deployment') await approveDeployment(); else await approveRollout()
    fireEvent.click(screen.getByRole('button', { name: kind === 'deployment' ? 'Deploy this version' : 'Start rollout' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry unconfirmed submission' }))
    await waitFor(() => expect(calls.filter(call => call.body?.requestId)).toHaveLength(2))
    const submissions = calls.filter(call => call.body?.requestId)
    expect(submissions[1].body).toEqual(submissions[0].body)
  })
})
