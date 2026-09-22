import { createBrowserRouter } from 'react-router-dom'

import { EnvironmentPage } from '../pages/EnvironmentPage'
import { InvalidRoutePage } from '../pages/InvalidRoutePage'

export const router = createBrowserRouter([
  {
    path: '/organizations/:organizationId/environments/:environmentId',
    element: <EnvironmentPage />,
  },
  {
    path: '*',
    element: <InvalidRoutePage />,
  },
])
