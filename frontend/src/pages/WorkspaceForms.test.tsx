// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import { EnvironmentCreatePage } from './EnvironmentCreatePage'
import { ProjectCreatePage } from './ProjectCreatePage'

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

function renderForm(path: string, options: { role?: 'OWNER' | 'MEMBER'; locale?: Locale; answer?: (method: string, path: string) => Response | Promise<Response> } = {}) {
  const sent: string[] = []
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string, init?: RequestInit) => {
    const request = `${init?.method ?? 'GET'} ${String(url).replace('/api/v1/organizations/org', '')}`
    sent.push(request)
    return Promise.resolve(options.answer?.(init?.method ?? 'GET', request) ?? json({ id: 'created' }))
  }))
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'a@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Northwind', role: options.role ?? 'OWNER' }])
  client.setQueryData(['projects', 'org'], [{ id: 'billing', organizationId: 'org', code: 'billing', name: 'Billing', description: null }])
  render(<I18nProvider initialLocale={options.locale ?? 'ru'}><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/organizations/:organizationId/projects/new" element={<ProjectCreatePage />} />
      <Route path="/organizations/:organizationId/projects/:projectId/environments/new" element={<EnvironmentCreatePage />} />
      <Route path="*" element={<p>navigated</p>} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
  return sent
}

const fill = (label: string, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } })

describe('project and environment forms', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('lets an owner create a project in one request, showing that it is working', async () => {
    let finish: (response: Response) => void = () => undefined
    const sent = renderForm('/organizations/org/projects/new', { answer: method => method === 'POST'
      ? new Promise<Response>(done => { finish = done }) : json([]) })

    expect(screen.getByRole('heading', { name: 'Новый проект' })).toBeTruthy()
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org')
    fill('Название', 'Billing'); fill('Код', 'billing')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Создать проект' })) })

    const pending = await screen.findByRole('button', { name: 'Создание…' }) as HTMLButtonElement
    expect(pending.disabled).toBe(true)
    expect(pending.getAttribute('aria-busy')).toBe('true')
    await act(async () => { finish(json({ id: 'billing', organizationId: 'org', code: 'billing', name: 'Billing', description: null })) })
    // Next step: the first environment of the new project.
    expect(await screen.findByRole('heading', { name: 'Новое окружение' })).toBeTruthy()
    // One create; the project list is re-read by its existing invalidation.
    expect(sent.filter(request => request.startsWith('POST'))).toEqual(['POST /projects'])
  })

  it('requires a name and a code before anything is sent', () => {
    const sent = renderForm('/organizations/org/projects/new')

    for (const label of ['Название', 'Код']) expect((screen.getByLabelText(label) as HTMLInputElement).required).toBe(true)
    expect((document.querySelector('form') as HTMLFormElement).checkValidity()).toBe(false)
    expect(sent).toEqual([])
  })

  it('explains a refused create without the server message', async () => {
    renderForm('/organizations/org/projects/new', { answer: method => method === 'POST'
      ? json({ code: 'INTERNAL_ERROR', message: 'duplicate key value violates unique constraint' }, 500) : json({}) })
    fill('Название', 'Billing'); fill('Код', 'billing')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Создать проект' })) })

    expect(await screen.findByText('Не удалось создать проект. Попробуйте ещё раз.')).toBeTruthy()
    expect(document.body.textContent).not.toContain('duplicate key')
  })

  it('tells a member creating projects and environments is for the owner, without a form', () => {
    renderForm('/organizations/org/projects/new', { role: 'MEMBER' })
    expect(screen.getByText('Создавать проекты может только владелец организации.')).toBeTruthy()
    expect(document.querySelector('form')).toBeNull()
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org')
    cleanup()

    renderForm('/organizations/org/projects/billing/environments/new', { role: 'MEMBER' })
    expect(screen.getByText('Создавать окружения может только владелец организации.')).toBeTruthy()
    expect(document.querySelector('form')).toBeNull()
  })

  it('names the project an environment is created in, from the route, and sends it there', async () => {
    const sent = renderForm('/organizations/org/projects/billing/environments/new', { answer: method => method === 'POST'
      ? json({ id: 'prod', organizationId: 'org', projectId: 'billing', code: 'prod', name: 'Production', kind: 'PROD' }) : json({}) })

    expect(screen.getByText('Проект · Billing')).toBeTruthy()
    expect(screen.getByText('Окружение будет создано в проекте')).toBeTruthy()
    expect(screen.getByText('billing')).toBeTruthy()
    // The project is not something to pick here.
    expect(screen.queryByLabelText('Проект')).toBeNull()
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/organizations/org?project=billing')

    fill('Название', 'Production'); fill('Код', 'prod')
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Создать окружение' })) })
    expect(await screen.findByText('navigated')).toBeTruthy()
    // One create; the only reads are the top bar's environments of this project and their refresh after it.
    expect(sent.filter(request => request.startsWith('POST'))).toEqual(['POST /projects/billing/environments'])
    expect(sent.filter(request => !request.startsWith('POST')).every(request => request === 'GET /projects/billing/environments')).toBe(true)
  })

  it('speaks English', () => {
    renderForm('/organizations/org/projects/billing/environments/new', { locale: 'en' })

    expect(screen.getByRole('heading', { name: 'Create environment' })).toBeTruthy()
    expect(screen.getByText('Project · Billing')).toBeTruthy()
    expect(screen.getByText('The environment is created in project')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Create environment' })).toBeTruthy()
    cleanup()

    renderForm('/organizations/org/projects/new', { locale: 'en', role: 'MEMBER' })
    expect(screen.getByText('Only organization owners can create projects.')).toBeTruthy()
  })
})
