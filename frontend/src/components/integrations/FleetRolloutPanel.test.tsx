// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../../app/queryClient'
import { I18nProvider } from '../../i18n'
import type { FleetMember, FleetRolloutDetail } from '../../types/remnawaveFleet'
import { FleetRolloutPanel } from './FleetRolloutPanel'

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

const members = [{ membershipId: 'm1', nodeName: 'edge-01', assessment: { compliance: 'COMPLIANT' } },
  { membershipId: 'm2', nodeName: 'edge-02', assessment: { compliance: 'DRIFTED' } }] as FleetMember[]

const rollout = (over: Partial<FleetRolloutDetail> = {}): FleetRolloutDetail => ({ id: 'r1', fleetId: 'f1',
  integrationId: 'i1', revisionId: 'rev', state: 'RUNNING', phase: 'APPLY_WAVES', expired: false, currentWave: 0,
  waveCount: 2, pauseAfterCanary: true, automaticRollback: true, rollbackScope: 'CURRENT_WAVE',
  createdAt: '2026-10-04T10:00:00Z', expiresAt: '2026-10-05T10:00:00Z', startedAt: null, finishedAt: null,
  failureCode: null, rollbackIncomplete: false, pauseReason: null, pauseRequested: false,
  rollbackRequested: false, updatedAt: '2026-10-04T10:00:00Z',
  snapshot: { revisionId: 'rev', revisionNumber: 2, policy: { waveSize: 1, canaryMembershipIds: ['m1'],
    pauseAfterCanary: true, automaticRollback: true, rollbackScope: 'CURRENT_WAVE' },
  sharedConfig: { required: false, revisionNumber: 1, externalNodes: 0, externalUnhealthyNodes: 0 },
  waveCount: 2, estimatedMutations: 4, members: [] },
  members: [{ id: 'x1', membershipId: 'm1', nodeName: 'edge-01', wave: 0, position: 0, state: 'UNKNOWN',
    skipReason: null, plannedActions: [], failureCode: null, rollbackFailureCode: null, finishedAt: null }],
  actions: [], ...over })

