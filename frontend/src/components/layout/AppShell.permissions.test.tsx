// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
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
      <Routes><Route path="/organizations/:organizationId/*"
        element={<AppShell><h1>Overview</h1></AppShell>} /></Routes>
    </MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

afterEach(() => cleanup())

describe('permission-aware workspace navigation', () => {
  it('shows read-only server profiles to members while keeping sensitive settings owner-only', () => {
    renderShell('MEMBER')
    const memberNav = screen.getByRole('navigation', { name: 'Primary navigation' })
    fireEvent.click(screen.getByRole('button', { name: 'Monitoring' }))
    expect(within(memberNav).queryByRole('link', { name: 'Notifications' })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Automation' }))
    expect(within(memberNav).queryByRole('link', { name: 'Integrations' })).toBeNull()
    expect(within(memberNav).getByRole('link', { name: 'Configurations' })).toBeTruthy()
    cleanup()

    renderShell('OWNER')
    const ownerNav = screen.getByRole('navigation', { name: 'Primary navigation' })
    fireEvent.click(screen.getByRole('button', { name: 'Monitoring' }))
    expect(within(ownerNav).getByRole('link', { name: 'Notifications' })).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Automation' }))
    expect(within(ownerNav).getByRole('link', { name: 'Integrations' })).toBeTruthy()
    expect(within(ownerNav).getByRole('link', { name: 'Configurations' })).toBeTruthy()
    expect(within(ownerNav).queryByRole('link', { name: 'Notifications' })).toBeNull()
  })
})
