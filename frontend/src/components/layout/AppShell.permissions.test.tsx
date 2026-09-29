// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'

import { I18nProvider } from '../../i18n'
import { AppShell } from './AppShell'

function renderShell(role: 'OWNER' | 'MEMBER') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'user@example.test', displayName: 'User' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role }])
  client.setQueryData(['projects', 'org'], [])

  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/overview']}>
      <Routes><Route path="/organizations/:organizationId/overview"
        element={<AppShell><h1>Overview</h1></AppShell>} /></Routes>
    </MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

afterEach(() => cleanup())

describe('permission-aware workspace navigation', () => {
  it('shows sensitive settings only to owners', () => {
    renderShell('MEMBER')
    const memberNav = screen.getByRole('navigation', { name: 'Primary navigation' })
    expect(within(memberNav).queryByRole('link', { name: 'Notifications' })).toBeNull()
    expect(within(memberNav).queryByRole('link', { name: 'Integrations' })).toBeNull()
    cleanup()

    renderShell('OWNER')
    const ownerNav = screen.getByRole('navigation', { name: 'Primary navigation' })
    expect(within(ownerNav).getByRole('link', { name: 'Notifications' })).toBeTruthy()
    expect(within(ownerNav).getByRole('link', { name: 'Integrations' })).toBeTruthy()
  })
})