function mount(current: FleetRolloutDetail | null, extra: (url: string, method: string) => Response | undefined = () => undefined,
  options: { locale?: 'en' | 'ru'; canControl?: boolean } = {}) {
  const calls: { url: string; method: string; body: unknown }[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const custom = extra(url, method)
    if (custom) return custom
    if (url.endsWith('/rollouts') && method === 'GET') return json({ items: current ? [current] : [] })
    if (current && url.endsWith('/rollouts/r1')) return json(current)
    return json({ code: 'NOT_FOUND', message: 'x' }, 404)
  }))
  render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={createAppQueryClient()}>
    <FleetRolloutPanel organizationId="org" integrationId="i1" fleetId="f1" desiredRevisionId="rev"
      members={members} canControl={options.canControl ?? true} /></QueryClientProvider></I18nProvider>)
  return { calls }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('FleetRolloutPanel', () => {
  it('shows UNKNOWN as its own state without a retry action', async () => {
    mount(rollout({ state: 'UNKNOWN', phase: 'APPLY_CANARY' }))
    expect(await screen.findByText('The outcome is unknown')).toBeTruthy()
    expect(screen.queryByText(/retry/i)).toBeNull()
    expect(screen.queryByText('Pause')).toBeNull()
  })

  it('offers pause and rollback only while a rollout is active', async () => {
    mount(rollout())
    expect(await screen.findByText('Pause')).toBeTruthy()
    expect(screen.getByText('Roll back')).toBeTruthy()
  })

  it('previews, then starts with the plan id and one stable request id', async () => {
    const plan = { status: 'READY', planId: 'plan-1', expiresAt: '2026-10-05T10:00:00Z', estimatedMutations: 4,
      warnings: [], plan: { ...rollout().snapshot, members: [{ membershipId: 'm1', inventoryNodeId: 'n1',
        nodeName: 'edge-01', wave: 0, position: 0, skipReason: null, actions: ['SERVER_PROFILE_APPLY'],
        baselineCompliance: 'DRIFTED', baselineHealth: 'HEALTHY' }] } }
    const { calls } = mount(null, (url, method) => {
      if (url.endsWith('/rollouts/preview')) return json(plan)
      if (url.endsWith('/rollouts') && method === 'POST') return json(rollout(), 202)
      return undefined
    })
    fireEvent.click(await screen.findByText('Deploy'))
    fireEvent.click(screen.getByText('Build plan'))
    const startButton = await screen.findByText('Start rollout')
    expect(screen.getByText('Drifted · Healthy')).toBeTruthy()
    fireEvent.click(startButton)
    await waitFor(() => expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/rollouts'))).toBe(true))
    const start = calls.find(c => c.method === 'POST' && c.url.endsWith('/rollouts'))!.body as { planId: string; requestId: string }
    expect(start.planId).toBe('plan-1')
    expect(start.requestId).toMatch(/[0-9a-f-]{36}/)
  })

  it('does not offer to start when evidence must be refreshed', async () => {
    const { calls } = mount(null, url => url.endsWith('/rollouts/preview')
      ? json({ status: 'REFRESH_REQUIRED', issues: [{ code: 'REFRESH_REQUIRED', node: 'edge-01' }] })
      : url.endsWith('/refresh') ? json({ membersDue: 2 }) : undefined)
    fireEvent.click(await screen.findByText('Deploy'))
    fireEvent.click(screen.getByText('Build plan'))
    expect(await screen.findByText('Fresh evidence is needed')).toBeTruthy()
    expect(screen.queryByText('Start rollout')).toBeNull()
    fireEvent.click(screen.getByText('Refresh fleet'))
    expect(await screen.findByText('Refresh queued. Wait for fresh evidence, then build the plan again.')).toBeTruthy()
    expect(calls.some(c => c.method === 'POST' && c.url.endsWith('/refresh'))).toBe(true)
  })

  it.each(['wave size', 'canary', 'automatic rollback', 'pause after canary'])('invalidates the preview after changing %s', async option => {
    const { calls } = mount(null, url => url.endsWith('/rollouts/preview') ? json({ status: 'READY',
      planId: 'plan', expiresAt: '2026-10-05T10:00:00Z', estimatedMutations: 0, warnings: [], plan: rollout().snapshot }) : undefined)
    fireEvent.click(await screen.findByText('Deploy'))
    expect(screen.queryByLabelText('edge-01')).toBeNull()
    fireEvent.click(screen.getByText('Build plan'))
    expect(await screen.findByText('Start rollout')).toBeTruthy()
    const body = calls.find(c => c.url.endsWith('/rollouts/preview'))!.body as { canaryMemberIds: string[] }
    expect(body.canaryMemberIds).toEqual(['m2'])
    if (option === 'wave size') fireEvent.change(screen.getByLabelText('Wave size'), { target: { value: '3' } })
    else fireEvent.click(screen.getByLabelText(option === 'canary' ? 'edge-02'
      : option === 'automatic rollback' ? 'Roll back automatically when a wave fails its health check' : 'Pause after the canary'))
    expect(screen.queryByText('Start rollout')).toBeNull()
    if (option === 'wave size') {
      fireEvent.change(screen.getByLabelText('Wave size'), { target: { value: '2' } })
      expect(screen.queryByText('Start rollout')).toBeNull()
    }
    fireEvent.click(screen.getByText('Build plan'))
    expect(await screen.findByText('Start rollout')).toBeTruthy()
  })

  it('uses completed members for progress and exposes rollback failures in history', async () => {
    const current = rollout()
    current.members[0] = { ...current.members[0], state: 'SUCCEEDED', rollbackFailureCode: 'REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_PROFILE' }
    mount(current)
    expect(await screen.findByText('Wave 1/2 · 1/1 nodes')).toBeTruthy()
    expect(screen.getByText('edge-01: No previous server profile is available')).toBeTruthy()
    fireEvent.click(screen.getByText('Details'))
    expect(await screen.findByText('Succeeded · Rollback failure: No previous server profile is available')).toBeTruthy()
  })

  it('keeps history readable without control permissions', async () => {
    mount(rollout({ state: 'SUCCEEDED', phase: 'COMPLETE' }), undefined, { canControl: false })
    expect(await screen.findByText('Rollout history')).toBeTruthy()
    expect(screen.queryByText('Deploy')).toBeNull()
    expect(screen.queryByText('Pause')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Roll back' })).toBeNull()
    fireEvent.click(screen.getByText('Details'))
    expect(await screen.findByRole('dialog', { name: 'Rollout' })).toBeTruthy()
  })

  it('hides the previous plan while rebuilding, including when the request fails', async () => {
    let attempt = 0
    mount(null, url => {
      if (!url.endsWith('/rollouts/preview')) return undefined
      attempt += 1
      return attempt === 1 ? json({ status: 'READY', planId: 'plan', expiresAt: '2026-10-05T10:00:00Z',
        estimatedMutations: 0, warnings: [], plan: rollout().snapshot }) : json({ code: 'ERROR' }, 500)
    })
    fireEvent.click(await screen.findByText('Deploy'))
    fireEvent.click(screen.getByText('Build plan'))
    expect(await screen.findByText('Start rollout')).toBeTruthy()
    fireEvent.click(screen.getByText('Build plan'))
    expect(screen.queryByText('Start rollout')).toBeNull()
    expect((await screen.findAllByText('The request could not be completed.')).length).toBeGreaterThan(0)
    expect(screen.queryByText('Start rollout')).toBeNull()
  })

  it('localizes backend phases and planner issues in Russian', async () => {
    mount(null, url => url.endsWith('/rollouts/preview') ? json({ status: 'BLOCKED', warnings: [],
      issues: [{ code: 'REMNAWAVE_FLEET_ROLLOUT_CANARY_INVALID', node: null },
        { code: 'MEMBER_COMPLIANCE_UNKNOWN', node: 'edge-01' }] }) : undefined, { locale: 'ru' })
    fireEvent.click(await screen.findByText('Развернуть'))
    fireEvent.click(screen.getByText('Построить план'))
    expect(await screen.findByText('Выберите допустимые канареечные узлы в пределах размера волны')).toBeTruthy()
    expect(screen.getByText('Соответствие узла неизвестно · edge-01')).toBeTruthy()
  })

  it('shows the Russian phase and evidence timeout pause reason', async () => {
    mount(rollout({ state: 'PAUSED', phase: 'VERIFY_CANARY', pauseReason: 'PAUSED_EVIDENCE_TIMEOUT' }), undefined, { locale: 'ru' })
    expect(await screen.findByText('Пауза: свежие данные проверки не поступили. Обновите и проверьте fleet перед продолжением.')).toBeTruthy()
    expect(screen.getAllByText('Проверка канарейки').length).toBeGreaterThan(0)
    expect(screen.queryByText('VERIFY_CANARY')).toBeNull()
  })

  it.each(['en', 'ru'] as const)('shows a resumable stale admission pause in %s', async locale => {
    mount(rollout({ state: 'PAUSED', phase: 'APPLY_WAVES', pauseReason: 'PAUSED_REFRESH_REQUIRED',
      failureCode: 'REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED' }), undefined, { locale })
    expect(await screen.findByText(locale === 'ru'
      ? 'Пауза: данные устарели. Обновите fleet, дождитесь свежих наблюдений и продолжите раскатку.'
      : 'Paused: evidence is stale. Refresh the fleet, wait for fresh observations, then resume.')).toBeTruthy()
    expect(screen.getByText(locale === 'ru' ? 'Продолжить' : 'Resume')).toBeTruthy()
  })

  it.each(['en', 'ru'] as const)('shows exact shared consumers and rollback capabilities in %s', async locale => {
    const plan = { ...rollout().snapshot,
      sharedConfig: { required: true, revisionNumber: 9, baselineRevisionNumber: 8, rollbackSupported: true,
        externalNodes: 1, externalUnhealthyNodes: 0, consumers: [
          { inventoryNodeId: 'n1', externalNodeId: 'e1', nodeName: 'shared-inside', disabled: false, connected: true, inFleet: true },
          { inventoryNodeId: 'n2', externalNodeId: 'e2', nodeName: 'shared-outside', disabled: true, connected: false, inFleet: false },
        ] }, members: [{ membershipId: 'm2', inventoryNodeId: 'n2', nodeName: 'edge-02', wave: 0,
        position: 0, skipReason: null, actions: ['SERVER_PROFILE_APPLY', 'NETWORK_FIREWALL'],
        baselineCompliance: 'DRIFTED', baselineHealth: 'HEALTHY', rollbackCapabilities: [
          { kind: 'SERVER_PROFILE_APPLY', supported: false, reason: 'NO_PREVIOUS_PROFILE' },
          { kind: 'NETWORK_FIREWALL', supported: true, reason: null },
        ] }] }
    mount(null, url => url.endsWith('/rollouts/preview') ? json({ status: 'READY', planId: 'p1', plan,
      expiresAt: '2026-10-05T10:00:00Z', estimatedMutations: 3, warnings: [] }) : undefined, { locale })
    fireEvent.click(await screen.findByText(locale === 'en' ? 'Deploy' : 'Развернуть'))
    fireEvent.click(screen.getByText(locale === 'en' ? 'Build plan' : 'Построить план'))
    expect(await screen.findByText('8 → 9')).toBeTruthy()
    expect(screen.getByText('shared-inside')).toBeTruthy()
    expect(screen.getByText('shared-outside')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Inside this fleet' : 'Внутри этого fleet')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Outside this fleet' : 'Вне этого fleet')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Disabled · Disconnected' : 'Выключен · Отключён')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Available when all affected waves are included' : 'Доступен при включении всех затронутых волн')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Apply server profile: Unavailable · No previous server profile is available'
      : 'Применение серверного профиля: Недоступен · Нет предыдущего серверного профиля')).toBeTruthy()
    expect(screen.getByText(locale === 'en' ? 'Firewall: Available' : 'Файрвол: Доступен')).toBeTruthy()
  })

  it('shows a durable desired-state child id for UNKNOWN and in history', async () => {
    mount(rollout({ state: 'UNKNOWN', actions: [{ id: 'a1', memberId: 'x1', rollback: false,
      kind: 'DESIRED_STATE', sequence: 40, state: 'UNKNOWN', serverProfileRunId: null, configRolloutId: null,
      desiredStateActionId: 'desired-action-1', failureCode: null, finishedAt: null }] }))
    expect(await screen.findByText('desired-action-1')).toBeTruthy()
    fireEvent.click(screen.getByText('Details'))
    expect((await screen.findAllByText('desired-action-1')).length).toBe(2)
  })

  it.each(['en', 'ru'] as const)('invalidates a plan rejected at Start for stale evidence and explains refresh in %s', async locale => {
    const { calls } = mount(null, (url, method) => {
      if (url.endsWith('/rollouts/preview')) return json({ status: 'READY', planId: 'p1',
        plan: rollout().snapshot, expiresAt: '2026-10-06T10:00:00Z', estimatedMutations: 1, warnings: [] })
      if (url.endsWith('/rollouts') && method === 'POST') return json({
        code: 'REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED', message: 'Fresh evidence required' }, 409)
      return undefined
    }, { locale })
    fireEvent.click(await screen.findByText(locale === 'en' ? 'Deploy' : 'Развернуть'))
    fireEvent.click(screen.getByText(locale === 'en' ? 'Build plan' : 'Построить план'))
    fireEvent.click(await screen.findByText(locale === 'en' ? 'Start rollout' : 'Запустить раскатку'))
    expect(await screen.findByText(locale === 'en'
      ? 'Fresh evidence is required. Refresh the fleet before starting or resuming.'
      : 'Нужны свежие данные. Обновите fleet перед запуском или продолжением.')).toBeTruthy()
    expect(screen.queryByText(locale === 'en' ? 'Start rollout' : 'Запустить раскатку')).toBeNull()
    expect(calls.filter(c => c.url.endsWith('/rollouts') && c.method === 'POST')).toHaveLength(1)
  })
})
