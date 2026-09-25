// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation, useNavigate, type NavigateFunction } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { I18nProvider, LocaleStorageKey, type Locale } from '../../i18n'
import { AppShell, ShellLayout } from './AppShell'

function Page({ title }: { title: string }) {
  const location = useLocation()
  // Pages wrap themselves in AppShell; inside the layout route that must not draw a second frame.
  return <AppShell><h1>{title}</h1><p data-testid="location">{location.pathname}{location.search}</p></AppShell>
}

function setup(path: string, role: 'OWNER' | 'MEMBER' = 'OWNER', locale: Locale = 'ru') {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } })
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
  beforeEach(() => window.localStorage.clear())
  afterEach(() => cleanup())

  it('draws one frame with grouped sections, in Russian by default', () => {
    setup('/organizations/org/overview')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(document.querySelectorAll('.app-shell')).toHaveLength(1)
    for (const group of ['Инфраструктура', 'Мониторинг', 'Управление']) expect(within(nav).getByText(group)).toBeTruthy()
    for (const link of ['Обзор', 'Ресурсы', 'Подключения', 'Инциденты', 'Проекты и окружения']) {
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
    // With an environment selected, Resources opens it directly.
    expect(within(nav).getByRole('link', { name: 'Ресурсы' }).getAttribute('href'))
      .toBe('/organizations/org/environments/env-test?project=org-p1')
  })

  it('disables the sections outside an organization', () => {
    setup('/organizations')
    const nav = screen.getByRole('navigation', { name: 'Основная навигация' })

    expect(within(nav).queryByRole('link', { name: 'Обзор' })).toBeNull()
    expect(within(nav).getByText('Выберите организацию, чтобы увидеть её разделы.')).toBeTruthy()
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

  it('closes the drawer after navigating from it', () => {
    setup('/organizations/org/overview')
    fireEvent.click(screen.getByRole('button', { name: 'Открыть меню' }))

    fireEvent.click(screen.getByRole('link', { name: 'Инциденты' }))

    expect(location()).toBe('/organizations/org/incidents')
    expect(screen.getByRole('button', { name: 'Открыть меню' }).getAttribute('aria-expanded')).toBe('false')
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
    const items = [...within(menu).getAllByRole('menuitemradio'), within(menu).getByRole('menuitem')]

    expect(document.activeElement).toBe(items[0])
    fireEvent.keyDown(menu, { key: 'ArrowDown' })
    expect(document.activeElement).toBe(items[1])
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
