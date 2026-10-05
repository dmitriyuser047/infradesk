// @vitest-environment jsdom
import { QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createAppQueryClient } from '../../app/queryClient'
import { I18nProvider } from '../../i18n'
import type { Fleet, FleetCandidate, FleetDetail, FleetRevision, FleetRevisionImpact } from '../../types/remnawaveFleet'
import { FleetSection } from './FleetSection'

const summary = (over: Partial<Fleet['summary']> = {}): Fleet['summary'] => ({ totalNodes: 3, compliant: 1,
  drifted: 1, unknown: 1, blocked: 0, healthy: 2, degraded: 1, healthUnknown: 0, assessed: 3,
  lastAssessmentAt: '2026-10-04T11:58:00Z', oldestEvidenceAt: '2026-10-04T11:50:00Z', ...over })

const fleet = (over: Partial<Fleet> = {}): Fleet => ({ id: 'fleet-1', code: 'production', name: 'Production VPN',
  description: 'Europe', desiredRevisionId: 'revision-1', desiredRevisionNumber: 5, version: 4,
  archived: false, createdAt: '2026-10-01T10:00:00Z', updatedAt: '2026-10-04T10:00:00Z',
  summary: summary(), ...over })

const revision = (number: number, desired: boolean, id = `revision-${number}`): FleetRevision => ({ id, number,
  schemaVersion: 1, contentHash: 'a'.repeat(64), createdAt: '2026-10-02T10:00:00Z', desired,
  desiredConfiguration: { serverProfileId: 'sp-1', serverProfileRevisionNumber: number,
    inventoryConfigProfileId: 'cp-object', externalConfigProfileId: 'cp-external',
    configurationProfileId: 'cp-1', configRevisionNumber: number + 3, activeInboundIds: ['inbound-1'],
    nodePort: 2222, panelCidrs: ['198.51.100.0/24'], desiredNodeState: 'ENABLED' } })

const detail = (over: Partial<FleetDetail> = {}): FleetDetail => ({
  fleet: fleet(), desiredRevision: revision(5, true, 'revision-1'),
  serverProfileName: 'VPN Production', configurationProfileName: 'Reality Production',
  revisions: [revision(6, false, 'revision-2'), revision(5, true, 'revision-1')],
  members: [
    { membershipId: 'member-1', membershipVersion: 1, inventoryNodeId: 'node-1', resourceId: 'resource-1',
      nodeName: 'edge-01', nodeAddress: '192.0.2.10', countryCode: 'NL', resourceName: 'Frankfurt VPS',
      resourceActive: true, connected: true, disabled: false, localManaged: true,
      actualServerProfileName: 'VPN Production', actualServerRevisionNumber: 4,
      actualConfigProfileName: 'Reality Production', actualConfigProfileExternalId: 'cp-external',
      actualInboundIds: ['inbound-1'], actualDesiredNodeState: 'ENABLED',
      assessment: { fleetRevisionId: 'revision-1', compliance: 'DRIFTED', health: 'HEALTHY',
        driftReasons: ['SERVER_PROFILE_ASSIGNMENT_MISMATCH'], healthReasons: [], rolloutBlockers: [],
        inventoryObservedAt: '2026-10-04T11:55:00Z', serverObservedAt: '2026-10-04T11:50:00Z',
        localObservedAt: '2026-10-04T11:55:00Z', oldestEvidenceAt: '2026-10-04T11:50:00Z',
        computedAt: '2026-10-04T11:58:00Z' } },
    { membershipId: 'member-2', membershipVersion: 1, inventoryNodeId: 'node-2', resourceId: 'resource-2',
      nodeName: 'legacy-01', nodeAddress: '192.0.2.11', countryCode: null, resourceName: 'Legacy VPS',
      resourceActive: true, connected: false, disabled: false, localManaged: false,
      actualServerProfileName: null, actualServerRevisionNumber: null,
      actualConfigProfileName: null, actualConfigProfileExternalId: null,
      actualInboundIds: null, actualDesiredNodeState: null,
      assessment: { fleetRevisionId: 'revision-1', compliance: 'UNKNOWN', health: 'DEGRADED',
        driftReasons: ['LOCAL_MANAGEMENT_UNAVAILABLE', 'INVENTORY_STALE'],
        healthReasons: ['NODE_DISCONNECTED'],
        rolloutBlockers: ['NO_MANAGED_LOCAL_INSTALLATION', 'UNKNOWN_REMOTE_STATE'],
        inventoryObservedAt: '2026-10-04T10:00:00Z', serverObservedAt: null, localObservedAt: null,
        oldestEvidenceAt: '2026-10-04T10:00:00Z', computedAt: '2026-10-04T11:58:00Z' } },
  ], ...over })

