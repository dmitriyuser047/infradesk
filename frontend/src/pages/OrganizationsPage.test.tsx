// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it } from 'vitest'

import { I18nProvider } from '../i18n'
import { ApiError } from '../api/httpClient'
import { OrganizationsPage } from './OrganizationsPage'

function setup(failed = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'user@example.test', displayName: 'User' })
  client.setQueryData(['my-organizations'], [
    { id: 'first', name: 'First', displayName: 'Friendly workspace', code: 'alpha', role: 'OWNER' },
    { id: 'second', name: 'Second', code: 'beta', role: 'MEMBER' },
  ])
  if (failed) client.getQueryCache().find({ queryKey: ['my-organizations'] })!
    .setState({ status: 'error', error: new ApiError(403, 'FORBIDDEN', 'unavailable') })
  render(<I18nProvider initialLocale="en"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations']}><OrganizationsPage /></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

afterEach(() => cleanup())

describe('organization entry', () => {
  it('opens only listed memberships and filters cards by display name or code', () => {
    setup()
    const main = within(screen.getByRole('main'))
    const links = main.getAllByRole('link')
    expect(links.map(link => link.getAttribute('href'))).toEqual(['/organizations/first/overview', '/organizations/second/overview'])
    expect(main.getByText('Owner')).toBeTruthy()
    expect(main.getByText('Member')).toBeTruthy()
    fireEvent.change(main.getByRole('textbox', { name: 'Find an organization' }), { target: { value: 'FRIENDLY' } })
    expect(main.getAllByRole('link')).toHaveLength(1)
    fireEvent.change(main.getByRole('textbox'), { target: { value: 'beta' } })
    expect(main.getAllByRole('link')[0].getAttribute('href')).toBe('/organizations/second/overview')
    fireEvent.change(main.getByRole('textbox'), { target: { value: 'missing' } })
    expect(main.queryByRole('link')).toBeNull()
    expect(main.getByText('No matching organizations')).toBeTruthy()
  })

  it('hides cached workspace cards after access to the membership list is rejected', () => {
    setup(true)
    const main = within(screen.getByRole('main'))
    expect(main.queryByRole('link')).toBeNull()
    expect(main.getByText('Unable to load organizations')).toBeTruthy()
    expect(main.getByRole('button', { name: 'Retry' })).toBeTruthy()
  })
})
