// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { en } from '../../i18n/en'
import { ru } from '../../i18n/ru'
import { I18nProvider, LocaleStorageKey, type Locale } from '../../i18n'
import { AppShell, ShellLayout } from './AppShell'
import { InfraDeskMark } from './InfraDeskMark'
import { initializeTheme, setTheme } from '../../app/theme'
import { ApiError } from '../../api/httpClient'
import { OrganizationsPage } from '../../pages/OrganizationsPage'
import { WorkspaceEntryPage } from '../../pages/WorkspaceEntryPage'

function Page({ title }: { title: string }) {
  const location = useLocation()
  // Pages wrap themselves in AppShell; inside the layout route that must not draw a second frame.
  return <AppShell><h1>{title}</h1></AppShell>
}

function setup(path: string, role: 'OWNER' | 'MEMBER' = 'OWNER', locale: Locale = 'ru', seed?: (client: QueryClient) => void) {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  queries.setQueryData(['me'], { id: 'user', email: 'dmitriy@example.com', displayName: 'Dmitriy' })
  queries.setQueryData(['my-organizations'], [
    { id: 'org', code: 'ORG', name: 'InfraDesk', role },
    { id: 'other', code: 'OTHER', name: 'Other org', role },
  ])
  for (const organization of ['org', 'other']) {
    queries.setQueryData(['projects', organization], [
      { id: `${organization}-p1`, organizationId: organization, code: 'app', name: organization === 'org' ? 'InitialProject' : 'Other project', description: null },
      { id: `${organization}-p2`, organizationId: organization, code: 'site', name: 'Website', description: null },
    ])
  }
  queries.setQueryData(['environments', 'org', 'org-p1'], [
    { id: 'env-test', organizationId: 'org', projectId: 'org-p1', code: 'test', name: 'Test', kind: 'TEST' },
    { id: 'env-prod', organizationId: 'org', projectId: 'org-p1', code: 'prod', name: 'Production', kind: 'PROD' },
  ])
  queries.setQueryData(['environments', 'org', 'org-p2'], [])
  queries.setQueryData(['resource-context', 'org', 'server'], {
    project: { id: 'org-p1', name: 'InitialProject' },
    environment: { id: 'env-test', name: 'Test', kind: 'TEST' },
    parentResource: null, sourceConnections: [], children: [], activeChildCount: 0, openIncidentCount: 0,
  })
  queries.setQueryData(['environment-context', 'org', 'env-test'], {
    project: { id: 'org-p1', name: 'InitialProject' },
    environment: { id: 'env-test', name: 'Test', kind: 'TEST' },
  })
  seed?.(queries)
  // A declarative router: the data router builds fetch Requests with jsdom's AbortSignal, which
  // newer Node versions reject, and the shell needs no loaders.
  const history: { navigate?: NavigateFunction } = {}
  render(<I18nProvider initialLocale={locale}><QueryClientProvider client={queries}>
    <MemoryRouter initialEntries={[path]}>
      <CaptureNavigate target={history} />
      <Routes>
        <Route path="/organizations" element={<OrganizationsPage />} />
        <Route path="/" element={<WorkspaceEntryPage />} />
        <Route element={<ShellLayout />}>
          <Route path="/administration" element={<Page title="administration" />} />
          <Route path="/settings/account" element={<Page title="account" />} />
          <Route path="/organizations/:organizationId" element={<Page title="workspace" />} />
          <Route path="/organizations/:organizationId/overview" element={<Page title="overview" />} />
          <Route path="/organizations/:organizationId/resources" element={<Page title="resources" />} />
          <Route path="/organizations/:organizationId/environments/:environmentId" element={<Page title="environment" />} />
          <Route path="/organizations/:organizationId/connections" element={<Page title="connections" />} />
          <Route path="/organizations/:organizationId/connections/:connectionId" element={<Page title="connection" />} />
          <Route path="/organizations/:organizationId/incidents" element={<Page title="incidents" />} />
          <Route path="/organizations/:organizationId/*" element={<Page title="nested" />} />
        </Route>
      </Routes>
    </MemoryRouter></QueryClientProvider></I18nProvider>)
  return { navigate: (delta: number) => { void history.navigate?.(delta) } }
}

