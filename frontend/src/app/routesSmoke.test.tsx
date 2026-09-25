import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

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
  const incident = { id: 'incident', monitorRuleId: 'rule', resourceId: 'resource', status: 'OPEN',
    startedAt: '2026-09-23T10:00:00Z', openedAt: '2026-09-23T10:00:00Z', resolvedAt: null,
    createdAt: '', updatedAt: '' }
  client.setQueryData(['incidents', 'org', 'OPEN'], [incident])
  client.setQueryData(['incident', 'org', 'incident'], incident)
  return client
}

const pages = [
  { path: '/login', route: '/login', element: <LoginPage />, title: 'Sign in' },
  { path: '/organizations', route: '/organizations', element: <OrganizationsPage />, title: 'Organizations' },
  { path: '/organizations/org', route: '/organizations/:organizationId', element: <OrganizationPage />, title: 'Example org' },
  { path: '/organizations/org/overview', route: '/organizations/:organizationId/overview', element: <OverviewPage />, title: 'Overview' },
  { path: '/organizations/org/projects/new', route: '/organizations/:organizationId/projects/new', element: <ProjectCreatePage />, title: 'Create project' },
  { path: '/organizations/org/projects/project/environments/new', route: '/organizations/:organizationId/projects/:projectId/environments/new', element: <EnvironmentCreatePage />, title: 'Create environment' },
  { path: '/organizations/org/environments/environment?project=project', route: '/organizations/:organizationId/environments/:environmentId', element: <EnvironmentPage />, title: 'Infrastructure' },
  { path: '/organizations/org/environments/environment/resources/resource?project=project', route: '/organizations/:organizationId/environments/:environmentId/resources/:resourceId', element: <ResourcePage />, title: 'Node' },
  { path: '/organizations/org/connections', route: '/organizations/:organizationId/connections', element: <ConnectionsPage />, title: 'Connections' },
  { path: '/organizations/org/connections/new', route: '/organizations/:organizationId/connections/new', element: <ConnectionFormPage />, title: 'Add SSH connection' },
  { path: '/organizations/org/connections/connection/edit', route: '/organizations/:organizationId/connections/:connectionId/edit', element: <ConnectionFormPage />, title: 'Edit connection' },
  { path: '/organizations/org/connections/connection', route: '/organizations/:organizationId/connections/:connectionId', element: <ConnectionPage />, title: 'SSH host' },
  { path: '/organizations/org/connections/connection/sync-sessions/session', route: '/organizations/:organizationId/connections/:connectionId/sync-sessions/:sessionId', element: <ConnectionSyncSessionPage />, title: 'Synchronization' },
  { path: '/organizations/org/incidents', route: '/organizations/:organizationId/incidents', element: <IncidentsPage />, title: 'Incidents' },
  { path: '/organizations/org/incidents/incident', route: '/organizations/:organizationId/incidents/:incidentId', element: <IncidentPage />, title: 'Infrastructure incident' },
  { path: '/missing', route: '*', element: <InvalidRoutePage />, title: 'Page unavailable' },
]

describe('redesigned routes', () => {
  for (const page of pages) {
    it(`renders ${page.path}`, () => {
      const html = renderPage(page)
      expect(html).toContain(page.title)
    })
  }

  it('shows the project action only in its frame', () => {
    const html = renderPage(pages.find(page => page.path === '/organizations/org')!)
    expect(html.match(/\+ New project/g)).toHaveLength(1)
  })

  it('shows human project and environment names in connection overview', () => {
    const html = renderPage(pages.find(page => page.path === '/organizations/org/connections/connection')!)
    expect(html).toContain('<dt>Project</dt><dd>App</dd>')
    expect(html).toContain('<dt>Environment</dt><dd>Production</dd>')
    expect(html).not.toContain('Project ID')
    expect(html).not.toContain('Environment ID')
  })
})

function renderPage(page: (typeof pages)[number]): string {
  return renderToStaticMarkup(<QueryClientProvider client={queryClient()}>
    <MemoryRouter initialEntries={[page.path]}><Routes>
      <Route path={page.route} element={page.element} />
    </Routes></MemoryRouter>
  </QueryClientProvider>)
}
