import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { I18nProvider, type Locale } from '../i18n'
import { ConnectionFormPage } from '../pages/ConnectionFormPage'
import { ConnectionPage } from '../pages/ConnectionPage'
import { ConnectionsPage } from '../pages/ConnectionsPage'
import { ConnectionSyncSessionPage } from '../pages/ConnectionSyncSessionPage'
import { EnvironmentCreatePage } from '../pages/EnvironmentCreatePage'
import { EnvironmentPage } from '../pages/EnvironmentPage'
import { IncidentPage } from '../pages/IncidentPage'
import { IncidentsPage } from '../pages/IncidentsPage'
import { InvalidRoutePage } from '../pages/InvalidRoutePage'
import { LoginPage } from '../pages/LoginPage'
import { OrganizationPage } from '../pages/OrganizationPage'
import { OrganizationsPage } from '../pages/OrganizationsPage'
import { OverviewPage } from '../pages/OverviewPage'
import { ProjectCreatePage } from '../pages/ProjectCreatePage'
import { ResourcePage } from '../pages/ResourcePage'

function queryClient(): QueryClient {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role: 'OWNER' }])
  client.setQueryData(['organization', 'org'], { id: 'org', code: 'ORG', name: 'Example org' })
  client.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  client.setQueryData(['environments', 'org', 'project'], [
    { id: 'environment', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' },
  ])
  const resource = { id: 'resource', organizationId: 'org', environmentId: 'environment', resourceTypeId: 'node',
    parentResourceId: null, code: 'node', name: 'Node', resourceTypeCode: 'NODE', active: true,
    createdAt: '', updatedAt: '', data: { kind: 'NODE', spec: null, status: null } }
  client.setQueryData(['environment-resources', 'org', 'environment'], [resource])
  client.setQueryData(['resource', 'org', 'resource'], resource)
  const connection = { id: 'connection', connectorType: 'SSH', code: 'ssh', name: 'SSH host',
    scope: { type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' }, active: true,
    schedule: null, lastSync: null, createdAt: '', updatedAt: '',
    ssh: { host: 'example.test', port: 22, username: 'user', hostKeyFingerprint: null, credentialConfigured: true } }
  client.setQueryData(['connections', 'org'], [connection])
  client.setQueryData(['connection', 'org', 'connection'], connection)
  const session = { id: 'session', status: 'COMPLETED', startedAt: '2026-09-23T10:00:00Z',
    finishedAt: '2026-09-23T10:00:05Z', errorCode: null, errorMessage: null }
  client.setQueryData(['sync-session', 'org', 'connection', 'session'], session)
  client.setQueryData(['sync-sessions', 'org', 'connection'], [session])
  const incident = { id: 'incident', monitorRuleId: 'rule', resourceId: 'resource', status: 'OPEN', reason: 'THRESHOLD',
    startedAt: '2026-09-23T10:00:00Z', openedAt: '2026-09-23T10:00:00Z', resolvedAt: null,
    createdAt: '', updatedAt: '' }
  client.setQueryData(['incidents', 'org', 'OPEN'], [incident])
  client.setQueryData(['incident', 'org', 'incident'], incident)
  return client
}

const pages = [
  { path: '/login', route: '/login', element: <LoginPage />, title: 'Войти', titleEn: 'Sign in' },
  { path: '/organizations', route: '/organizations', element: <OrganizationsPage />, title: 'Организации', titleEn: 'Organizations' },
  { path: '/organizations/org', route: '/organizations/:organizationId', element: <OrganizationPage />, title: 'Проекты и окружения', titleEn: 'Projects and environments' },
  { path: '/organizations/org/overview', route: '/organizations/:organizationId/overview', element: <OverviewPage />, title: 'Обзор инфраструктуры', titleEn: 'Infrastructure overview' },
  { path: '/organizations/org/projects/new', route: '/organizations/:organizationId/projects/new', element: <ProjectCreatePage />, title: 'Новый проект', titleEn: 'Create project' },
  { path: '/organizations/org/projects/project/environments/new', route: '/organizations/:organizationId/projects/:projectId/environments/new', element: <EnvironmentCreatePage />, title: 'Новое окружение', titleEn: 'Create environment' },
  { path: '/organizations/org/environments/environment?project=project', route: '/organizations/:organizationId/environments/:environmentId', element: <EnvironmentPage />, title: 'Ресурсы', titleEn: 'Resources' },
  { path: '/organizations/org/environments/environment/resources/resource?project=project', route: '/organizations/:organizationId/environments/:environmentId/resources/:resourceId', element: <ResourcePage />, title: 'Node', titleEn: 'Node' },
  { path: '/organizations/org/connections', route: '/organizations/:organizationId/connections', element: <ConnectionsPage />, title: 'Подключения', titleEn: 'Connections' },
  { path: '/organizations/org/connections/new', route: '/organizations/:organizationId/connections/new', element: <ConnectionFormPage />, title: 'Подключение к серверу', titleEn: 'Connect a server' },
  { path: '/organizations/org/connections/connection/edit', route: '/organizations/:organizationId/connections/:connectionId/edit', element: <ConnectionFormPage />, title: 'Изменение подключения', titleEn: 'Edit connection' },
  { path: '/organizations/org/connections/connection', route: '/organizations/:organizationId/connections/:connectionId', element: <ConnectionPage />, title: 'SSH host', titleEn: 'SSH host' },
  { path: '/organizations/org/connections/connection/sync-sessions/session', route: '/organizations/:organizationId/connections/:connectionId/sync-sessions/:sessionId', element: <ConnectionSyncSessionPage />, title: 'Синхронизация', titleEn: 'Synchronization' },
  { path: '/organizations/org/incidents', route: '/organizations/:organizationId/incidents', element: <IncidentsPage />, title: 'Инциденты', titleEn: 'Incidents' },
  { path: '/organizations/org/incidents/incident', route: '/organizations/:organizationId/incidents/:incidentId', element: <IncidentPage />, title: 'Превышен порог · Node', titleEn: 'Threshold exceeded · Node' },
  { path: '/missing', route: '*', element: <InvalidRoutePage />, title: 'Страница недоступна', titleEn: 'Page unavailable' },
]

/**
 * Interface words that must never leak into the Russian interface. Proper names (InfraDesk, SSH,
 * Docker) and data (project names, codes) are not interface text and may appear in any locale.
 */
const englishInterfaceWords = [
  '>Overview<', '>Resources<', '>Connections<', '>Incidents<', '>Retry<', '>Refresh<', '>Cancel<',
  '>Save', 'Loading', 'Sign out', '>Status<', '>Name<', 'Synchroniz', 'Organization', 'Environment',
  'Project', 'Unable to', 'No data', 'Needs attention',
]

describe('redesigned routes', () => {
  for (const page of pages) {
    it(`renders ${page.path} in Russian by default`, () => {
      const html = renderPage(page)
      expect(html).toContain(page.title)
      for (const word of englishInterfaceWords) {
        expect(html, `"${word}" leaked into the Russian page`).not.toContain(word)
      }
    })

    it(`renders ${page.path} in English`, () => {
      expect(renderPage(page, 'en')).toContain(page.titleEn)
    })
  }

  it('shows the project action only in its frame', () => {
    const html = renderPage(pages.find(page => page.path === '/organizations/org')!)
    expect(html.match(/Новый проект/g)).toHaveLength(1)
  })

  it('shows human project and environment names in connection overview', () => {
    const html = renderPage(pages.find(page => page.path === '/organizations/org/connections/connection')!, 'en')
    expect(html).toContain('<dt>Project</dt><dd>App</dd>')
    expect(html).toContain('<dt>Environment</dt><dd>Production</dd>')
    expect(html).not.toContain('Project ID')
    expect(html).not.toContain('Environment ID')
  })
})

function renderPage(page: (typeof pages)[number], locale: Locale = 'ru'): string {
  return renderToStaticMarkup(<I18nProvider initialLocale={locale}><QueryClientProvider client={queryClient()}>
    <MemoryRouter initialEntries={[page.path]}><Routes>
      <Route path={page.route} element={page.element} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}
