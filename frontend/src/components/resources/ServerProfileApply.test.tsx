// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import { emptyServerProfileContent, type ServerProfileAutomation, type ServerProfilePlan } from '../../types/serverProfile'
import { ServerProfileAutomationPanel } from './ServerProfileAutomationPanel'

const base = '/api/v1/organizations/org'
const resourceProfile = `${base}/resources/resource/server-profile`
const plan: ServerProfilePlan = {
  run: { id: 'a839d111-fd69-4daa-8d58-b7eb0a21cc36', state: 'PLANNED' },
  connectionName: 'Production SSH', resourceKind: 'new_vps_test', profileName: 'Edge profile', revisionNumber: 3,
  steps: [{ kind: 'PREFLIGHT', position: 0 }], dependencyPackages: [], endpoint: null,
  assessment: { compliant: false, modules: [], changes: [] }, warnings: [], blockingProblems: [],
}
const automation: ServerProfileAutomation = {
  state: 'DRIFTED', operationsBlocked: false, activeRun: null, observation: null, assessment: plan.assessment,
  assignment: { id: 'assignment', resourceId: 'resource', profileId: 'profile', revisionId: 'revision', revisionNumber: 3, version: 1 },
  profile: { id: 'profile', organizationId: 'org', code: 'edge', name: 'Edge profile', description: null, archived: false,
    latestRevision: 3, createdAt: '2026-10-05T12:00:00Z', updatedAt: '2026-10-05T12:00:00Z' },
  revision: { id: 'revision', profileId: 'profile', number: 3, contentHash: 'hash', content: emptyServerProfileContent(), createdAt: '2026-10-05T12:00:00Z' },
}
const queued = () => Response.json({ id: 'queued-run', state: 'QUEUED' })
const clients: QueryClient[] = []
const getRandomValues = crypto.getRandomValues.bind(crypto)
beforeEach(() => vi.stubGlobal('crypto', {getRandomValues}))

function setup(options: { previewPlan?: ServerProfilePlan; recoveryState?: string; start?: () => Response | Promise<Response> } = {}) {
  const posts: { planId: string; requestId: string }[] = []
  let automationReads = 0
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url === `${base}/provisioning/runs` && init?.method === 'POST') {
      posts.push(JSON.parse(init.body as string))
      return options.start ? options.start() : queued()
    }
    if (url === `${resourceProfile}/preview` && init?.method === 'POST') return Response.json(options.previewPlan ?? plan)
    if (url === `${resourceProfile}/automation`) { automationReads++; return Response.json(automation) }
    if (url === `${base}/provisioning/runs/${plan.run.id}`) return Response.json({run:{...plan.run,state:options.recoveryState ?? 'PLANNED',requestId:null},steps:[]})
    if (url === `${base}/server-profiles/profile`) return Response.json({ profile: automation.profile, revisions: [automation.revision], assignments: [] })
    if (url === `${base}/server-profiles?archived=false&limit=200`) return Response.json({ items: [] })
    if (url === '/api/v1/me/organizations') return Response.json([{ id: 'org', role: 'OWNER' }])
    throw new Error(`Unexpected request: ${init?.method ?? 'GET'} ${url}`)
  })
  vi.stubGlobal('fetch', fetchMock)
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  clients.push(client)
  client.setQueryData(['my-organizations'], [{ id: 'org', role: 'OWNER' }])
  const onRunQueued = vi.fn()
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <ServerProfileAutomationPanel organizationId="org" resourceId="resource" resourceName="Finland VPS" onRunQueued={onRunQueued} />
  </QueryClientProvider></I18nProvider>)
  return { posts, fetchMock, onRunQueued, automationReads: () => automationReads }
}
async function openPreview() {
  fireEvent.click(await screen.findByRole('button', { name: 'Preview changes' }))
  return screen.findByRole('dialog')
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); vi.unstubAllGlobals(); sessionStorage.clear() })

