// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { ApiError } from '../../api/httpClient'
import { I18nProvider } from '../../i18n'
import { CopyButton, PageLoading, PageUnavailable } from './WorkspacePrimitives'

function renderInApp(node: React.ReactNode) {
  return render(<I18nProvider initialLocale="ru"><MemoryRouter>{node}</MemoryRouter></I18nProvider>)
}

describe('shared page primitives', () => {
  afterEach(() => { cleanup(); vi.unstubAllGlobals() })

  it('forgets "copied" as soon as the value to copy changes', async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } })
    const { rerender } = renderInApp(<CopyButton value="SHA256:first" />)
    await act(async () => { fireEvent.click(screen.getByRole('button')) })
    expect(screen.getByRole('button').textContent).toBe('Скопировано')

    rerender(<I18nProvider initialLocale="ru"><MemoryRouter><CopyButton value="SHA256:second" /></MemoryRouter></I18nProvider>)
    expect(screen.getByRole('button').textContent).toBe('Скопировать')
  })

  it('keeps the header and the way back while a detail page loads', () => {
    renderInApp(<PageLoading title="Загрузка подключения" back={{ label: 'Подключения', to: '/list' }} label="Загрузка подключения" />)

    expect(screen.getByRole('heading', { name: 'Загрузка подключения' })).toBeTruthy()
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/list')
    expect(document.querySelector('[aria-busy="true"]')).not.toBeNull()
  })

  it('explains a missing object without a retry, and a failed load with one and without the server message', () => {
    const onRetry = vi.fn()
    renderInApp(<PageUnavailable back={{ label: 'Назад', to: '/list' }} notFound notFoundTitle="Не найдено" errorTitle="Ошибка"
      error={null} onRetry={onRetry} />)
    expect(screen.getAllByText('Не найдено').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Повторить' })).toBeNull()
    cleanup()

    renderInApp(<PageUnavailable back={{ label: 'Назад', to: '/list' }} notFound={false} notFoundTitle="Не найдено" errorTitle="Ошибка"
      error={new ApiError(500, 'INTERNAL_ERROR', 'relation "secret" does not exist')} onRetry={onRetry} />)
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }))
    expect(onRetry).toHaveBeenCalledOnce()
    expect(document.body.textContent).not.toContain('secret')
    expect(document.querySelector('.workspace-back')?.getAttribute('href')).toBe('/list')
  })
})
