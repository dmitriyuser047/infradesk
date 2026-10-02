// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import { ServerProfileCreatePage, ServerProfileDetailPage, ServerProfilesListPage } from './ServerProfilesPage'
import { useServerProfileAutomation } from '../api/serverProfiles'
import type { ServerProfile, ServerProfileContent, ServerProfileDetail } from '../types/serverProfile'

const content: ServerProfileContent = {
  schemaVersion: 1,
  packages: { enabled: false, packages: [] }, network: { enabled: false, bbr: false, sysctl: {} },
  limits: { enabled: false, nofileSoft: 65536, nofileHard: 65536, systemdDefaultLimitNofile: 65536 },
  firewall: { enabled: false, rules: [] }, fail2ban: { enabled: false }, docker: { enabled: false },
  caddy: { enabled: false, domain: null, localHttpsPort: 8080, siteRoot: '/var/www/infradesk/default', compression: 'zstd', redirect: 'NONE' },
  site: { enabled: false, domain: null, root: '/var/www/infradesk/default', template: 'DEFAULT_PLACEHOLDER', templateVersion: 1 },
}
const profile: ServerProfile = { id: 'profile-1', organizationId: 'org', name: 'Baseline', code: 'baseline', description: null, archived: false, latestRevision: 1, createdAt: '2026-10-01T00:00:00Z', updatedAt: '2026-10-01T00:00:00Z' }
const detail: ServerProfileDetail = { profile, revisions: [{ id: 'rev-1', profileId: profile.id, number: 1, contentHash: 'a'.repeat(64), content, createdAt: '2026-10-01T00:00:00Z' }], assignments: [] }
function client(role: 'OWNER' | 'MEMBER' = 'OWNER') {
  const value = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  value.setQueryData(['me'], { id: 'user', email: 'user@example.test', displayName: 'User' })
  value.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role }])
  value.setQueryData(['projects', 'org'], [{ id:'project-1', code:'PROJECT', name:'Project' }])
  value.setQueryData(['environments', 'org', null], [])
  value.setQueryData(['environments', 'org', 'project-1'], [])
  return value
}
function page(path: string, opts: { locale?: 'en'|'ru'; role?: 'OWNER'|'MEMBER' } = {}) {
  const qc = client(opts.role)
  return render(<I18nProvider initialLocale={opts.locale ?? 'en'}><QueryClientProvider client={qc}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/organizations/:organizationId/configurations/server-profiles/new" element={<ServerProfileCreatePage />} />
    <Route path="/organizations/:organizationId/configurations/server-profiles/:serverProfileId" element={<ServerProfileDetailPage />} />
    <Route path="/organizations/:organizationId/configurations" element={<ServerProfilesListPage />} />
  </Routes></MemoryRouter></QueryClientProvider></I18nProvider>)
}
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.useRealTimers(); vi.restoreAllMocks() })

