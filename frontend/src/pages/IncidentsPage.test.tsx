// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { I18nProvider } from '../i18n'
import type { IncidentListItemResponse } from '../types/incident'
import { IncidentsPage } from './IncidentsPage'

function incident(index: number): IncidentListItemResponse {
  const resourceId = `resource-${index}`
  return {
    id: `incident-${index}`, monitorRuleId: `rule-${index}`, resourceId, status: 'OPEN', reason: 'THRESHOLD',
    startedAt: '2026-09-25T10:00:00Z', openedAt: '2026-09-25T10:00:00Z', resolvedAt: null, createdAt: '', updatedAt: '',
    resource: { id: resourceId, name: `node-${index}`, resourceTypeCode: index % 2 === 0 ? 'NODE' : 'CONTAINER' },
  }
}

function renderPage() {
  // Only the shell's own data is seeded; the incidents and every resource start cold.
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
  client.setQueryData(['me'], { id: 'user', email: 'owner@example.com', displayName: 'Owner' })
  client.setQueryData(['my-organizations'], [{ id: 'org', code: 'ORG', name: 'Org', role: 'MEMBER' }])
  client.setQueryData(['projects', 'org'], [])
  render(<I18nProvider initialLocale="ru"><QueryClientProvider client={client}>
    <MemoryRouter initialEntries={['/organizations/org/incidents?status=OPEN']}><Routes>
      <Route path="/organizations/:organizationId/incidents" element={<IncidentsPage />} />
    </Routes></MemoryRouter>
  </QueryClientProvider></I18nProvider>)
}

describe('incidents page', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it.each([1, 12])('renders %i incidents on distinct resources from one request', async count => {
    const incidents = Array.from({ length: count }, (_, index) => incident(index))
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(incidents),
      { status: 200, headers: { 'Content-Type': 'application/json' } })))
    vi.stubGlobal('fetch', fetchMock)
    renderPage()

    expect(await screen.findByText(`node-${count - 1}`)).toBeTruthy()
    expect(screen.getAllByRole('row')).toHaveLength(count + 1)
    // One read for the list, however many resources it mentions: no request per row.
    expect(fetchMock.mock.calls.map(([url]) => String(url)))
      .toEqual(['/api/v1/organizations/org/incidents?status=OPEN'])
  })
})
