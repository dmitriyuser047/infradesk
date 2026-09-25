import { createBrowserRouter } from 'react-router-dom'

import { RequireAuth } from '../components/auth/RequireAuth'

import { EnvironmentPage } from '../pages/EnvironmentPage'
import { InvalidRoutePage } from '../pages/InvalidRoutePage'
import { IncidentsPage } from '../pages/IncidentsPage'
import { IncidentPage } from '../pages/IncidentPage'
import { ConnectionPage } from '../pages/ConnectionPage'
import { ConnectionsPage } from '../pages/ConnectionsPage'
import { ConnectionFormPage } from '../pages/ConnectionFormPage'
import { ConnectionSyncSessionPage } from '../pages/ConnectionSyncSessionPage'
import { OrganizationPage } from '../pages/OrganizationPage'
import { ProjectCreatePage } from '../pages/ProjectCreatePage'
import { EnvironmentCreatePage } from '../pages/EnvironmentCreatePage'
import { OrganizationsPage } from '../pages/OrganizationsPage'
import { OverviewPage } from '../pages/OverviewPage'
import { LoginPage } from '../pages/LoginPage'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    element: <RequireAuth />,
    children: [
      { path: '/organizations', element: <OrganizationsPage /> },
      { path: '/organizations/:organizationId', element: <OrganizationPage /> },
      { path: '/organizations/:organizationId/overview', element: <OverviewPage /> },
      { path: '/organizations/:organizationId/projects/new', element: <ProjectCreatePage /> },
      { path: '/organizations/:organizationId/projects/:projectId/environments/new', element: <EnvironmentCreatePage /> },
      { path: '/organizations/:organizationId/connections/:connectionId', element: <ConnectionPage /> },
      { path: '/organizations/:organizationId/connections/:connectionId/sync-sessions/:sessionId', element: <ConnectionSyncSessionPage /> },
      { path: '/organizations/:organizationId/connections/:connectionId/edit', element: <ConnectionFormPage /> },
      { path: '/organizations/:organizationId/connections/new', element: <ConnectionFormPage /> },
      { path: '/organizations/:organizationId/connections', element: <ConnectionsPage /> },
      { path: '/organizations/:organizationId/incidents/:incidentId', element: <IncidentPage /> },
      { path: '/organizations/:organizationId/incidents', element: <IncidentsPage /> },
      {
        path: '/organizations/:organizationId/environments/:environmentId/resources/:resourceId',
        lazy: async () => {
          const { ResourcePage } = await import('../pages/ResourcePage')
          return { Component: ResourcePage }
        },
      },
      { path: '/organizations/:organizationId/environments/:environmentId', element: <EnvironmentPage /> },
      { path: '*', element: <InvalidRoutePage /> },
    ],
  },
])
