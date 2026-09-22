import { createBrowserRouter } from 'react-router-dom'

import { RequireAuth } from '../components/auth/RequireAuth'

import { EnvironmentPage } from '../pages/EnvironmentPage'
import { InvalidRoutePage } from '../pages/InvalidRoutePage'
import { IncidentsPage } from '../pages/IncidentsPage'
import { IncidentPage } from '../pages/IncidentPage'
import { ConnectionPage } from '../pages/ConnectionPage'
import { ConnectionsPage } from '../pages/ConnectionsPage'
import { OrganizationPage } from '../pages/OrganizationPage'
import { OrganizationsPage } from '../pages/OrganizationsPage'
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
      { path: '/organizations/:organizationId/connections/:connectionId', element: <ConnectionPage /> },
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
