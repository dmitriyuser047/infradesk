// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { I18nProvider } from '../i18n'
import { AdministrationPage, AdministrationUserCreatePage, AdministrationUserPage, OrganizationCreatePage, OrganizationMembersPage } from './AdministrationPages'

const getRandomValues = crypto.getRandomValues.bind(crypto)
beforeEach(() => vi.stubGlobal('crypto', { getRandomValues }))

const user = { id: 'target', email: 'target@example.test', displayName: 'Test account', isActive: true, isAdministrator: false, updatedAt: '2026-10-09T10:00:00Z' }
const member = { user, organizationId: 'org', organizationName: 'First organization', role: 'MEMBER', isActive: true, updatedAt: '2026-10-09T10:01:00Z' }
const page = (items: unknown[], nextCursor: string | null = null) => ({ items, nextCursor })
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })
type Recorded = { url: string; method: string; body: Record<string, unknown> | null }
function setup(path: string, options: { administrator?: boolean; role?: string; request?: (record: Recorded) => Response | Promise<Response> | undefined } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity }, mutations: { retry: false } } })
  client.setQueryData(['me'], { id: 'actor', email: 'actor@example.test', displayName: 'Actor', isAdministrator: options.administrator ?? false })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'first', name: 'First organization', role: options.role ?? 'OWNER' }])
  const requests: Recorded[] = []
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const record = { url: String(input), method: init?.method ?? 'GET', body: init?.body ? JSON.parse(String(init.body)) : null }
    requests.push(record)
    const custom = options.request?.(record); if (custom) return custom
    if (record.url === '/api/v1/me') return json({ id: 'actor', email: 'actor@example.test', displayName: 'Actor', isAdministrator: options.administrator ?? false })
    if (record.url === '/api/v1/me/organizations') return json([{ id: 'org', code: 'first', name: 'First organization', role: options.role ?? 'OWNER' }])
    if (record.url.endsWith('/projects')) return json([])
    if (record.url === '/api/v1/administration/users') return json(page([user]))
    if (record.url === '/api/v1/administration/organizations') return json(page([{ id: 'org', code: 'first', name: 'First organization' }]))
    if (record.url.endsWith('/memberships') || record.url.endsWith('/members')) return json(page([member]))
    if (record.url === '/api/v1/administration/users/target') return json(user)
    return json({ code: 'UNEXPECTED_TEST_REQUEST', message: 'Unexpected request' }, 500)
  }))
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/administration" element={<AdministrationPage />} />
    <Route path="/administration/users/new" element={<AdministrationUserCreatePage />} />
    <Route path="/administration/users/:userId" element={<AdministrationUserPage />} />
    <Route path="/organizations/new" element={<OrganizationCreatePage />} />
    <Route path="/organizations/:organizationId/members" element={<OrganizationMembersPage />} />
    <Route path="/organizations/:organizationId/members/new" element={<AdministrationUserCreatePage />} />
    <Route path="/organizations/:organizationId/overview" element={<div>New organization overview</div>} />
  </Routes></MemoryRouter></QueryClientProvider></I18nProvider>)
  return { client, requests }
}
afterEach(() => { cleanup(); vi.unstubAllGlobals() })
describe('hierarchical administration', () => {
  it('does not load global accounts or show creation controls for an organization owner', async () => {
    const { requests } = setup('/administration')
    expect(await screen.findByText('You do not have permission to manage access here')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Create user' })).toBeNull()
    expect(requests.some(r => r.url.startsWith('/api/v1/administration'))).toBe(false)
  })
  it('loads paginated global users and uses accessible tab panels', async () => {
    const { requests } = setup('/administration', { administrator: true, request: r => r.url.endsWith('users?after=target') ? json(page([{ ...user, id: 'next', displayName: 'Next account' }])) : r.url.endsWith('/users') ? json(page([user], 'target')) : undefined })
    expect(await screen.findByText('Test account')).toBeTruthy()
    const tab = screen.getByRole('tab', { name: 'Users' }); expect(tab.getAttribute('aria-controls')).toBe(screen.getByRole('tabpanel').id)
    fireEvent.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('Next account')).toBeTruthy()
    expect(requests.some(r => r.url.endsWith('users?after=target'))).toBe(true)
  })
  it('prevents members from opening organization user management', async () => {
    const { requests } = setup('/organizations/org/members', { role: 'MEMBER' })
    expect(await screen.findByText('You do not have permission to manage access here')).toBeTruthy()
    expect(requests.some(r => r.url.endsWith('/members'))).toBe(false)
  })
  it('offers administrators lower roles and sends the observed membership version', async () => {
    const { requests } = setup('/organizations/org/members', { role: 'ADMINISTRATOR', request: r => r.method === 'PUT' ? json({ ...member, role: 'OPERATOR' }) : undefined })
    fireEvent.click(await screen.findByRole('button', { name: 'Change access' }))
    const dialog = within(screen.getByRole('dialog'))
    expect(dialog.getAllByRole('option').map(item => item.textContent)).toEqual(['Operator', 'Member'])
    fireEvent.change(dialog.getByRole('combobox'), { target: { value: 'OPERATOR' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(requests.find(r => r.method === 'PUT')?.body).toEqual({ role: 'OPERATOR', isActive: true, expectedUpdatedAt: member.updatedAt }))
  })
  it('creates an organization and retries an unresolved response using the same identity', async () => {
    let attempts = 0
    const { requests } = setup('/organizations/new', { request: r => r.method === 'POST' ? (++attempts === 1 ? json({ code: 'INTERNAL_ERROR', message: 'Unknown' }, 500) : json({ id: 'second', code: 'second', name: 'Second organization' }, 201)) : undefined })
    fireEvent.change(screen.getByRole('textbox', { name: 'Name' }), { target: { value: 'Second organization' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Code' }), { target: { value: 'second' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create organization' }))
    expect(await screen.findByText('Unable to save changes')).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Create organization' }))
    expect(await screen.findByText('New organization overview')).toBeTruthy()
    const bodies = requests.filter(r => r.method === 'POST').map(r => r.body)
    expect(bodies).toHaveLength(2); expect(bodies[0]).toEqual(bodies[1]); expect(bodies[0]?.requestId).toMatch(/^[a-f0-9-]{36}$/)
  })
  it('creates an organization-scoped user without offering global privileges or retaining the password', async () => {
    const { requests, client } = setup('/organizations/org/members/new', { request: r => r.method === 'POST' ? json(user, 201) : undefined })
    fireEvent.change(await screen.findByRole('textbox', { name: 'Display name' }), { target: { value: user.displayName } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Email' }), { target: { value: user.email } })
    fireEvent.change(screen.getByLabelText('Initial password'), { target: { value: 'test-account-password' } })
    expect(screen.queryByRole('checkbox', { name: /Installation administrator/ })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Create user' }))
    await screen.findByRole('heading', { name: 'People and access', level: 1 })
    const input = requests.find(r => r.method === 'POST')?.body
    expect(input).toMatchObject({ organizationId: 'org', role: 'MEMBER', isAdministrator: false, password: 'test-account-password' })
    await waitFor(() => expect(client.getMutationCache().getAll()).toHaveLength(0))
    expect(localStorage.getItem('password')).toBeNull()
  })
  it('shows a protected-last-administrator error without pretending access was changed', async () => {
    setup('/administration/users/target', { administrator: true, request: r => r.method === 'PATCH' ? json({ code: 'LAST_INSTALLATION_ADMINISTRATOR', message: 'Protected' }, 409) : undefined })
    fireEvent.click(await screen.findByRole('button', { name: 'Account settings' }))
    const dialog = within(screen.getByRole('dialog')); fireEvent.click(dialog.getByRole('checkbox', { name: 'Active' }))
    fireEvent.click(dialog.getByRole('button', { name: 'Save changes' }))
    expect(await dialog.findByText('At least one active installation administrator must remain.')).toBeTruthy()
    expect(screen.getByRole('dialog')).toBeTruthy()
  })
  it('hides cached global account data when the administrator permission check fails', async () => {
    const { client } = setup('/administration', { administrator: true })
    await screen.findByText('Test account')
    act(() => client.getQueryCache().find({ queryKey: ['me'] })!.setState({ status: 'error', error: new Error('Permission check unavailable') }))
    expect(await screen.findByText('Unable to check permissions')).toBeTruthy()
    expect(screen.queryByText('Test account')).toBeNull()
    expect(screen.queryByRole('link', { name: 'Create user' })).toBeNull()
  })
  it('reads the exact membership before editing access outside the loaded membership page', async () => {
    const current = { ...member, updatedAt: '2026-10-09T11:00:00Z' }
    const { requests } = setup('/administration/users/target', { administrator: true, request: r =>
      r.url.endsWith('/memberships') ? json(page([{ ...member, organizationId: 'other', organizationName: 'Other organization' }], 'other')) :
      r.url.endsWith('/organizations/org/members/target') ? json(current) : undefined })
    await screen.findByText('Other organization')
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'org' } })
    const add = within(screen.getByRole('combobox').closest('.administration-add-access') as HTMLElement).getByRole('button', { name: 'Change access' })
    await waitFor(() => expect((add as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(add)
    const dialog = within(screen.getByRole('dialog'))
    fireEvent.change(dialog.getByRole('combobox'), { target: { value: 'OPERATOR' } })
    fireEvent.click(dialog.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(requests.find(r => r.method === 'PUT')?.body?.expectedUpdatedAt).toBe(current.updatedAt))
  })
})
