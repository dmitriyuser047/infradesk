import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { OrganizationPage } from './OrganizationPage'

function render(role: 'OWNER' | 'MEMBER', projects: boolean): string {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'a@example.com', displayName: 'Dmitriy' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Northwind', role }])
  client.setQueryData(['organization', 'org'], { id: 'org', code: 'ORG', name: 'Northwind' })
  client.setQueryData(['projects', 'org'], projects ? [
    { id: 'billing', organizationId: 'org', code: 'billing', name: 'Billing', description: 'Платёжный сервис' },
    { id: 'site', organizationId: 'org', code: 'site', name: 'Website', description: null },
  ] : [])
  client.setQueryData(['environments', 'org', 'billing'], [
    { id: 'prod', organizationId: 'org', projectId: 'billing', code: 'prod', name: 'Production', kind: 'PROD' },
    { id: 'stage', organizationId: 'org', projectId: 'billing', code: 'stage', name: 'Staging', kind: 'STAGE' },
  ])
  client.setQueryData(['environments', 'org', 'site'], [])
  return renderToStaticMarkup(<QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org']}><Routes>
      <Route path="/organizations/:organizationId" element={<OrganizationPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider>)
}

describe('projects and environments', () => {
  it('explains the hierarchy and lists each project with its environments', () => {
    const html = render('OWNER', true)

    expect(html).toContain('Проекты и окружения')
    expect(html).toContain('Одна система или продукт')
    expect(html).toContain('В нём обнаруживаются серверы и контейнеры')
    expect(html).toMatch(/Billing.*2 окружения.*Production.*Продакшен.*Staging/s)
    expect(html).toContain('href="/organizations/org/environments/prod?project=billing"')
    expect(html).toContain('href="/organizations/org/overview?project=billing&amp;environment=prod"')
    expect(html).toContain('В этом проекте пока нет окружений')
  })

  it('offers project and environment management to an owner only', () => {
    const owner = render('OWNER', true)
    const member = render('MEMBER', true)

    expect(owner).toContain('href="/organizations/org/projects/new"')
    expect(owner).toContain('href="/organizations/org/projects/billing/environments/new"')
    expect(member).not.toContain('/projects/new')
    expect(member).not.toContain('/environments/new')
  })

  it('says what to do when there are no projects yet', () => {
    expect(render('OWNER', false)).toContain('Создайте проект для каждой системы')
    const member = render('MEMBER', false)
    expect(member).toContain('Владелец организации ещё не создал ни одного проекта.')
    expect(member).not.toContain('/projects/new')
  })
})