const impact: FleetRevisionImpact = { currentRevisionNumber: 5, candidateRevisionNumber: 6,
  currentServerProfileName: 'VPN Production', candidateServerProfileName: 'VPN Production',
  currentServerRevisionNumber: 5, candidateServerRevisionNumber: 6,
  currentConfigProfileName: 'Reality Production', candidateConfigProfileName: 'Reality Production',
  currentConfigRevisionNumber: 8, candidateConfigRevisionNumber: 9,
  currentPanelCidrs: ['198.51.100.0/24'], candidatePanelCidrs: ['198.51.100.0/24', '203.0.113.0/24'],
  currentNodePort: 2222, candidateNodePort: 2222,
  currentDesiredNodeState: 'ENABLED', candidateDesiredNodeState: 'ENABLED',
  members: 8, compliantAfter: 0, expectedDrift: 7, unknown: 1, blocked: 0 }

const candidates: FleetCandidate[] = [
  { inventoryNodeId: 'node-3', externalId: 'ext-3', nodeName: 'edge-03', resourceId: 'resource-3',
    resourceName: 'Amsterdam VPS', eligible: true, blockedBy: null, currentFleetName: null },
  { inventoryNodeId: 'node-1', externalId: 'ext-1', nodeName: 'edge-01', resourceId: 'resource-1',
    resourceName: 'Frankfurt VPS', eligible: false, blockedBy: 'REMNAWAVE_FLEET_NODE_ALREADY_MEMBER',
    currentFleetName: 'Production VPN' },
]

const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })

function mount(entry = '/', locale: 'en' | 'ru' = 'en', role = 'OWNER',
  overrides: (url: string, method: string) => Response | undefined = () => undefined,
  fleetDetail: FleetDetail = detail()) {
  const calls: { url: string; method: string; body: unknown }[] = []
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input); const method = init?.method ?? 'GET'
    calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
    const custom = overrides(url, method)
    if (custom) return custom
    if (url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'ORG', name: 'Org', role }])
    if (url.endsWith('/remnawave-fleets') && method === 'GET') return json({ items: [fleetDetail.fleet] })
    if (url.endsWith('/remnawave-fleets/candidates')) return json({ items: candidates })
    if (url.endsWith('/remnawave-fleets/fleet-1') && method === 'GET') return json(fleetDetail)
    if (url.includes('/revisions/revision-2/preview')) return json(impact)
    if (url.includes('/revisions/revision-2/promote')) return json(fleetDetail.fleet)
    if (url.endsWith('/refresh')) return json({ membersDue: 2 })
    if (url.endsWith('/members') && method === 'POST') return json({ membershipId: 'member-3' })
    if (url.includes('/members/member-1')) return new Response(null, { status: 204 })
    if (url.endsWith('/remnawave-fleets') && method === 'POST') return json(fleetDetail.fleet)
    if (url.includes('/server-profiles')) return json({ items: [] })
    if (url.endsWith('/remnawave-node-onboarding/options')) return json({ nodeApi: null, servers: [], profiles: [] })
    return json({ code: 'NOT_FOUND', message: 'not found' }, 404)
  })
  vi.stubGlobal('fetch', fetchMock)
  render(<I18nProvider initialLocale={locale}>
    <QueryClientProvider client={createAppQueryClient()}>
      <MemoryRouter initialEntries={[entry]}>
        <FleetSection organizationId="org" integrationId="integration" />
      </MemoryRouter>
    </QueryClientProvider>
  </I18nProvider>)
  return { calls }
}

afterEach(() => { cleanup(); vi.unstubAllGlobals() })

