// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import type { EnvironmentResponse, ProjectResponse } from '../types/navigation'
import { OrganizationPage } from './OrganizationPage'

function project(id: string, name = id, description: string | null = null): ProjectResponse {
  return { id, organizationId: 'org', code: id, name, description }
}
function environment(id: string, projectId: string, kind: string, name = id): EnvironmentResponse {
  return { id, organizationId: 'org', projectId, code: id, name, kind } as EnvironmentResponse
}

const billing = project('billing', 'Billing', 'Платёжный сервис')
const site = project('site', 'Website')
const environmentsOf: Record<string, EnvironmentResponse[]> = {
  billing: [environment('prod', 'billing', 'PROD', 'Production'), environment('stage', 'billing', 'STAGE', 'Staging'),
    environment('test', 'billing', 'TEST', 'Test')],
  site: [],
}

function Location() {
  const location = useLocation()
  return <output data-testid="location">{location.pathname}{location.search}</output>
}

/** The organization and its projects answer; each project's environments come from `environments`. */
function renderPage(options: { role?: 'OWNER' | 'MEMBER'; projects?: ProjectResponse[]; path?: string; locale?: Locale
  environments?: (projectId: string) => Response } = {}) {
  const requests: string[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    const path = String(url).replace('/api/v1/organizations/org', '')
    requests.push(path)
    const match = path.match(/^\/projects\/([^/]+)\/environments$/)
    const body = match ? null : path === '/projects' ? options.projects ?? [billing, site] : { id: 'org', code: 'ORG', name: 'Northwind' }
    return Promise.resolve(match
      ? options.environments?.(match[1]) ?? new Response(JSON.stringify(environmentsOf[match[1]] ?? []), { status: 200, headers: { 'Content-Type': 'application/json' } })
      : new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'a@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Northwind', role: options.role ?? 'OWNER' }])
  render(<I18nProvider initialLocale={options.locale ?? 'ru'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[options.path ?? '/organizations/org']}><Routes>
      <Route path="/organizations/:organizationId" element={<><OrganizationPage /><Location /></>} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return requests
}

const card = (name: string) => screen.getByRole('region', { name })

