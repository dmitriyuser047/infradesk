// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { AppShell } from '../components/layout/AppShell'
import { I18nProvider, type Locale } from '../i18n'
import type { ConfigurationProfile, ConfigurationRevision } from '../types/configuration'
import { ConfigurationCreatePage, ConfigurationVersionPage } from './ConfigurationEditorPages'
import { ConfigurationProfilePage } from './ConfigurationProfilePage'
import { ConfigurationsPage } from './ConfigurationsPage'

const owner = { id: 'user', displayName: 'Dmitriy' }
const template = '{\n  "port": {{ port }}\n}'

function revision(number: number, text = template): ConfigurationRevision {
  return {
    revisionNumber: number, template: text, createdAt: `2026-09-2${number}T10:00:00Z`, createdBy: owner,
    variables: [{ name: 'port', type: 'INTEGER', required: true, defaultValue: '443', description: 'Listen port' }],
  }
}

function profile(overrides: Partial<ConfigurationProfile> = {}): ConfigurationProfile {
  return {
    id: 'vpn', code: 'vpn-production', name: 'VPN Production Nodes', description: null, archived: false,
    latestRevisionNumber: 1, latestRevisionCreatedAt: '2026-09-21T10:00:00Z', createdAt: '2026-09-21T10:00:00Z',
    updatedAt: '2026-09-21T10:00:00Z', ...overrides,
  }
}

/** A small in-memory backend for the configuration endpoints, recording every request. */
function backend(initial: { profiles?: ConfigurationProfile[]; revisions?: ConfigurationRevision[] } = {}) {
  const state = { profiles: initial.profiles ?? [], revisions: initial.revisions ?? [] }
  const requests: { method: string; path: string; body?: unknown }[] = []
  const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body),
    { status, headers: { 'Content-Type': 'application/json' } }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((input: string, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const path = url.pathname.replace('/api/v1/organizations/org/configuration-profiles', '')
    const method = init?.method ?? 'GET'
    const body = init?.body ? JSON.parse(String(init.body)) : undefined
    requests.push({ method, path: path + url.search, body })
    const current = state.profiles[0]
    const latest = () => state.revisions.find(item => item.revisionNumber === current.latestRevisionNumber)!
    if (method === 'GET' && path === '') return json(state.profiles.filter(item => item.archived === (url.searchParams.get('archived') === 'true')))
    if (method === 'POST' && path === '/validate') {
      const undefinedPort = body.template.includes('vpn_port')
      return json(undefinedPort
        ? { valid: false, referencedVariables: ['vpn_port'], renderedPreview: null, previewError: null, diagnostics: [
          { code: 'CONFIGURATION_VARIABLE_UNDEFINED', severity: 'ERROR', variableName: 'vpn_port', line: 1, column: 6 }] }
        : { valid: true, referencedVariables: ['port'], diagnostics: [], previewError: null,
          renderedPreview: body.template.replace('{{ port }}', body.variables[0]?.defaultValue ?? '') })
    }
    if (method === 'POST' && path === '') {
      const created = profile({ id: 'created', code: body.code, name: body.name })
      state.profiles = [created, ...state.profiles]
      const first = { ...revision(1, body.template), variables: body.variables }
      state.revisions = [first]
      return json({ profile: created, latestRevision: first }, 201)
    }
    if (method === 'GET' && /^\/[^/]+$/.test(path)) return json({ profile: current, latestRevision: latest() })
    if (method === 'PATCH') {
      state.profiles = [{ ...current, name: body.name, description: body.description }]
      return json(state.profiles[0])
    }
    if (method === 'DELETE') {
      state.profiles = [{ ...current, archived: true }]
      return json(state.profiles[0])
    }
    if (method === 'POST' && path.endsWith('/revisions')) {
      const next = { ...revision(current.latestRevisionNumber + 1, body.template), variables: body.variables }
      state.revisions = [...state.revisions, next]
      state.profiles = [{ ...current, latestRevisionNumber: next.revisionNumber }]
      return json(next, 201)
    }
    if (method === 'GET' && path.endsWith('/revisions')) {
      return json([...state.revisions].reverse().map(item => ({ revisionNumber: item.revisionNumber,
        variableCount: item.variables.length, createdBy: item.createdBy, createdAt: item.createdAt })))
    }
    const single = path.match(/\/revisions\/(\d+)$/)
    if (method === 'GET' && single) return json(state.revisions.find(item => item.revisionNumber === Number(single[1])))
    return json({ code: 'NOT_FOUND', message: 'x' }, 404)
  }))
  return { state, requests }
}

