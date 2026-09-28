import { createBrowserRouter } from 'react-router-dom'

import { RequireAuth } from '../components/auth/RequireAuth'
import { ShellLayout } from '../components/layout/AppShell'

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
import { ResourcesIndexPage } from '../pages/ResourcesIndexPage'
import { LoginPage } from '../pages/LoginPage'
import { AccountPage } from '../pages/AccountPage'
import { NotificationChannelsPage } from '../pages/NotificationChannelsPage'
import { NotificationChannelFormPage } from '../pages/NotificationChannelFormPage'

export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    element: <RequireAuth />,
    children: [{ element: <ShellLayout />, children: [
      { path: '/settings/account', element: <AccountPage /> },
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
      { path: '/organizations/:organizationId/notifications/:channelId/edit', element: <NotificationChannelFormPage /> },
      { path: '/organizations/:organizationId/notifications/new', element: <NotificationChannelFormPage /> },
      { path: '/organizations/:organizationId/notifications', element: <NotificationChannelsPage /> },
      // Owner-only and rarely opened: loaded on first visit, not with every page.
      { path: '/organizations/:organizationId/configurations', lazy: async () => {
        const { ConfigurationsPage } = await import('../pages/ConfigurationsPage')
        return { Component: ConfigurationsPage }
      } },
      { path: '/organizations/:organizationId/configurations/new', lazy: async () => {
        const { ConfigurationCreatePage } = await import('../pages/ConfigurationEditorPages')
        return { Component: ConfigurationCreatePage }
      } },
      { path: '/organizations/:organizationId/configurations/:profileId', lazy: async () => {
        const { ConfigurationProfilePage } = await import('../pages/ConfigurationProfilePage')
        return { Component: ConfigurationProfilePage }
      } },
      { path: '/organizations/:organizationId/configurations/:profileId/versions/new', lazy: async () => {
        const { ConfigurationVersionPage } = await import('../pages/ConfigurationEditorPages')
        return { Component: ConfigurationVersionPage }
      } },
      { path: '/organizations/:organizationId/configuration-assignments/new', lazy: async () => {
        const { ConfigurationAssignmentCreatePage } = await import('../pages/ConfigurationAssignmentPages')
        return { Component: ConfigurationAssignmentCreatePage }
      } },
      { path: '/organizations/:organizationId/configuration-assignments/:assignmentId', lazy: async () => {
        const { ConfigurationAssignmentEditPage } = await import('../pages/ConfigurationAssignmentPages')
        return { Component: ConfigurationAssignmentEditPage }
      } },
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
      { path: '/organizations/:organizationId/resources', element: <ResourcesIndexPage /> },
      { path: '*', element: <InvalidRoutePage /> },
    ] }],
  },
])