describe('FleetSection', () => {
  it('shows revision impact preview failures instead of silently clearing the dialog',async()=>{
    mount('/?fleet=fleet-1','en','OWNER',url=>url.includes('/revisions/revision-2/preview') ? json({code:'HTTP_ERROR',message:'raw secret' },503) : undefined)
    fireEvent.click((await screen.findAllByText('Impact of this revision'))[0])
    expect((await screen.findAllByText('The request could not be completed.')).length).toBeGreaterThan(0)
    expect(screen.queryByText('raw secret')).toBeNull()
  })
  it('lists fleets with their compliance and health counts', async () => {
    mount()
    expect(await screen.findByText('Production VPN')).toBeTruthy()
    expect(screen.getByText('Desired revision: 5')).toBeTruthy()
    const card = screen.getByText('Production VPN').closest('article')!
    expect(within(card).getByText('Compliant').nextElementSibling?.textContent).toBe('1')
    expect(within(card).getByText('Drifted').nextElementSibling?.textContent).toBe('1')
    expect(within(card).getByText('Unknown').nextElementSibling?.textContent).toBe('1')
    expect(within(card).getByText('Degraded').nextElementSibling?.textContent).toBe('1')
  })

  it('shows an empty state and no create entry without the manage permissions', async () => {
    mount('/', 'en', 'MEMBER', url => url.endsWith('/remnawave-fleets') ? json({ items: [] }) : undefined)
    expect(await screen.findByText('No fleets yet')).toBeTruthy()
    expect(screen.queryByText('Create fleet')).toBeNull()
  })

  it('shows members with compliance, health and rollout eligibility separately', async () => {
    mount('/?fleet=fleet-1')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    const drifted = screen.getByText('edge-01').closest('tr')!
    expect(within(drifted).getByText('Drifted')).toBeTruthy()
    // The configuration differs while the node itself is fine: two independent verdicts.
    expect(within(drifted).getByText('Healthy')).toBeTruthy()
    expect(within(drifted).getByText('No blockers')).toBeTruthy()
    const unknown = screen.getByText('legacy-01').closest('tr')!
    expect(within(unknown).getByText('Unknown')).toBeTruthy()
    expect(within(unknown).getByText('Degraded')).toBeTruthy()
    expect(unknown.textContent).toContain('No managed local installation')
  })

  it('explains an unmanaged legacy node and both kinds of reason in the member detail', async () => {
    mount('/?fleet=fleet-1')
    fireEvent.click(await screen.findByText('legacy-01'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getAllByText('Not managed by InfraDesk').length).toBeGreaterThan(0)
    expect(within(dialog).getByText(/InfraDesk has no record of installing this node/)).toBeTruthy()
    expect(within(dialog).getByText(/InfraDesk does not manage this local installation/)).toBeTruthy()
    expect(within(dialog).getByText(/The inventory is out of date/)).toBeTruthy()
    expect(within(dialog).getByText(/The node is not connected/)).toBeTruthy()
    // The technical codes stay visible beside the readable text.
    expect(dialog.textContent).toContain('LOCAL_MANAGEMENT_UNAVAILABLE')
  })

  it('previews the impact of a candidate revision without promoting it', async () => {
    const { calls } = mount('/?fleet=fleet-1')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    fireEvent.click(screen.getAllByText('Impact of this revision')[0])
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('Revision').nextElementSibling?.textContent).toBe('5 → 6')
    expect(within(dialog).getByText('Remnawave configuration profile').nextElementSibling?.textContent)
      .toBe('8 → 9')
    expect(within(dialog).getByText('Expected drift').nextElementSibling?.textContent).toBe('7')
    expect(within(dialog).getByText('Compliant after change').nextElementSibling?.textContent).toBe('0')
    expect(calls.some(call => call.url.includes('/preview'))).toBe(true)
    expect(calls.some(call => call.url.includes('/promote'))).toBe(false)
  })

  it('promotes a revision as metadata and says that members now differ', async () => {
    const { calls } = mount('/?fleet=fleet-1')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    fireEvent.click(screen.getByText('Make desired'))
    await waitFor(() => expect(screen.getByText(/This revision is now the desired state/)).toBeTruthy())
    const promote = calls.find(call => call.url.includes('/promote'))!
    expect(promote.method).toBe('POST')
    expect(promote.body).toEqual({ expectedVersion: 4 })
  })

  it('refreshes state as an observation and never applies anything', async () => {
    const { calls } = mount('/?fleet=fleet-1')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    fireEvent.click(screen.getByText('Refresh state'))
    await waitFor(() => expect(screen.getByText('2 members will be checked again.')).toBeTruthy())
    expect(calls.filter(call => call.url.endsWith('/refresh')).length).toBe(1)
    expect(calls.some(call => call.url.includes('/promote') || call.url.includes('/deployments'))).toBe(false)
  })

  it('only offers nodes that may join, and names why the others cannot', async () => {
    mount('/?fleet=fleet-1')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    fireEvent.click(screen.getByText('Add member'))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('edge-03 · Amsterdam VPS')).toBeTruthy()
    expect(within(dialog).getByText(/edge-01: Production VPN/)).toBeTruthy()
  })

  it('warns that a view resting on incomplete evidence needs a refresh', async () => {
    mount('/?fleet=fleet-1', 'en', 'OWNER', () => undefined,
      detail({ fleet: fleet({ summary: summary({ assessed: 1 }) }) }))
    expect(await screen.findByText('Needs a refresh')).toBeTruthy()
    expect(screen.getByText(/older than the freshness window/)).toBeTruthy()
  })

  it('hides every change entry from a reader and keeps the observed state visible', async () => {
    mount('/?fleet=fleet-1', 'en', 'MEMBER')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    expect(screen.getByText('edge-01')).toBeTruthy()
    expect(screen.queryByText('Make desired')).toBeNull()
    expect(screen.queryByText('Create revision')).toBeNull()
    expect(screen.queryByText('Refresh state')).toBeNull()
    expect(screen.queryByText('Add member')).toBeNull()
    // Reading the impact of a revision is still allowed.
    expect(screen.getAllByText('Impact of this revision').length).toBeGreaterThan(0)
  })

  it('renders the fleet in Russian', async () => {
    mount('/?fleet=fleet-1', 'ru')
    expect(await screen.findByText('Fleet: Production VPN')).toBeTruthy()
    expect(screen.getByText('Обновить состояние')).toBeTruthy()
    expect(screen.getByText('Сделать желаемой')).toBeTruthy()
    const drifted = screen.getByText('edge-01').closest('tr')!
    expect(within(drifted).getByText('Расхождение')).toBeTruthy()
    expect(within(drifted).getByText('Здоров')).toBeTruthy()
  })
})
