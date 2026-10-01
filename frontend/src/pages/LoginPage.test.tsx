// @vitest-environment jsdom
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { I18nProvider } from '../i18n'
import { LoginPage } from './LoginPage'

describe('sign-in brand', () => {
  it('uses the shared decorative InfraDesk mark', () => {
    const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
    const { container } = render(<I18nProvider initialLocale="ru"><QueryClientProvider client={client}>
      <MemoryRouter><LoginPage /></MemoryRouter>
    </QueryClientProvider></I18nProvider>)

    expect(screen.getByText('InfraDesk')).toBeTruthy()
    expect(container.querySelector('.eyebrow svg.brand-mark')?.getAttribute('aria-hidden')).toBe('true')
    expect(container.querySelector('.eyebrow')?.textContent).toBe('InfraDesk')
  })
})
