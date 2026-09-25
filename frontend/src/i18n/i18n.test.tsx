// @vitest-environment jsdom
import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { en } from './en'
import { createI18n, I18nProvider, LocaleStorageKey, readStoredLocale, useI18n } from './index'
import { ru } from './ru'
import { describeError, describeFailure } from './errors'
import { ApiError } from '../api/httpClient'

function Probe() {
  const { t, locale, setLocale } = useI18n()
  return <div>
    <p data-testid="nav">{t.shell.nav.overview} · {t.shell.nav.connections}</p>
    <p data-testid="locale">{locale}</p>
    <button type="button" onClick={() => setLocale(locale === 'ru' ? 'en' : 'ru')}>switch</button>
  </div>
}

describe('localization', () => {
  beforeEach(() => window.localStorage.clear())
  afterEach(() => cleanup())

  it('starts in Russian when nothing is remembered', () => {
    render(<I18nProvider><Probe /></I18nProvider>)

    expect(screen.getByTestId('locale').textContent).toBe('ru')
    expect(screen.getByTestId('nav').textContent).toBe('Обзор · Подключения')
    expect(document.documentElement.lang).toBe('ru')
  })

  it('switches to English without a reload and remembers the choice', () => {
    render(<I18nProvider><Probe /></I18nProvider>)

    act(() => screen.getByText('switch').click())

    expect(screen.getByTestId('nav').textContent).toBe('Overview · Connections')
    expect(window.localStorage.getItem(LocaleStorageKey)).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('reads the remembered language when the application starts again', () => {
    window.localStorage.setItem(LocaleStorageKey, 'en')

    render(<I18nProvider><Probe /></I18nProvider>)

    expect(screen.getByTestId('locale').textContent).toBe('en')
  })

  it('falls back to Russian for a damaged or unknown stored value', () => {
    for (const stored of ['de', '', 'EN', '{"locale":"en"}']) {
      expect(readStoredLocale({ getItem: () => stored, setItem: () => undefined })).toBe('ru')
    }
    expect(readStoredLocale({ getItem: () => { throw new Error('blocked') }, setItem: () => undefined })).toBe('ru')
    expect(readStoredLocale(null)).toBe('ru')
  })

  it('translates every English key into Russian', () => {
    const missing: string[] = []
    const walk = (reference: Record<string, unknown>, translation: Record<string, unknown>, path: string) => {
      for (const [key, value] of Object.entries(reference)) {
        const other = translation[key]
        if (other === undefined) missing.push(`${path}${key}`)
        else if (typeof value === 'object' && value !== null) walk(value as Record<string, unknown>, other as Record<string, unknown>, `${path}${key}.`)
      }
    }
    walk(en as unknown as Record<string, unknown>, ru as unknown as Record<string, unknown>, '')

    expect(missing).toEqual([])
  })

  it('keeps domain codes as codes and only translates their presentation', () => {
    const russian = createI18n('ru')

    // The dictionaries are keyed by the stable backend codes.
    expect(russian.t.incidents.reasons.NO_DATA).toBe('Нет данных')
    expect(russian.t.operations.statuses.UNKNOWN).toBe('Результат неизвестен')
    expect(russian.t.connections.authTypes.PRIVATE_KEY).toBe('Приватный ключ')
    expect(createI18n('en').t.operations.statuses.UNKNOWN).toBe('Result unknown')
  })

  it('formats time and durations for the active language', () => {
    const now = Date.parse('2026-09-25T12:00:00Z')

    expect(createI18n('ru').format.relative('2026-09-25T11:53:00Z', now)).toBe('7 минут назад')
    expect(createI18n('en').format.relative('2026-09-25T11:53:00Z', now)).toBe('7 minutes ago')
    expect(createI18n('ru').format.duration(3_665)).toBe('1 ч 1 мин')
    expect(createI18n('en').format.duration(183_600)).toBe('2d 3h')
    expect(createI18n('en').format.duration(null)).toBe('—')
    expect(createI18n('ru').format.dateTime('not-a-date')).toBe('—')
  })

  it('translates known error codes and never shows an English server message in Russian', () => {
    const russian = createI18n('ru')
    const english = createI18n('en')
    const mismatch = new ApiError(502, 'SSH_HOST_KEY_MISMATCH', 'SSH host identity has changed')
    const novel = new ApiError(400, 'SOMETHING_NEW', 'Something new went wrong')

    expect(describeError(mismatch, russian)).toBe('Ключ сервера изменился. Подключение заблокировано.')
    expect(describeError(mismatch, english)).toBe('Server host key changed. Connection was blocked.')
    expect(describeError(novel, english)).toBe('Something new went wrong')
    expect(describeError(novel, russian)).toBe(russian.t.errors.generic)
    expect(describeError(new Error('java.lang.NullPointerException at ru.bitec'), english)).toBe(english.t.errors.generic)
    expect(describeFailure(null, 'raw', russian, 'fallback')).toBe('fallback')
  })
})
