import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { AppShell } from './AppShell'
import { WorkspaceHeader } from './WorkspacePrimitives'

function renderAt(path: string): string {
  const queries = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  queries.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  queries.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Example org', role: 'OWNER' }])
  queries.setQueryData(['projects', 'org'], [{ id: 'project', organizationId: 'org', code: 'app', name: 'App', description: null }])
  queries.setQueryData(['environments', 'org', 'project'], [
    { id: 'environment', organizationId: 'org', projectId: 'project', code: 'prod', name: 'Production', kind: 'PROD' },
  ])
  return renderToStaticMarkup(<QueryClientProvider client={queries}><MemoryRouter initialEntries={[path]}>
    <Routes><Route path="/organizations/:organizationId/environments/:environmentId" element={
      <AppShell><WorkspaceHeader title="Infrastructure" actions={<button>Refresh</button>} /></AppShell>
    } /></Routes>
  </MemoryRouter></QueryClientProvider>)
}

describe('workspace shell', () => {
  it('shows module navigation, route-backed context and primary object action', () => {
    const html = renderAt('/organizations/org/environments/environment?project=project')
    expect(html).toContain('aria-label="Основная навигация"')
    expect(html).toContain('nav-item-active')
    expect(html).toContain('Ресурсы')
    expect(html).toContain('Example org')
    expect(html).toContain('App')
    expect(html).toContain('Production')
    expect(html).toContain('Refresh</button>')
  })
})
