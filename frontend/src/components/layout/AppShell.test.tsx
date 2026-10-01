// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { I18nProvider, LocaleStorageKey, type Locale } from '../../i18n'
import { AppShell, ShellLayout } from './AppShell'
import { InfraDeskMark } from './InfraDeskMark'
import { ApiError } from '../../api/httpClient'

function Page({ title }: { title: string }) {
  const location = useLocation()
  // Pages wrap themselves in AppShell; inside the layout route that must not draw a second frame.
  return <AppShell><h1>{title}</h1><p data-testid="location">{location.pathname}{location.search}</p></AppShell>
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
        <Route element={<ShellLayout />}>
          <Route path="/organizations" element={<Page title="organizations" />} />
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
  return null
}

const location = () => screen.getByTestId('location').textContent

describe('application shell', () => {
  it('renders the product mark as decorative geometry beside the brand name', () => {
    const { container } = render(<I18nProvider initialLocale="ru"><InfraDeskMark /></I18nProvider>)
    const mark = container.querySelector('svg.brand-mark')
    expect(mark?.getAttribute('aria-hidden')).toBe('true')
    expect(mark?.querySelectorAll('path').length).toBe(2)
    expect(container.textContent).toBe('')
  })

  beforeEach(() => window.localStorage.clear())
  afterEach(() => cleanup())

  it('falls back to organization scope when a selected project is no longer available', async () => {
    setup('/organizations/org/integrations/one?project=removed&environment=gone&tab=nodes', 'OWNER', 'en', client => {
      client.setQueryData(['environments', 'org', 'removed'], [])
    })
    await waitFor(() => expect(location()).toBe('/organizations/org/integrations/one?tab=nodes'))
    expect(screen.getByRole('button', { name: 'Current context: InfraDesk / All projects. Change' })).toBeTruthy()
  })

  it.each([403, 404])('falls back to project scope after an environment becomes unavailable (%i)', async status => {
    setup('/organizations/org/incidents?project=org-p1&environment=env-test&status=OPEN', 'OWNER', 'en', client => {
      client.getQueryCache().find({ queryKey: ['environments', 'org', 'org-p1'] })!
        .setState({ status: 'error', error: new ApiError(status, 'ENVIRONMENT_NOT_FOUND', 'unavailable') })
    })
    await waitFor(() => expect(location()).toBe('/organizations/org/incidents?project=org-p1&status=OPEN'))
    expect(screen.getByRole('button', { name: 'Current context: InfraDesk / InitialProject / All environments. Change' })).toBeTruthy()
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
    expect(screen.getByRole('button', { name: 'Current context: InfraDesk / Website / All environments. Change' })).toBeTruthy()
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    expect(within(nav).getByRole('link', { name: 'Incidents' }).getAttribute('href')).toBe('/organizations/org/incidents?project=org-p2')
  })

  it('preserves environment scope from Servers through Connections, Incidents and Integrations', () => {
    setup('/organizations/org/environments/env-test?project=org-p1', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    for (const [name, route] of [['Connections', 'connections'], ['Incidents', 'incidents'], ['Integrations', 'integrations']]) {
      fireEvent.click(within(nav).getByRole('link', { name }))
      expect(location()).toBe(`/organizations/org/${route}?project=org-p1&environment=env-test`)
    }
  })

  it('draws one frame with grouped sections, in Russian by default', () => {
    setup('/organizations/org/overview')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(document.querySelectorAll('.app-shell')).toHaveLength(1)
    expect(Array.from(nav.querySelectorAll('.nav-group-label')).map(item => item.textContent))
      .toEqual(['Инфраструктура', 'Мониторинг', 'Автоматизация', 'Структура'])
    expect(within(nav).queryByText('Управление')).toBeNull()
    for (const link of ['Обзор', 'Серверы', 'Подключения', 'Инциденты', 'Проекты и окружения']) {
      expect(within(nav).getByRole('link', { name: link })).toBeTruthy()
    }
    expect(within(nav).getByRole('link', { name: 'Обзор' }).getAttribute('aria-current')).toBe('page')
    expect(within(nav).getByRole('link', { name: 'Подключения' }).getAttribute('aria-current')).toBeNull()
  })

  it('carries the context through every section and marks the current one', () => {
    setup('/organizations/org/connections/c1?project=org-p1&environment=env-test')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(within(nav).getByRole('link', { name: 'Подключения' }).getAttribute('aria-current')).toBe('page')
    expect(within(nav).getByRole('link', { name: 'Обзор' }).getAttribute('href'))
      .toBe('/organizations/org/overview?project=org-p1&environment=env-test')
    // With an environment selected, Servers opens it directly.
    expect(within(nav).getByRole('link', { name: 'Серверы' }).getAttribute('href'))
      .toBe('/organizations/org/environments/env-test?project=org-p1')
  })

  it('keeps the selected project and environment while moving between modules', () => {
    setup('/organizations/org/overview?project=org-p1&environment=env-test', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    fireEvent.click(within(nav).getByRole('link', { name: 'Incidents' }))
    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-test')
    fireEvent.click(within(nav).getByRole('link', { name: 'Connections' }))
    expect(location()).toBe('/organizations/org/connections?project=org-p1&environment=env-test')
    fireEvent.click(within(nav).getByRole('link', { name: 'Configurations' }))
    expect(location()).toBe('/organizations/org/configurations?project=org-p1&environment=env-test')
  })

  it('recovers the project from a direct environment link before switching modules', async () => {
    setup('/organizations/org/environments/env-test', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    expect(await screen.findByRole('button', { name: 'Current context: InfraDesk / InitialProject / Test. Change' }))
      .toBeTruthy()
    fireEvent.click(within(nav).getByRole('link', { name: 'Incidents' }))
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

  it('shows each scope and exposes the organization list beside the switcher', () => {
    for (const [path, name] of [
      ['/organizations/org/overview', 'Current context: InfraDesk / All projects. Change'],
      ['/organizations/org/overview?project=org-p1', 'Current context: InfraDesk / InitialProject / All environments. Change'],
      ['/organizations/org/overview?project=org-p1&environment=env-prod', 'Current context: InfraDesk / InitialProject / Production. Change'],
    ]) {
      setup(path, 'OWNER', 'en')
      fireEvent.click(screen.getByRole('button', { name }))
      expect(within(screen.getByRole('dialog', { name: 'Change context' }))
        .getByRole('link', { name: 'All organizations' }).getAttribute('href')).toBe('/organizations')
      cleanup()
    }
  })

  it('disables the sections outside an organization', () => {
    setup('/organizations')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(within(nav).queryByRole('link', { name: 'Обзор' })).toBeNull()
    expect(within(nav).getByText('Выберите организацию, чтобы увидеть её разделы.')).toBeTruthy()
  })

  it('explains why navigation is disabled without an organization', () => {
    setup('/organizations', 'OWNER', 'en')
    const nav = screen.getByRole('navigation', { name: 'Primary navigation' })
    const overview = within(nav).getByText('Overview').closest('.nav-link')
    expect(overview?.getAttribute('aria-disabled')).toBe('true')
    expect(overview?.getAttribute('title')).toBe('Choose an organization first')
  })

  it('shows the context as one line and changes it in a popover', () => {
    const router = setup('/organizations/org/overview?project=org-p1&environment=env-test')
    const trigger = screen.getByRole('button', { name: /Текущий контекст: InfraDesk \/ InitialProject \/ Test/ })

    expect(trigger.getAttribute('aria-expanded')).toBe('false')
    fireEvent.click(trigger)
    const panel = screen.getByRole('dialog', { name: 'Сменить контекст' })
    expect(trigger.getAttribute('aria-expanded')).toBe('true')

    // A new project clears the environment; the section stays the same.
    fireEvent.change(within(panel).getByLabelText('Проект'), { target: { value: 'org-p2' } })
    expect(location()).toBe('/organizations/org/overview?project=org-p2')

    // Back returns to the previous context: the URL is the only state.
    act(() => router.navigate(-1))
    expect(location()).toBe('/organizations/org/overview?project=org-p1&environment=env-test')
  })

  it('prefers optional display names for organization, project, and environment while retaining name fallbacks', () => {
    setup('/organizations/org/overview?project=org-p1&environment=env-test', 'OWNER', 'en', client => {
      client.setQueryData(['my-organizations'], [
        { id: 'org', code: 'org_slug', name: 'API organization name', displayName: 'Friendly organization', role: 'OWNER' },
      ])
      client.setQueryData(['projects', 'org'], [
        { id: 'org-p1', organizationId: 'org', code: 'project_slug', name: 'API project name', displayName: 'Friendly project', description: null },
      ])
      client.setQueryData(['environments', 'org', 'org-p1'], [
        { id: 'env-test', organizationId: 'org', projectId: 'org-p1', code: 'environment_slug', name: 'API environment name', displayName: 'Friendly environment', kind: 'TEST' },
      ])
    })

    const trigger = screen.getByRole('button', { name: 'Current context: Friendly organization / Friendly project / Friendly environment. Change' })
    expect(trigger.textContent).toContain('Friendly organization')
    expect(trigger.textContent).toContain('Friendly project')
    expect(trigger.textContent).toContain('Friendly environment')
    expect(trigger.textContent).not.toContain('project_slug')
    fireEvent.click(trigger)
    const panel = screen.getByRole('dialog', { name: 'Change context' })
    expect(within(panel).getByRole('option', { name: 'Friendly organization' })).toBeTruthy()
    expect(within(panel).getByRole('option', { name: 'Friendly project' })).toBeTruthy()
    expect(within(panel).getByRole('option', { name: 'Friendly environment · Test' })).toBeTruthy()
  })

  it('uses organization/project/environment codes when names are absent and never shows UUIDs', () => {
    setup('/organizations/org/overview?project=org-p1&environment=env-test', 'OWNER', 'en', client => {
      client.setQueryData(['my-organizations'], [{ id: 'org', code: 'org_slug', name: '', role: 'OWNER' }])
      client.setQueryData(['projects', 'org'], [{ id: 'org-p1', organizationId: 'org', code: 'project_slug', name: '', description: null }])
      client.setQueryData(['environments', 'org', 'org-p1'], [
        { id: 'env-test', organizationId: 'org', projectId: 'org-p1', code: 'environment_slug', name: '', kind: 'TEST' },
      ])
    })
    const trigger = screen.getByRole('button', { name: 'Current context: org_slug / project_slug / environment_slug. Change' })
    expect(trigger.textContent).not.toContain('env-test')
    fireEvent.click(trigger)
    const panel = screen.getByRole('dialog', { name: 'Change context' })
    expect(within(panel).getByRole('option', { name: 'org_slug' })).toBeTruthy()
    expect(within(panel).getByRole('option', { name: 'project_slug' })).toBeTruthy()
    expect(within(panel).getByRole('option', { name: 'environment_slug · Test' })).toBeTruthy()
  })

  it('selects an environment inside the chosen project and keeps the section', () => {
    setup('/organizations/org/incidents?project=org-p1')
    fireEvent.click(screen.getByRole('button', { name: /Текущий контекст/ }))
    const panel = screen.getByRole('dialog', { name: 'Сменить контекст' })

    fireEvent.change(within(panel).getByLabelText('Окружение'), { target: { value: 'env-prod' } })

    expect(location()).toBe('/organizations/org/incidents?project=org-p1&environment=env-prod')
  })

  it('clears project and environment when the organization changes, and leaves a detail page for its list', () => {
    setup('/organizations/org/connections/c1?project=org-p1&environment=env-test')
    fireEvent.click(screen.getByRole('button', { name: /Текущий контекст/ }))

    fireEvent.change(within(screen.getByRole('dialog')).getByLabelText('Организация'), { target: { value: 'other' } })

    expect(location()).toBe('/organizations/other/connections')
  })

  it('does not allow an environment before a project is chosen', () => {
    setup('/organizations/org/overview')
    fireEvent.click(screen.getByRole('button', { name: /Текущий контекст/ }))

    expect((within(screen.getByRole('dialog')).getByLabelText('Окружение') as HTMLSelectElement).disabled).toBe(true)
  })

  it('closes the context popover with Escape and returns focus to its trigger', () => {
    setup('/organizations/org/overview')
    const trigger = screen.getByRole('button', { name: /Текущий контекст/ })
    fireEvent.click(trigger)

    fireEvent.keyDown(document, { key: 'Escape' })

    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(trigger)
  })

  it('opens and closes the navigation drawer from the keyboard', () => {
    setup('/organizations/org/overview')
    const toggle = screen.getByRole('button', { name: 'Открыть меню' })

    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    expect(document.querySelector('.app-shell')?.classList.contains('drawer-open')).toBe(true)
    // Focus moves into the drawer so it can be used without a mouse.
    expect(document.getElementById('app-sidebar')?.contains(document.activeElement)).toBe(true)

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggle)
  })

  it('closes the drawer from its backdrop and gives focus back to the menu button', () => {
    setup('/organizations/org/overview')
    const toggle = screen.getByRole('button', { name: 'Открыть меню' })
    fireEvent.click(toggle)

    fireEvent.click(document.querySelector('.drawer-backdrop') as HTMLElement)

    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(toggle)
  })

  it('closes the drawer after navigating from it', () => {
    setup('/organizations/org/overview')
    fireEvent.click(screen.getByRole('button', { name: 'Открыть меню' }))

    fireEvent.click(screen.getByRole('link', { name: 'Инциденты' }))

    expect(location()).toBe('/organizations/org/incidents')
    expect(screen.getByRole('button', { name: 'Открыть меню' }).getAttribute('aria-expanded')).toBe('false')
    expect(document.activeElement).toBe(screen.getByRole('main'))
  })

  it('switches the language from the account menu without a reload and remembers it', () => {
    setup('/organizations/org/overview')
    fireEvent.click(screen.getByRole('button', { name: 'Меню учётной записи Dmitriy' }))
    const menu = screen.getByRole('menu')

    expect(within(menu).getByRole('menuitemradio', { name: 'Русский' }).getAttribute('aria-checked')).toBe('true')
    fireEvent.click(within(menu).getByRole('menuitemradio', { name: 'English' }))

    expect(screen.getByRole('navigation', { name: 'Primary navigation' })).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Connections' })).toBeTruthy()
    expect(window.localStorage.getItem(LocaleStorageKey)).toBe('en')
  })

  it('moves between account menu items with the arrow keys', () => {
    setup('/organizations/org/overview', 'MEMBER', 'en')
    fireEvent.click(screen.getByRole('button', { name: 'Account menu for Dmitriy' }))
    const menu = screen.getByRole('menu')
    // Every focusable menu entry in DOM order, whichever kind of menuitem it is.
    const items = Array.from(menu.querySelectorAll<HTMLElement>('[role^="menuitem"]'))
    const firstRadio = within(menu).getAllByRole('menuitemradio')[0]

    // The first language option is focused when the menu opens.
    expect(document.activeElement).toBe(firstRadio)
    const start = items.indexOf(firstRadio)
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[(start + 1) % items.length])
    fireEvent.keyDown(menu, { key: 'End' })
    expect(document.activeElement).toBe(within(menu).getByRole('menuitem', { name: 'Sign out' }))
  })

  it('shows management shortcuts in the switcher only to an owner', () => {
    setup('/organizations/org/overview?project=org-p2', 'MEMBER')
    fireEvent.click(screen.getByRole('button', { name: /Текущий контекст/ }))
    expect(screen.queryByRole('link', { name: 'Добавить окружение' })).toBeNull()
    cleanup()

    setup('/organizations/org/overview?project=org-p2', 'OWNER')
    fireEvent.click(screen.getByRole('button', { name: /Текущий контекст/ }))
    expect(screen.getByRole('link', { name: 'Добавить окружение' })).toBeTruthy()
  })
})