describe('projects and environments', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('shows each project with its code, environment count and environments by kind', async () => {
    renderPage()
    await screen.findByText('Production')
    const billingCard = card('Billing')

    expect(within(billingCard).getByText('billing')).toBeTruthy()
    expect(within(billingCard).getByText('3 окружения')).toBeTruthy()
    expect(within(billingCard).getByText('Платёжный сервис')).toBeTruthy()
    expect(within(billingCard).getByText('Продакшен')).toBeTruthy()
    expect(within(billingCard).getByText('Предпрод')).toBeTruthy()
    expect(within(card('Website')).getByText('0 окружений')).toBeTruthy()
  })

  it('counts in English too', async () => {
    renderPage({ locale: 'en', projects: [billing, project('one', 'One')], environments: id => new Response(JSON.stringify(
      id === 'one' ? [environment('only', 'one', 'DEV')] : environmentsOf.billing), { status: 200, headers: { 'Content-Type': 'application/json' } }) })

    expect(await screen.findByText('3 environments')).toBeTruthy()
    expect(screen.getByText('1 environment')).toBeTruthy()
    expect(screen.getByText('Development')).toBeTruthy()
  })

  it('pluralizes the count in Russian', async () => {
    const sizes = [1, 2, 5, 21]
    renderPage({ projects: sizes.map(size => project(`p${size}`, `P${size}`)), environments: id => new Response(JSON.stringify(
      Array.from({ length: Number(id.slice(1)) }, (_, index) => environment(`${id}-${index}`, id, 'TEST'))),
    { status: 200, headers: { 'Content-Type': 'application/json' } }) })

    expect(await screen.findByText('21 окружение')).toBeTruthy()
    expect(screen.getByText('1 окружение')).toBeTruthy()
    expect(screen.getByText('2 окружения')).toBeTruthy()
    expect(screen.getByText('5 окружений')).toBeTruthy()
  })

  it('marks the project and environment chosen in the URL, in words as well as by look', async () => {
    renderPage({ path: '/organizations/org?project=billing&environment=stage' })
    await screen.findByRole('link', { name: 'Открыть ресурсы окружения Staging' })

    expect(card('Billing').getAttribute('aria-current')).toBe('true')
    expect(within(card('Billing')).getByText('Текущий проект')).toBeTruthy()
    expect(card('Website').getAttribute('aria-current')).toBeNull()
    expect(screen.getByRole('link', { name: 'Billing' }).getAttribute('aria-current')).toBe('page')
    expect(screen.getByRole('link', { name: 'Открыть ресурсы окружения Staging' }).getAttribute('aria-current')).toBe('true')
    expect(screen.getByRole('link', { name: 'Открыть ресурсы окружения Production' }).getAttribute('aria-current')).toBeNull()

    // Choosing another project is a link that writes the context, which resets the environment.
    fireEvent.click(screen.getByRole('link', { name: 'Website' }))
    expect(screen.getByTestId('location').textContent).toBe('/organizations/org?project=site')
    expect(card('Website').getAttribute('aria-current')).toBe('true')
    expect(card('Billing').getAttribute('aria-current')).toBeNull()
  })

  it('leads each environment to its own resources and overview, keeping the context', async () => {
    renderPage()
    await screen.findByText('Production')

    expect(screen.getByRole('link', { name: 'Открыть ресурсы окружения Production' }).getAttribute('href'))
      .toBe('/organizations/org/environments/prod?project=billing')
    expect(screen.getByRole('link', { name: 'Обзор окружения Production' }).getAttribute('href'))
      .toBe('/organizations/org/overview?project=billing&environment=prod')
    expect(within(card('Billing')).getByRole('link', { name: 'Обзор проекта' }).getAttribute('href'))
      .toBe('/organizations/org/overview?project=billing')
  })

  it('keeps a long project name inside its card', async () => {
    const long = 'a-very-long-project-name-'.repeat(8)
    renderPage({ projects: [project('long', long)] })

    expect(await screen.findByRole('link', { name: long })).toBeTruthy()
    expect(card(long).className).toContain('project-card')
  })

  it('lets an owner add the first environment of an empty project, and tells a member who does', async () => {
    renderPage()
    await screen.findByText('Production')
    const empty = card('Website')
    expect(within(empty).getByText('В этом проекте пока нет окружений')).toBeTruthy()
    expect(within(empty).getByRole('link', { name: 'Добавить окружение в Website' }).getAttribute('href'))
      .toBe('/organizations/org/projects/site/environments/new')
    expect(within(card('Billing')).getByRole('link', { name: 'Добавить окружение в Billing' })).toBeTruthy()
    cleanup()

    renderPage({ role: 'MEMBER' })
    await screen.findByText('Production')
    expect(within(card('Website')).getByText('Окружения добавляет владелец организации.')).toBeTruthy()
    expect(document.querySelector('a[href*="/environments/new"]')).toBeNull()
    expect(document.querySelector('a[href*="/projects/new"]')).toBeNull()
  })

  it('explains an empty organization, with the first step for an owner only', async () => {
    renderPage({ projects: [] })
    expect(await screen.findByText('Проектов пока нет')).toBeTruthy()
    expect(screen.getByText('Создайте первый проект, чтобы разделить инфраструктуру по системам и окружениям.')).toBeTruthy()
    expect(screen.getAllByRole('link', { name: 'Новый проект' }).length).toBeGreaterThan(0)
    cleanup()

    renderPage({ projects: [], role: 'MEMBER' })
    expect(await screen.findByText('Владелец организации ещё не создал ни одного проекта.')).toBeTruthy()
    expect(screen.queryByRole('link', { name: 'Новый проект' })).toBeNull()
  })

  it('keeps a failed environments read inside its own card, with a retry', async () => {
    renderPage({ environments: id => id === 'site'
      ? new Response(JSON.stringify({ code: 'INTERNAL_ERROR', message: 'x' }), { status: 500, headers: { 'Content-Type': 'application/json' } })
      : new Response(JSON.stringify(environmentsOf.billing), { status: 200, headers: { 'Content-Type': 'application/json' } }) })

    const failed = await screen.findByRole('region', { name: 'Website' })
    expect(await within(failed).findByText('Не удалось загрузить окружения')).toBeTruthy()
    expect(within(card('Website')).getByRole('button', { name: 'Повторить' })).toBeTruthy()
    expect(within(card('Billing')).getByText('Production')).toBeTruthy()
    expect(within(card('Billing')).queryByRole('alert')).toBeNull()
  })

  it.each([1, 5])('asks once for the organization and projects, and once per project for its environments: %i project(s)', async count => {
    const projects = Array.from({ length: count }, (_, index) => project(`p${index}`, `P${index}`))
    const requests = renderPage({ projects })
    await waitFor(() => expect(screen.getAllByText('0 окружений')).toHaveLength(count))

    // The count, the code and the marker add nothing: no per-card request beyond the environments one.
    expect([...requests].sort()).toEqual(['', '/projects', ...projects.map(item => `/projects/${item.id}/environments`)].sort())
  })
})