function Where() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderApp(path: string, options: { role?: 'OWNER' | 'MEMBER'; locale?: Locale } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: options.role ?? 'OWNER' }])
  client.setQueryData(['projects', 'org'], [])
  return render(<I18nProvider initialLocale={options.locale ?? 'en'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/organizations/:organizationId/configurations" element={<ConfigurationsPage />} />
      <Route path="/organizations/:organizationId/configurations/new" element={<ConfigurationCreatePage />} />
      <Route path="/organizations/:organizationId/configurations/:profileId" element={<ConfigurationProfilePage />} />
      <Route path="/organizations/:organizationId/configurations/:profileId/versions/new" element={<ConfigurationVersionPage />} />
      <Route path="/organizations/:organizationId/overview" element={<AppShell><h1>Overview</h1></AppShell>} />
    </Routes><Where /></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

const location = () => screen.getByTestId('location').textContent

describe('configuration profiles', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

  it('appears in the navigation for an owner only', async () => {
    backend()
    renderApp('/organizations/org/overview')
    expect(screen.getByRole('link', { name: 'Configurations' }).getAttribute('href')).toBe('/organizations/org/configurations')
    cleanup()
    renderApp('/organizations/org/overview', { role: 'MEMBER' })
    expect(screen.queryByRole('link', { name: 'Configurations' })).toBeNull()
    cleanup()
    // Hidden navigation is not the security: the page itself refuses a member, and asks for nothing.
    const { requests } = backend()
    renderApp('/organizations/org/configurations', { role: 'MEMBER' })
    expect(screen.getByText('You do not have permission to manage configurations.')).toBeTruthy()
    expect(requests).toEqual([])
  })

  it('lists profiles with their latest version and no content, active or archived', async () => {
    const { requests } = backend({ profiles: [profile({ latestRevisionNumber: 4 }), profile({ id: 'old', code: 'old', name: 'Old', archived: true })] })
    renderApp('/organizations/org/configurations')
    const row = (await screen.findByRole('link', { name: 'VPN Production Nodes' })).closest('tr')!
    expect(within(row).getByText('vpn-production')).toBeTruthy()
    expect(within(row).getByText('v4')).toBeTruthy()
    expect(screen.getAllByRole('columnheader').map(cell => cell.textContent)).toEqual(['Name', 'Latest version', 'Updated', 'State'])
    expect(document.body.textContent).not.toContain('{{')
    fireEvent.click(screen.getByRole('radio', { name: 'Archived' }))
    expect(await screen.findByRole('link', { name: 'Old' })).toBeTruthy()
    expect(location()).toBe('/organizations/org/configurations?state=archived')
    expect(requests.map(item => item.path)).toEqual(['?archived=false&limit=200', '?archived=true&limit=200'])
  })

  it('creates a profile: variables are added and removed, errors are shown, success opens v1', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configurations/new')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'VPN Production Nodes' } })
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'vpn-production' } })
    fireEvent.change(screen.getByLabelText('Template'), { target: { value: 'port={{ vpn_port }}' } })
    expect(screen.getByRole('note').textContent).toBe('Do not store passwords or tokens in the template or in default values.')

    fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
    fireEvent.click(screen.getByRole('button', { name: 'Add variable' }))
    expect(screen.getAllByRole('button', { name: /^Remove variable/ })).toHaveLength(2)
    fireEvent.click(screen.getAllByRole('button', { name: /^Remove variable/ })[1])
    fireEvent.change(screen.getByLabelText('Name of variable 1'), { target: { value: 'port' } })
    fireEvent.change(screen.getByLabelText('Type of variable 1'), { target: { value: 'INTEGER' } })
    fireEvent.change(screen.getByLabelText('Default of variable 1'), { target: { value: '443' } })

    fireEvent.click(screen.getByRole('button', { name: 'Validate' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('Template contains errors')
    expect(alert.textContent).toContain('Line 1, column 6: Undefined variable vpn_port.')

    fireEvent.change(screen.getByLabelText('Template'), { target: { value: 'port={{ port }}' } })
    // The old answer is gone as soon as the content changes.
    expect(screen.queryByRole('alert')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Validate' }))
    expect((await screen.findByRole('status')).textContent).toContain('Template is valid')
    expect(screen.getByRole('region', { name: 'Rendered preview' }).textContent).toContain('port=443')

    fireEvent.click(screen.getByRole('button', { name: 'Create profile' }))
    await waitFor(() => expect(location()).toBe('/organizations/org/configurations/created'))
    await screen.findByRole('heading', { level: 1, name: 'VPN Production Nodes' })
    const create = requests.find(item => item.method === 'POST' && item.path === '')
    expect(create?.body).toEqual({ code: 'vpn-production', name: 'VPN Production Nodes', description: null, template: 'port={{ port }}',
      variables: [{ name: 'port', type: 'INTEGER', required: true, defaultValue: '443' }] })
    expect(screen.getByText('Version 1')).toBeTruthy()
  })

  it('refuses an invalid code before asking the server', async () => {
    const { requests } = backend()
    renderApp('/organizations/org/configurations/new')
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'X' } })
    fireEvent.change(screen.getByLabelText('Code'), { target: { value: 'Bad Code' } })
    fireEvent.change(screen.getByLabelText('Template'), { target: { value: 'x' } })
    fireEvent.click(screen.getByRole('button', { name: 'Create profile' }))
    expect(screen.getByRole('alert').textContent).toContain('Use lowercase letters')
    expect(requests).toEqual([])
  })

  it('shows v1, saves a new version prefilled from it, and keeps the old one read-only in the history', async () => {
    const { requests } = backend({ profiles: [profile()], revisions: [revision(1)] })
    renderApp('/organizations/org/configurations/vpn')
    expect(await screen.findByText('Version 1')).toBeTruthy()
    expect(screen.getByLabelText('Template v1').textContent).toBe(template)
    expect(screen.getByText(/Created .* by Dmitriy/)).toBeTruthy()

    fireEvent.click(screen.getByRole('tab', { name: 'Variables' }))
    const variables = screen.getByRole('table')
    expect(within(variables).getAllByRole('columnheader').map(cell => cell.textContent)).toEqual(['Name', 'Type', 'Required', 'Default', 'Description'])
    expect(within(variables).getByText('Integer')).toBeTruthy()

    fireEvent.click(screen.getByRole('link', { name: 'New version' }))
    const editor = await screen.findByLabelText('Template') as HTMLTextAreaElement
    expect(editor.value).toBe(template)
    expect((screen.getByLabelText('Default of variable 1') as HTMLInputElement).value).toBe('443')
    fireEvent.change(editor, { target: { value: 'listen {{ port }};' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save version' }))
    expect(await screen.findByText('Version 2')).toBeTruthy()
    // The browser never chose the number: it only sent the content.
    expect(requests.find(item => item.path.endsWith('/revisions') && item.method === 'POST')?.body).toEqual({
      template: 'listen {{ port }};', variables: [{ name: 'port', type: 'INTEGER', required: true, defaultValue: '443', description: 'Listen port' }] })

    fireEvent.click(screen.getByRole('tab', { name: 'Versions' }))
    const history = await screen.findByRole('table')
    expect(within(history).getAllByRole('button').map(button => button.textContent)).toEqual(['v2', 'v1'])
    fireEvent.click(within(history).getByRole('button', { name: 'Open version 1' }))
    expect(await screen.findByText('You are viewing version 1. The latest is v2.')).toBeTruthy()
    expect((await screen.findByLabelText('Template v1')).textContent).toBe(template)
    expect(location()).toBe('/organizations/org/configurations/vpn?revision=1')
    // Read-only: there is nothing to type into.
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('edits details without creating a version, and archives only after confirmation', async () => {
    const { requests } = backend({ profiles: [profile()], revisions: [revision(1)] })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    renderApp('/organizations/org/configurations/vpn')
    fireEvent.click(await screen.findByRole('button', { name: 'Edit details' }))
    expect(screen.getByText('Changing the name or description does not create a new version.')).toBeTruthy()
    fireEvent.change(screen.getByLabelText('Name'), { target: { value: 'VPN Nodes' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save details' }))
    expect(await screen.findByRole('heading', { level: 1, name: 'VPN Nodes' })).toBeTruthy()
    expect(requests.filter(item => item.method === 'POST')).toEqual([])
    expect(requests.find(item => item.method === 'PATCH')?.body).toEqual({ name: 'VPN Nodes', description: null })

    fireEvent.click(screen.getByRole('button', { name: 'Archive' }))
    expect(requests.some(item => item.method === 'DELETE')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Archive' }))
    expect(confirm).toHaveBeenLastCalledWith('Archive VPN Nodes? Its versions are kept, but no new version can be created.')
    expect(await screen.findByText('This configuration is archived and read-only.')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'New version' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Edit details' })).toBeNull()
  })

  it('speaks Russian', async () => {
    backend({ profiles: [profile()], revisions: [revision(1)] })
    renderApp('/organizations/org/configurations', { locale: 'ru' })
    expect(await screen.findByRole('heading', { level: 1, name: 'Конфигурации' })).toBeTruthy()
    expect(screen.getByText('Общие шаблоны конфигурации инфраструктуры')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Создать конфигурацию' })).toBeTruthy()
    expect(await screen.findByText('Активна')).toBeTruthy()
    cleanup()
    backend()
    renderApp('/organizations/org/configurations/new', { locale: 'ru' })
    expect(screen.getByRole('note').textContent).toBe('Не храните пароли и токены в шаблоне или значениях по умолчанию.')
    await act(async () => { await Promise.resolve() })
    expect(screen.getByRole('button', { name: 'Проверить' })).toBeTruthy()
  })
})