function CaptureNavigate({ target }: { target: { navigate?: NavigateFunction } }) {
  target.navigate = useNavigate()
  const location = useLocation()
  return <p data-testid="location">{location.pathname}{location.search}</p>
}

const location = () => screen.getByTestId('location').textContent

/** Reveal a page through its actual group control; context assertions still inspect real links. */
function navigationLink(name: string): HTMLElement {
  for (const t of [en, ru]) {
    const groups = [
      [t.shell.nav.overview, [t.shell.nav.overview]],
      [t.shell.groups.infrastructure, [t.shell.nav.resources, t.shell.nav.connections]],
      [t.shell.groups.monitoring, [t.shell.nav.incidents, t.shell.nav.notifications]],
      [t.shell.groups.automation, [t.shell.nav.integrations, t.shell.nav.configurations]],
      [t.shell.groups.structure, [t.shell.nav.workspace, t.administration.members]],
    ] as const
    const group = groups.find(([, names]) => names.some(label => label === name))
    if (group) {
      const control = screen.getByRole('button', { name: group[0] })
      if (control.getAttribute('aria-pressed') !== 'true') fireEvent.click(control)
      return within(screen.getByRole('navigation', { name: t.shell.primaryNavigation }))
        .getByRole('link', { name })
    }
  }
  throw new Error('Unknown navigation item: ' + name)
}


