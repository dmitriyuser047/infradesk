import { createBrowserRouter } from 'react-router-dom'

import { EnvironmentPage } from '../pages/EnvironmentPage'
import { InvalidRoutePage } from '../pages/InvalidRoutePage'
import { IncidentsPage } from '../pages/IncidentsPage'
import { IncidentPage } from '../pages/IncidentPage'

export const router = createBrowserRouter([
  {
    path: '/organizations/:organizationId/incidents/:incidentId',
    element: <IncidentPage />,
  },
  {
    path: '/organizations/:organizationId/incidents',
    element: <IncidentsPage />,
  },
  {
    path: '/organizations/:organizationId/environments/:environmentId/resources/:resourceId',
    lazy: async () => {
      const { ResourcePage } = await import('../pages/ResourcePage')
      return { Component: ResourcePage }
    },
  },
  {
    path: '/organizations/:organizationId/environments/:environmentId',
    element: <EnvironmentPage />,
  },
  {
    path: '*',
    element: <InvalidRoutePage />,
  },
])