describe('Server Profile Apply HTTP workflow', () => {
  it('recovers an uncertain submission after remount with the original identity and no automatic POST', async () => {
    const first = setup({start:()=>Response.json({code:'HTTP_ERROR',message:'lost'},{status:503})})
    const dialog = await openPreview()
    fireEvent.click(within(dialog).getByRole('button',{name:'Apply reviewed plan'}))
    await within(dialog).findByRole('alert')
    const identity = first.posts[0]
    cleanup(); clients.splice(0).forEach(client=>client.clear())
    const second = setup()
    const retry = await screen.findByRole('button',{name:'Retry unconfirmed submission'}) as HTMLButtonElement
    await waitFor(()=>expect(retry.disabled).toBe(false))
    expect(second.posts).toHaveLength(0)
    fireEvent.click(retry)
    await waitFor(()=>expect(second.onRunQueued).toHaveBeenCalledWith('queued-run'))
    expect(second.posts).toEqual([identity])
    expect(sessionStorage.length).toBe(0)
  })
  it('opens the backend run after reload when the original submission already succeeded', async () => {
    sessionStorage.setItem('server-profile-apply:org:resource',JSON.stringify({planId:plan.run.id,requestId:'d1111111-1111-4111-8111-111111111111'}))
    const test = setup({recoveryState:'QUEUED'})
    await waitFor(()=>expect(test.onRunQueued).toHaveBeenCalledWith(plan.run.id))
    expect(test.posts).toHaveLength(0)
    expect(sessionStorage.length).toBe(0)
  })
  it('posts the reviewed plan and UUID exactly once, closes preview, queues the run and refreshes automation', async () => {
    const test = setup()
    expect(screen.queryByRole('button', { name: 'Apply reviewed plan' })).toBeNull()
    const dialog = await openPreview()
    await waitFor(() => expect(test.automationReads()).toBeGreaterThan(1))
    const readsBeforeStart = test.automationReads()
    const apply = within(dialog).getByRole('button', { name: 'Apply reviewed plan' }) as HTMLButtonElement
    expect(apply.disabled).toBe(false)
    fireEvent.click(apply)
    await waitFor(() => expect(test.onRunQueued).toHaveBeenCalledExactlyOnceWith('queued-run'))
    expect(test.posts).toHaveLength(1)
    expect(test.posts[0]).toEqual({ planId: plan.run.id, requestId: expect.stringMatching(uuid) })
    expect(screen.queryByRole('dialog')).toBeNull()
    await waitFor(() => expect(test.automationReads()).toBeGreaterThan(readsBeforeStart))
  })

  it('keeps the same request ID after a start error and displays that error beside the actions', async () => {
    const start = vi.fn().mockReturnValueOnce(Response.json({ code: 'PROVISIONING_REMOTE_UNAVAILABLE', message: 'raw secret exception' }, { status: 503 }))
      .mockImplementation(queued)
    const test = setup({ start })
    const dialog = await openPreview()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply reviewed plan' }))
    const alert = await within(dialog).findByRole('alert')
    expect(alert.textContent).toContain('Operation could not be started.')
    expect(alert.textContent).toContain('The server connection was lost; the outcome is unknown.')
    expect(dialog.textContent).not.toContain('raw secret exception')
    const actions = within(dialog).getByRole('button', { name: 'Apply reviewed plan' }).parentElement!
    expect(actions.parentElement?.contains(alert)).toBe(true)
    expect(test.onRunQueued).not.toHaveBeenCalled()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply reviewed plan' }))
    await waitFor(() => expect(test.onRunQueued).toHaveBeenCalledExactlyOnceWith('queued-run'))
    expect(test.posts).toHaveLength(2)
    expect(test.posts[0].requestId).toMatch(uuid)
    expect(test.posts[1]).toEqual(test.posts[0])
  })

  it('does not POST a plan with blocking problems', async () => {
    const test = setup({ previewPlan: { ...plan, blockingProblems: ['FIREWALL_RULE_UNSUPPORTED'] } })
    const dialog = await openPreview()
    const apply = within(dialog).getByRole('button', { name: 'Apply reviewed plan' }) as HTMLButtonElement
    expect(apply.disabled).toBe(true)
    fireEvent.click(apply)
    expect(test.posts).toHaveLength(0)
    expect(test.onRunQueued).not.toHaveBeenCalled()
  })

  it('disables pending Apply and cannot POST again until the first request completes', async () => {
    let resolveStart!: (response: Response) => void
    const test = setup({ start: () => new Promise<Response>(resolve => { resolveStart = resolve }) })
    const dialog = await openPreview()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply reviewed plan' }))
    const pending = await within(dialog).findByRole('button', { name: 'Applying profile' }) as HTMLButtonElement
    expect(pending.disabled).toBe(true)
    fireEvent.click(pending)
    expect(test.posts).toHaveLength(1)
    resolveStart(queued())
    await waitFor(() => expect(test.onRunQueued).toHaveBeenCalledExactlyOnceWith('queued-run'))
    expect(test.posts).toHaveLength(1)
  })

  it('clears the whole preview on close and creates a new request ID for a newly reviewed plan', async () => {
    const test = setup({ start: () => Response.json({ code: 'PROVISIONING_PLAN_EXPIRED', message: 'expired' }, { status: 409 }) })
    let dialog = await openPreview()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply reviewed plan' }))
    await within(dialog).findByRole('alert')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('button', { name: 'Apply reviewed plan' })).toBeNull()
    dialog = await openPreview()
    expect(within(dialog).queryByRole('alert')).toBeNull()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Apply reviewed plan' }))
    await within(dialog).findByRole('alert')
    expect(test.posts).toHaveLength(2)
    expect(test.posts[1].requestId).toMatch(uuid)
    expect(test.posts[1].requestId).not.toBe(test.posts[0].requestId)
  })
})