describe('application shell', () => {
  it('renders the product mark as decorative geometry beside the brand name', () => {
    const { container } = render(<I18nProvider initialLocale="ru"><InfraDeskMark /></I18nProvider>)
    const mark = container.querySelector('svg.brand-mark')
    expect(mark?.getAttribute('aria-hidden')).toBe('true')
    expect(mark?.querySelectorAll('path').length).toBe(2)
    expect(container.textContent).toBe('')
  })

  beforeEach(() => { window.localStorage.clear(); window.sessionStorage.clear() })
  afterEach(() => cleanup())

  it('asks for an organization on first entry, remembers the choice and keeps the explicit chooser available', () => {
    setup('/', 'OWNER', 'en')
    expect(screen.getByRole('heading', { level: 1, name: en.organizations.title })).toBeTruthy()
    expect(document.querySelector('.context-switcher')).toBeNull()
    expect(document.querySelector('.app-shell, .sidebar, .topbar, .navigation-panel, .workspace-dock')).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: /Other org OTHER/ }))
    expect(location()).toBe('/organizations/other/overview')
    expect(JSON.parse(localStorage.getItem('infradesk.workspace.user')!).organizationId).toBe('other')
    cleanup()
    sessionStorage.clear()
    setup('/', 'OWNER', 'en')
    expect(location()).toBe('/organizations/other/overview')
    fireEvent.click(screen.getByRole('link', { name: en.shell.organizations }))
    expect(location()).toBe('/organizations')
    expect(screen.getByRole('link', { name: en.administration.createOrganization }).getAttribute('href')).toBe('/organizations/new')
  })

  it('does not silently choose the sole organization at first entry or restore revoked access', () => {
    setup('/', 'OWNER', 'en', client => client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'InfraDesk', role: 'OWNER' }]))
    expect(location()).toBe('/')
    expect(screen.getByRole('heading', { level: 1, name: en.organizations.title })).toBeTruthy()
    cleanup()
    localStorage.setItem('infradesk.workspace.user', JSON.stringify({ organizationId: 'removed' }))
    setup('/', 'OWNER', 'en')
    expect(location()).toBe('/')
    expect(localStorage.getItem('infradesk.workspace.user')).toBeNull()
  })

  it('filters pages by project and environment without a top context control', () => {
    setup('/organizations/org/incidents?project=org-p1&environment=env-test', 'OWNER', 'en')
    expect(document.querySelector('.topbar select, .context-trigger')).toBeNull()
    expect((screen.getByLabelText(en.context.project) as HTMLSelectElement).value).toBe('org-p1')
    fireEvent.change(screen.getByLabelText(en.context.project), { target: { value: 'org-p2' } })
    expect(location()).toBe('/organizations/org/incidents?project=org-p2')
    fireEvent.change(screen.getByLabelText(en.context.project), { target: { value: 'org-p1' } })
    fireEvent.change(screen.getByLabelText(en.context.environment), { target: { value: 'env-prod' } })
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-prod')
  })

  it('shows a bounded splash on entering an organization but not on switching its pages', async () => {
    vi.useFakeTimers()
    try {
      setup('/organizations/org/overview', 'OWNER', 'en')
      expect(document.querySelector('.workspace-splash strong')?.textContent).toBe('InfraDesk')
      expect(document.querySelector('.app-shell')?.hasAttribute('inert')).toBe(true)
      await act(async () => vi.advanceTimersByTime(1000))
      expect(document.querySelector('.workspace-splash')).toBeNull()
      expect(document.querySelector('.app-shell')?.hasAttribute('inert')).toBe(false)
      fireEvent.click(screen.getByRole('button', { name: en.shell.groups.monitoring }))
      expect(location()).toBe('/organizations/org/incidents')
      expect(document.querySelector('.workspace-splash')).toBeNull()
    } finally { vi.useRealTimers() }
  })

  it('keeps an entered organization ready through administration, account settings and browser Back', async () => {
    vi.useFakeTimers()
    try {
      const router = setup('/organizations/org/overview', 'OWNER', 'en', client => client.setQueryData(['me'], {
        id: 'user', email: 'admin@example.test', displayName: 'Admin', isAdministrator: true,
      }))
      expect(document.querySelector('.workspace-splash')).toBeTruthy()
      await act(async () => vi.advanceTimersByTime(1000))
      for (const group of [en.shell.nav.overview, en.shell.groups.infrastructure, en.shell.groups.monitoring,
        en.shell.groups.automation, en.shell.groups.structure]) {
        fireEvent.click(screen.getByRole('button', { name: en.administration.title }))
        expect(location()).toBe('/administration')
        expect(document.querySelector('.workspace-splash')).toBeNull()
        fireEvent.click(screen.getByRole('button', { name: group }))
        expect(location()).toMatch(/^\/organizations\/org/)
        expect(document.querySelector('.workspace-splash')).toBeNull()
        expect(document.querySelector('.app-shell')?.hasAttribute('inert')).toBe(false)
      }
      fireEvent.click(screen.getByRole('button', { name: en.administration.title }))
      fireEvent.click(screen.getByRole('link', { name: en.shell.returnToWorkspace }))
      expect(location()).toBe('/organizations/org/overview')
      expect(document.querySelector('.workspace-splash')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: en.shell.accountMenu('Admin') }))
      fireEvent.click(within(screen.getByRole('menu')).getByRole('menuitem', { name: en.shell.accountSettings }))
      expect(location()).toBe('/settings/account')
      await act(async () => router.navigate(-1))
      expect(location()).toBe('/organizations/org/overview')
      expect(document.querySelector('.workspace-splash')).toBeNull()
      fireEvent.click(screen.getByRole('button', { name: en.administration.title }))
      fireEvent.click(screen.getByRole('link', { name: en.shell.brandHome }))
      expect(location()).toBe('/organizations/org/overview')
      expect(document.querySelector('.workspace-splash')).toBeNull()
      fireEvent.click(screen.getByRole('link', { name: en.shell.organizations }))
      fireEvent.click(screen.getByRole('link', { name: /Other org/ }))
      expect(document.querySelector('.workspace-splash strong')?.textContent).toBe('Other org')
      await act(async () => vi.advanceTimersByTime(1000))
      expect(document.querySelector('.workspace-splash')).toBeNull()
      fireEvent.click(screen.getByRole('link', { name: en.shell.organizations }))
      fireEvent.click(screen.getByRole('link', { name: /InfraDesk ORG/ }))
      expect(document.querySelector('.workspace-splash strong')?.textContent).toBe('InfraDesk')
    } finally { vi.useRealTimers() }
  })

  it('falls back to organization scope when a selected project is no longer available', async () => {
    setup('/organizations/org/integrations/one?project=removed&environment=gone&tab=nodes', 'OWNER', 'en', client => {
      client.setQueryData(['environments', 'org', 'removed'], [])
    })
    await waitFor(() => expect(location()).toBe('/organizations/org/integrations/one?tab=nodes'))
  })

  it.each([403, 404])('falls back to project scope after an environment becomes unavailable (%i)', async status => {
    setup('/organizations/org/incidents?project=org-p1&environment=env-test&status=OPEN', 'OWNER', 'en', client => {
      client.getQueryCache().find({ queryKey: ['environments', 'org', 'org-p1'] })!
        .setState({ status: 'error', error: new ApiError(status, 'ENVIRONMENT_NOT_FOUND', 'unavailable') })
    })
    await waitFor(() => expect(location()).toBe('/organizations/org/incidents?project=org-p1&status=OPEN'))
  })

  it('keeps scope during a temporary load failure', () => {
    setup('/organizations/org/incidents?project=org-p1&environment=env-test', 'OWNER', 'en', client => {
      client.getQueryCache().find({ queryKey: ['environments', 'org', 'org-p1'] })!
        .setState({ status: 'error', error: new ApiError(500, 'INTERNAL_ERROR', 'temporary') })
    })
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
  })

  it('does not mix a project-wide selection with a connection environment', () => {
    setup('/organizations/org/connections/c1?project=org-p2', 'OWNER', 'en', client => {
      client.setQueryData(['connection', 'org', 'c1'], { scope: { type: 'ENVIRONMENT', projectId: 'org-p1', environmentId: 'env-test' } })
    })
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    expect(navigationLink('Incidents').getAttribute('href')).toBe('/organizations/org/incidents?project=org-p2')
  })

  it('preserves environment scope from Servers through Connections, Incidents and Integrations', () => {
    setup('/organizations/org/environments/env-test?project=org-p1', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    for (const [name, route] of [['Connections', 'connections'], ['Incidents', 'incidents'], ['Integrations', 'integrations']]) {
      fireEvent.click(navigationLink(name))
      expect(location()).toBe(`/organizations/org/${route}?project=org-p1&environment=env-test`)
    }
  })

  it('draws one frame with group controls and page navigation inside the workspace', () => {
    setup('/organizations/org/overview')
    expect(document.querySelectorAll('.app-shell')).toHaveLength(1)
    const sections = screen.getByRole('navigation', { name: ru.shell.navigationSections })
    for (const name of Object.values(ru.shell.groups)) expect(within(sections).getByRole('button', { name })).toBeTruthy()
    expect(navigationLink(ru.shell.nav.overview).getAttribute('aria-current')).toBe('page')
    expect(navigationLink(ru.shell.nav.connections).getAttribute('aria-current')).toBeNull()
    expect(document.querySelector('.app-main > .navigation-panel')).toBeTruthy()
    expect(document.querySelector('.sidebar .navigation-panel')).toBeNull()
  })

  it('carries the context through every section and marks the current one', () => {
    setup('/organizations/org/connections/c1?project=org-p1&environment=env-test')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(navigationLink('Подключения').getAttribute('aria-current')).toBe('page')
    expect(navigationLink('Обзор').getAttribute('href'))
      .toBe('/organizations/org/overview?project=org-p1&environment=env-test')
    // With an environment selected, Servers opens it directly.
    expect(navigationLink('Серверы').getAttribute('href'))
      .toBe('/organizations/org/environments/env-test?project=org-p1')
  })

  it('keeps the selected project and environment while moving between modules', () => {
    setup('/organizations/org/overview?project=org-p1&environment=env-test', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    fireEvent.click(navigationLink('Incidents'))
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
    fireEvent.click(navigationLink('Connections'))
    expect(location()).toBe('/organizations/org/connections?project=org-p1&environment=env-test')
    fireEvent.click(navigationLink('Configurations'))
    expect(location()).toBe('/organizations/org/configurations?project=org-p1&environment=env-test')
  })

  it('recovers the project from a direct environment link before switching modules', async () => {
    setup('/organizations/org/environments/env-test', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    await waitFor(() => expect((screen.getByLabelText(en.context.project) as HTMLSelectElement).value).toBe('org-p1'))
    fireEvent.click(navigationLink('Incidents'))
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
  })

  it('marks nested configuration and integration pages in the sidebar', () => {
    for (const [path, label] of [
      ['/organizations/org/configuration-rules/rule', 'Configurations'],
      ['/organizations/org/configuration-assignments/assignment', 'Configurations'],
      ['/organizations/org/integrations/integration/config-profiles/profile', 'Integrations'],
      ['/organizations/org/environments/env-test/resources/server', 'Servers'],
    ]) {
      setup(path, 'OWNER', 'en')
      const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
      expect(within(nav).getByRole('link', { name: label }).getAttribute('aria-current')).toBe('page')
      cleanup()
    }
  })











  it('opens Monitoring from a configuration page and switches its tabs with the same scope', async () => {
    const router = setup('/organizations/org/configurations?project=org-p1&environment=env-test', 'OWNER', 'en')
    fireEvent.click(screen.getByRole('button', { name: en.shell.groups.monitoring }))
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
    expect(screen.getByRole('heading', { name: 'incidents' })).toBeTruthy()
    expect(screen.getByRole('button', { name: en.shell.groups.monitoring }).getAttribute('aria-pressed')).toBe('true')
    expect(screen.getByRole('link', { name: en.shell.nav.incidents }).getAttribute('aria-current')).toBe('page')
    fireEvent.click(screen.getByRole('link', { name: en.shell.nav.notifications }))
    expect(location()).toBe('/organizations/org/notifications?project=org-p1&environment=env-test')
    expect(screen.getByRole('link', { name: en.shell.nav.notifications }).getAttribute('aria-current')).toBe('page')
    await act(async () => router.navigate(-1))
    await act(async () => router.navigate(-1))
    expect(location()).toBe('/organizations/org/configurations?project=org-p1&environment=env-test')
    expect(screen.getByRole('button', { name: en.shell.groups.automation }).getAttribute('aria-pressed')).toBe('true')
  })

  it('places the account menu beside the page title and restores the route group on Back', async () => {
    const router = setup('/organizations/org/overview', 'OWNER', 'en')
    expect(screen.queryByRole('searchbox', { name: en.shell.searchNavigation })).toBeNull()
    expect(document.querySelector('.topbar')).toBeNull()
    expect(document.querySelector('.navigation-account button')).toBeTruthy()
    fireEvent.click(navigationLink(en.shell.nav.connections))
    expect(location()).toBe('/organizations/org/connections')
    expect(screen.getByRole('button', { name: en.shell.groups.infrastructure }).getAttribute('aria-pressed')).toBe('true')
    await act(async () => router.navigate(-1))
    expect(location()).toBe('/organizations/org/resources')
  })

  it('opens the first authorized Automation page for a read-only member', () => {
    setup('/organizations/org/overview?project=org-p1&environment=env-test', 'MEMBER', 'en')
    fireEvent.click(screen.getByRole('button', { name: en.shell.groups.automation }))
    expect(location()).toBe('/organizations/org/configurations?project=org-p1&environment=env-test')
    expect(screen.getByRole('link', { name: en.shell.nav.configurations }).getAttribute('aria-current')).toBe('page')
    expect(screen.queryByRole('link', { name: en.shell.nav.integrations })).toBeNull()
  })

  it('keeps the workspace through administration, reload and account settings', () => {
    const admin = (client: QueryClient) => client.setQueryData(['me'], {
      id: 'user', email: 'admin@example.test', displayName: 'Admin', isAdministrator: true,
    })
    setup('/organizations/org/connections?project=org-p1&environment=env-test', 'OWNER', 'en', admin)
    fireEvent.click(screen.getByRole('button', { name: en.administration.title }))
    expect(location()).toBe('/administration')
    expect(screen.getByRole('link', { name: en.shell.returnToWorkspace }).getAttribute('href'))
      .toBe('/organizations/org/overview?project=org-p1&environment=env-test')
    cleanup()
    setup('/administration?tab=organizations', 'OWNER', 'en', admin)
    expect(screen.getByRole('link', { name: en.administration.organizations }).getAttribute('aria-current')).toBe('page')
    fireEvent.click(screen.getByRole('button', { name: en.shell.groups.monitoring }))
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
    cleanup()
    setup('/settings/account', 'OWNER', 'en', admin)
    expect(screen.getByRole('link', { name: en.shell.accountSettings }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('button', { name: en.shell.nav.overview }).getAttribute('aria-pressed')).toBe('false')
    fireEvent.click(screen.getByRole('button', { name: en.shell.groups.infrastructure }))
    expect(location()).toBe('/organizations/org/environments/env-test?project=org-p1')
  })

  it('does not restore a removed membership or another account workspace', () => {
    sessionStorage.setItem('infradesk.workspace.someone-else', JSON.stringify({ organizationId: 'org' }))
    sessionStorage.setItem('infradesk.workspace.admin', JSON.stringify({ organizationId: 'removed', projectId: 'private' }))
    setup('/administration', 'OWNER', 'en', client => client.setQueryData(['me'], {
      id: 'admin', email: 'admin@example.test', displayName: 'Admin', isAdministrator: true,
    }))
    expect(screen.queryByRole('button', { name: en.shell.groups.infrastructure })).toBeNull()
    expect(screen.getByRole('link', { name: en.shell.returnToWorkspace }).getAttribute('href')).toBe('/organizations')
    expect(sessionStorage.getItem('infradesk.workspace.admin')).toBeNull()
    fireEvent.click(screen.getByRole('link', { name: en.shell.organizations }))
    fireEvent.click(screen.getByRole('link', { name: /Other org/ }))
    expect(location()).toBe('/organizations/other/overview')
  })


  it('never reveals restricted pages and exposes admin only to global admins', () => {
    setup('/organizations/org/overview', 'MEMBER', 'en')
    for (const name of [en.shell.nav.integrations, en.shell.nav.notifications, en.administration.members, en.administration.users]) {
      expect(screen.queryByRole('link', { name })).toBeNull()
    }
    expect(screen.queryByRole('button', { name: en.administration.title })).toBeNull()
    cleanup()
    setup('/administration', 'OWNER', 'en', client => client.setQueryData(['me'], {
      id: 'admin', email: 'admin@example.test', displayName: 'Admin', isAdministrator: true,
    }))
    fireEvent.click(screen.getByRole('button', { name: en.administration.title }))
    expect(screen.getByRole('link', { name: en.administration.users }).getAttribute('href')).toBe('/administration')
  })

  it('switches the language from the account menu without a reload and remembers it', () => {
    setup('/organizations/org/overview')
    fireEvent.click(screen.getByRole('button', { name: 'Меню учётной записи Dmitriy' }))
    const menu = screen.getByRole('menu')

    expect(within(menu).getByRole('menuitemradio', { name: 'Русский' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'English' }))

    expect(screen.getByRole('navigation', { name: 'Primary navigation' })).toBeTruthy()
    expect(navigationLink('Connections')).toBeTruthy()
    expect(window.localStorage.getItem(LocaleStorageKey)).toBe('en')
  })

  it('moves between account menu items with the arrow keys', () => {
    setup('/organizations/org/overview', 'MEMBER', 'en')
    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Dmitriy' }))
    const menu = screen.getByRole('menu')
    // Every focusable menu entry in DOM order, whichever kind of menuitem it is.
    const items = Array.from(menu.querySelectorAll<HTMLElement>('[role^="menuitem"]'))
    const firstRadio = within(menu).getAllByRole('menuitemradio')[0]

    // The first appearance option is focused when the menu opens.
    expect(document.activeElement).toBe(firstRadio)
    const start = items.indexOf(firstRadio)
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[(start + 1) % items.length])
    fireEvent.keyDown(menu, { key: 'End' })
    expect(document.activeElement).toBe(within(menu).getByRole('menuitem', { name: 'Sign out' }))
  })

  it('changes the interface theme from the account menu and restores the saved choice', () => {
    setup('/organizations/org/overview', 'MEMBER', 'en')
    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Dmitriy' }))
    fireEvent.click(screen.getByRole('menuitemradio', { name: 'Dark theme' }))
    expect(document.documentElement.dataset.theme).toBe('dark')
    expect(window.localStorage.getItem('infradesk.theme')).toBe('dark')
    expect(screen.getByRole('menuitemradio', { name: 'Dark theme' }).getAttribute('aria-checked')).toBe('true')
    document.documentElement.dataset.theme = 'light'
    act(() => initializeTheme())
    expect(document.documentElement.dataset.theme).toBe('dark')
    act(() => setTheme('light'))
    expect(screen.getByRole('menuitemradio', { name: 'Light theme' }).getAttribute('aria-checked')).toBe('true')
  })


})