describe('server profile screens', () => {
  it('creates a profile from all eight typed module sections and sends structured content', async () => {
    const calls: { method: string; path: string; body?: any }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(String(input), 'http://localhost'); const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, path: url.pathname, body })
      if (method === 'POST' && url.pathname.endsWith('/server-profiles')) return response({ profile, revision: detail.revisions[0] }, 201)
      if (url.pathname.endsWith('/server-profiles')) return response({ items: [] })
      if (url.pathname.endsWith('/profile-1')) return response(detail)
      return response({ code: 'NOT_FOUND', message: 'missing' }, 404)
    }))
    page('/organizations/org/configurations/server-profiles/new')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'Baseline' } })
    fireEvent.change(screen.getByLabelText(/Code/), { target: { value: 'baseline' } })
    for (const label of ['Packages', 'Network tuning', 'Process limits', 'Firewall', 'Fail2ban', 'Docker', 'Caddy HTTPS', 'Placeholder site']) {
      const group = screen.getByRole('group', { name: label })
      fireEvent.click(within(group).getAllByRole('checkbox')[0])
    }
    fireEvent.change(screen.getByLabelText('Packages to install'), { target: { value: 'curl\nca-certificates' } })
    fireEvent.change(within(screen.getByRole('group', { name: 'Caddy HTTPS' })).getByLabelText('Domain'), { target: { value: 'node.example.test' } })
    fireEvent.change(within(screen.getByRole('group', { name: 'Placeholder site' })).getByLabelText('Domain'), { target: { value: 'node.example.test' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create profile' }))
    await waitFor(() => expect(calls.some(call => call.method === 'POST' && call.path.endsWith('/server-profiles'))).toBe(true))
    const sent = calls.find(call => call.method === 'POST' && call.path.endsWith('/server-profiles'))!.body.content as ServerProfileContent
    expect(Object.keys(sent)).toEqual(['schemaVersion','packages','network','limits','firewall','fail2ban','docker','caddy','site'])
    expect(sent.packages).toEqual({ enabled: true, packages: ['curl', 'ca-certificates'] })
    for (const key of ['network','limits','firewall','fail2ban','docker','caddy','site'] as const) expect(sent[key].enabled).toBe(true)
    expect(sent.caddy.domain).toBe('node.example.test')
    expect(await screen.findByRole('heading', { name: 'Baseline' })).toBeTruthy()
  })

  it('renders pinned revisions and assignment names without exposing identifiers', async () => {
    const assigned = { id: 'assignment-uuid', resourceId: 'node-uuid', profileId: profile.id, revisionId: 'rev-uuid', revisionNumber: 1, version: 1, resourceName: 'Finland VPS', resourceActive: true }
    const named = { ...detail, assignments: [assigned] }
    vi.stubGlobal('fetch', vi.fn(async () => response(named)))
    page('/organizations/org/configurations/server-profiles/profile-1?tab=servers')
    expect(await screen.findByText('Finland VPS')).toBeTruthy()
    expect(screen.queryByText(/assignment-uuid|node-uuid|rev-uuid/)).toBeNull()
  })

  it('appends an immutable revision through the typed editor', async () => {
    const calls: { method:string; path:string; body?:any }[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
      const url = new URL(String(input), 'http://localhost'); const method = init?.method ?? 'GET'; const body = init?.body ? JSON.parse(String(init.body)) : undefined
      calls.push({ method, path:url.pathname, body })
      if (url.pathname.endsWith('/server-profiles/profile-1/revisions') && method === 'POST') return response({ id:'rev-2', profileId:profile.id, number:2, contentHash:'b'.repeat(64), content:body.content, createdAt:'2026-10-02T00:00:00Z' }, 201)
      return response(detail)
    }))
    page('/organizations/org/configurations/server-profiles/profile-1')
    fireEvent.click(await screen.findByRole('button', { name:'Save as new revision' }))
    await waitFor(() => expect(calls.some(call => call.method === 'POST' && call.path.endsWith('/revisions'))).toBe(true))
    expect(calls.find(call => call.method === 'POST')!.body.content).toEqual(content)
    expect(await screen.findByRole('status')).toBeTruthy()
  })

  it('keeps archived profile settings read-only', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...detail, profile:{ ...profile, archived:true } })))
    page('/organizations/org/configurations/server-profiles/profile-1')
    expect(await screen.findByText('Read-only profile settings. You need Manage configurations to create a revision.')).toBeTruthy()
    expect(screen.queryByRole('button', { name:'Save as new revision' })).toBeNull()
    expect((screen.getByLabelText('Packages to install') as HTMLTextAreaElement).disabled).toBe(true)
  })

  it('lets read-only members inspect typed settings without edit controls', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(detail)))
    page('/organizations/org/configurations/server-profiles/profile-1', { role:'MEMBER' })
    expect(await screen.findByText('Read-only profile settings. You need Manage configurations to create a revision.')).toBeTruthy()
    expect((screen.getByLabelText('Packages to install') as HTMLTextAreaElement).disabled).toBe(true)
    expect(screen.queryByRole('button', { name:'Save as new revision' })).toBeNull()
  })

  it('compares revision settings in human terms and preserves workspace context on the revision link', async () => {
    const changed = { ...content, packages:{ enabled:true, packages:['curl'] }, firewall:{ enabled:true, rules:[{ id:'ssh', protocol:'tcp', port:2222, sources:['ANY'] }] } }
    const revisions = [detail.revisions[0], { id:'rev-2', profileId:profile.id, number:2, contentHash:'b'.repeat(64), content:changed, createdAt:'2026-10-02T00:00:00Z' }]
    vi.stubGlobal('fetch', vi.fn(async () => response({ ...detail, profile:{ ...profile, latestRevision:2 }, revisions })))
    page('/organizations/org/configurations/server-profiles/profile-1?tab=revisions&project=project-1')
    fireEvent.click((await screen.findAllByText('Compare settings'))[0])
    const comparison = screen.getAllByText('Compare settings')[0].closest('details')
    expect(comparison?.open).toBe(true)
    expect(comparison?.textContent).toContain('Do not manage')
    expect(comparison?.textContent).toContain('Manage this section')
    expect(comparison?.textContent).toContain('TCP/2222')
    expect(comparison?.textContent).not.toContain('"protocol"')
    expect(comparison?.textContent).toContain('curl')
    expect(screen.getByRole('link', { name:'Save as new revision' }).getAttribute('href')).toContain('project=project-1')
  })

  it('shows server profiles to read-only members in Russian and makes no file-profile request', async () => {
    const requests: string[] = []
    vi.stubGlobal('fetch', vi.fn(async (input: string) => { requests.push(String(input)); return response({ items: [] }) }))
    page('/organizations/org/configurations', { role: 'MEMBER', locale: 'ru' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Профили серверов' })).toBeTruthy()
    expect(requests.some(path => path.includes('/server-profiles'))).toBe(true)
    expect(requests.some(path => path.includes('configuration-profiles'))).toBe(false)
    expect(screen.getByRole('tab', { name: 'Файловые конфигурации' })).toBeTruthy()
    expect((screen.getByRole('tab', { name: 'Файловые конфигурации' }) as HTMLButtonElement).disabled).toBe(true)
    expect(screen.getByRole('tab', { name: 'Профили серверов' })).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Новый профиль сервера' })).toBeNull()
  })
})

function AutomationPollingProbe() {
  const query = useServerProfileAutomation('org', 'resource', true)
  return <span>{query.data?.activeRun?.state ?? 'idle'}</span>
}

describe('server profile automation polling', () => {
  it('polls while operations are blocked and stops after the active run becomes terminal', async () => {
    vi.useFakeTimers()
    const active = { state: 'RUNNING', operationsBlocked: true, activeRun: { id: 'run', state: 'RUNNING' }, assignment: null, profile: null, revision: null, observation: null, assessment: null }
    const idle = { ...active, state: 'UNKNOWN', operationsBlocked: false, activeRun: null }
    const fetchMock = vi.fn().mockResolvedValueOnce(response(active)).mockResolvedValueOnce(response(active)).mockResolvedValueOnce(response(idle))
    vi.stubGlobal('fetch', fetchMock)
    const qc = client()
    render(<QueryClientProvider client={qc}><AutomationPollingProbe /></QueryClientProvider>)
    await act(async () => { await Promise.resolve(); await Promise.resolve() })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    await act(async () => { await vi.advanceTimersByTimeAsync(3000) })
    expect(fetchMock).toHaveBeenCalledTimes(3)
    await act(async () => { await vi.advanceTimersByTimeAsync(9000) })
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })
})
