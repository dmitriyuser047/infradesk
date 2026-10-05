// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../../i18n'
import type { ServerProfilePlan } from '../../types/serverProfile'

const mocks = vi.hoisted(() => ({ automation: vi.fn(), profile: vi.fn(), list: vi.fn(), observe: vi.fn(), preview: vi.fn(), assign: vi.fn(), unassign: vi.fn(), start: vi.fn(), startMutation: vi.fn() }))
vi.mock('../../api/serverProfiles', () => ({
  useServerProfile: mocks.profile, useServerProfileAutomation: mocks.automation, useServerProfiles: mocks.list, useObserveServerProfile: mocks.observe,
  usePreviewServerProfile: mocks.preview, useAssignServerProfile: mocks.assign, useUnassignServerProfile: mocks.unassign,
}))
vi.mock('../../api/provisioning', () => ({ useStartProvisioning: mocks.start, useProvisioningRun: () => ({data:undefined}) }))
import { ServerProfileAutomationPanel } from './ServerProfileAutomationPanel'

const plan: ServerProfilePlan = {
  run: { id: 'plan-id', state: 'PLANNED' }, connectionName: 'Production SSH', resourceKind: 'new_vps_test', profileName: 'Edge profile', revisionNumber: 3,
  steps: [{ kind: 'PREFLIGHT', position: 0 }, { kind: 'VERIFY', position: 1 }, { kind: 'INSTALL_PACKAGES', position: 2 }],
  dependencyPackages: ['ca-certificates'], endpoint: 'https://node.example.test:8080',
  assessment: { compliant: false, modules: [], changes: [{ module: 'caddy', code: 'CADDY_LISTENER_DRIFT', detail: 'local HTTPS port', before: '4096', after: '65535' }] },
  warnings: [], blockingProblems: [],
}
function renderPanel(role: 'OWNER'|'MEMBER' = 'OWNER', onQueued = vi.fn(), options: { operationsBlocked?: boolean; previewPlan?: ServerProfilePlan; automationData?: any; locale?: 'en'|'ru'; startPending?: boolean } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id:'user', email:'user@example.test', displayName:'User' })
  client.setQueryData(['my-organizations'], [{ id:'org', code:'ORG', name:'Org', role }])
  mocks.automation.mockReturnValue({ data: options.automationData ?? { state:'DRIFTED', operationsBlocked:options.operationsBlocked ?? false, activeRun:null,
    assignment:{ id:'assignment-secret', resourceId:'resource-secret', profileId:'profile-id', revisionId:'revision-secret', revisionNumber:3, version:2 },
    profile:{ id:'profile-id', name:'Edge profile' }, revision:{ number:3 }, assessment:plan.assessment }, isError:false, refetch:vi.fn() })
  mocks.list.mockReturnValue({ data:{ items:[] } })
  mocks.profile.mockReturnValue({ data:{ revisions:[{ id:'rev-3', number:3 }, { id:'rev-4', number:4 }] } })
  mocks.observe.mockReturnValue({ mutateAsync:vi.fn().mockResolvedValue({}), isPending:false, isError:false })
  mocks.preview.mockReturnValue({ mutateAsync:vi.fn().mockResolvedValue(options.previewPlan ?? plan), isPending:false, isError:false })
  mocks.assign.mockReturnValue({ mutateAsync:vi.fn().mockResolvedValue({}), isPending:false, isError:false })
  mocks.unassign.mockReturnValue({ mutateAsync:vi.fn().mockResolvedValue({}), isPending:false, isError:false })
  mocks.start.mockReturnValue({ mutateAsync:mocks.startMutation, reset:vi.fn(), isPending:options.startPending ?? false, isError:false })
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}><ServerProfileAutomationPanel organizationId="org" resourceId="resource" resourceName="Finland VPS" onRunQueued={onQueued} /></QueryClientProvider></I18nProvider>)
  return { onQueued }
}
afterEach(() => { cleanup(); vi.clearAllMocks(); sessionStorage.clear() })

describe('server profile resource automation', () => {
  it('keeps a pending application dialog open and disables both dismissal actions', async () => {
    renderPanel('OWNER', vi.fn(), {startPending:true})
    fireEvent.click(screen.getByRole('button', {name:'Preview changes'}))
    const dialog = await screen.findByRole('dialog')
    expect((within(dialog).getByRole('button', {name:'Cancel'}) as HTMLButtonElement).disabled).toBe(true)
    expect((within(dialog).getByRole('button', {name:'Close'}) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.keyDown(dialog, {key:'Escape'})
    fireEvent.mouseDown(dialog.parentElement!)
    expect(screen.getByRole('dialog')).toBe(dialog)
  })
  it('shows an unknown blocker before changes and steps with a truthful disabled explanation', async () => {
    renderPanel('OWNER', vi.fn(), { previewPlan:{ ...plan, blockingProblems:['NEW_BACKEND_BLOCKER'] } })
    fireEvent.click(screen.getByRole('button', { name:'Preview changes' }))
    const dialog = await screen.findByRole('dialog')
    const blocker = within(dialog).getByRole('alert')
    expect(blocker.textContent).toContain('InfraDesk detected a problem that prevents this operation from being performed safely.')
    expect(dialog.textContent).not.toContain('The profile or server state changed.')
    const changes = within(dialog).getByRole('heading', { name:'Planned changes' })
    const steps = within(dialog).getByText('Execution steps (3)')
    expect(blocker.compareDocumentPosition(changes) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(changes.compareDocumentPosition(steps) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(dialog).getByRole('status').textContent).toContain('Cannot apply: 1 blocking problem detected.')
    expect((within(dialog).getByRole('button', { name:'Apply reviewed plan' }) as HTMLButtonElement).disabled).toBe(true)
  })
  it('separates workflow from assignment management while preserving Observe and Preview requests', async () => {
    renderPanel('OWNER')
    const observe = mocks.observe.mock.results.at(-1)?.value.mutateAsync
    const preview = mocks.preview.mock.results.at(-1)?.value.mutateAsync
    const workflow = screen.getByRole('region', { name:'Check and preview' })
    fireEvent.click(within(workflow).getByRole('button', { name:'Observe server' }))
    expect(observe).toHaveBeenCalledWith()
    fireEvent.click(within(workflow).getByRole('button', { name:'Preview changes' }))
    await screen.findByRole('dialog')
    expect(preview).toHaveBeenCalledWith()
    expect(within(workflow).queryByRole('combobox')).toBeNull()
    expect(within(screen.getByRole('region', { name:'Manage assignment' })).getByRole('combobox', { name:'Revision' })).toBeTruthy()
  })
  it.each([
    ['en', 'Preview changes', 'Apply reviewed plan', 'InfraDesk detected a UFW rule or output format that it cannot safely manage. Review the current firewall rules.', 'The profile or server state changed.'],
    ['ru', 'Предпросмотр изменений', 'Применить проверенный план', 'InfraDesk обнаружил правило или формат UFW, который не может безопасно обработать. Проверьте текущие правила сетевого экрана.', 'Профиль или состояние сервера изменились.'],
  ] as const)('explains unsupported UFW output in %s and keeps Apply blocked', async (locale, preview, apply, message, fallback) => {
    renderPanel('OWNER', vi.fn(), { locale, previewPlan:{ ...plan, blockingProblems:['FIREWALL_RULE_UNSUPPORTED'] } })
    fireEvent.click(screen.getByRole('button', { name:preview }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain(message)
    expect(dialog.textContent).not.toContain(fallback)
    expect(dialog.textContent).not.toContain('FIREWALL_RULE_UNSUPPORTED')
    expect((screen.getByRole('button', { name:apply }) as HTMLButtonElement).disabled).toBe(true)
    expect(mocks.startMutation).not.toHaveBeenCalled()
  })

  it('reviews the human-readable target and diff, then retries Apply with the same request ID', async () => {
    const onQueued = vi.fn()
    const start = vi.fn().mockRejectedValueOnce(new Error('connection lost')).mockResolvedValue({ id:'run-queued', state:'QUEUED' })
    mocks.startMutation = start
    renderPanel('OWNER', onQueued)
    fireEvent.click(screen.getByRole('button', { name:'Preview changes' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('Finland VPS')
    expect(dialog.textContent).toContain('new_vps_test')
    expect(dialog.textContent).toContain('Production SSH')
    expect(dialog.textContent).toContain('Caddy is not listening on the requested HTTPS port.')
    expect(within(dialog).getByText('4096')).toBeTruthy()
    expect(within(dialog).getByText('65535')).toBeTruthy()
    expect(dialog.textContent).toContain('https://node.example.test:8080')
    expect(dialog.textContent).not.toContain('revision-secret')
    const apply = screen.getByRole('button', { name:'Apply reviewed plan' })
    fireEvent.click(apply)
    await waitFor(() => expect(start).toHaveBeenCalledTimes(1))
    fireEvent.click(apply)
    await waitFor(() => expect(start).toHaveBeenCalledTimes(2))
    expect(start.mock.calls[0][0]).toEqual(start.mock.calls[1][0])
    expect(start.mock.calls[0][0].planId).toBe('plan-id')
    expect(onQueued).toHaveBeenCalledWith('run-queued')
  })

  it('keeps Observe and Apply unavailable to read-only members', () => {
    renderPanel('MEMBER')
    expect(screen.queryByRole('button', { name:'Observe server' })).toBeNull()
    expect(screen.queryByRole('button', { name:'Preview changes' })).toBeNull()
    expect(screen.getByText('You can view profiles, but cannot change or apply them.')).toBeTruthy()
  })

  it('lets a manager explicitly pin a different immutable revision', async () => {
    renderPanel('OWNER')
    const assign = mocks.assign.mock.results.at(-1)?.value.mutateAsync as ReturnType<typeof vi.fn>
    fireEvent.change(screen.getByLabelText('Revision'), { target:{ value:'4' } })
    fireEvent.click(screen.getByRole('button', { name:'Pin selected revision' }))
    await waitFor(() => expect(assign).toHaveBeenCalledWith({ profileId:'profile-id', revisionNumber:4 }))
  })

  it('blocks Observe and Preview while any operation is active', () => {
    renderPanel('OWNER', vi.fn(), { operationsBlocked:true })
    expect(screen.queryByRole('button', { name:'Observe server' })).toBeNull()
    expect(screen.queryByRole('button', { name:'Preview changes' })).toBeNull()
    expect(screen.getByText('Wait for the active server operation to finish before observing, assigning, or previewing.')).toBeTruthy()
  })

  it('labels disabled modules as unmanaged instead of compliant', () => {
    renderPanel('OWNER', vi.fn(), { automationData: { state:'COMPLIANT', operationsBlocked:false, activeRun:null,
      assignment:{ id:'a', resourceId:'r', profileId:'p', revisionId:'v', revisionNumber:3, version:1 }, profile:{ id:'p', name:'Edge' },
      revision:{ number:3, content:{ packages:{enabled:false}, network:{enabled:false}, limits:{enabled:false}, firewall:{enabled:false}, fail2ban:{enabled:false}, docker:{enabled:false}, caddy:{enabled:false}, site:{enabled:false} } },
      assessment:{ modules:[{module:'caddy', compliant:true}], changes:[] } } })
    expect(screen.getByText('Do not manage')).toBeTruthy()
    expect(screen.getByText('Do not manage').closest('.server-profile-module-status')?.querySelector('.status-success')).toBeNull()
  })

  it('keeps Apply disabled when the server-specific preview has blockers', async () => {
    renderPanel('OWNER', vi.fn(), { previewPlan:{ ...plan, blockingProblems:['PROVISIONING_PROFILE_PLAN_CHANGED'] } })
    fireEvent.click(screen.getByRole('button', { name:'Preview changes' }))
    await screen.findByRole('dialog')
    expect((screen.getByRole('button', { name:'Apply reviewed plan' }) as HTMLButtonElement).disabled).toBe(true)
  })

  it('localizes known preview findings and blocker explanations in Russian', async () => {
    renderPanel('OWNER', vi.fn(), { locale:'ru', previewPlan:{ ...plan, blockingProblems:['FIREWALL_OWNERSHIP_COLLISION'] } })
    fireEvent.click(screen.getByRole('button', { name:'Предпросмотр изменений' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('Caddy не слушает заданный HTTPS-порт.')
    expect(dialog.textContent).toContain('Правило сетевого экрана конфликтует с неуправляемым правилом')
    expect(dialog.textContent).not.toContain('FIREWALL_OWNERSHIP_COLLISION')
  })
})
