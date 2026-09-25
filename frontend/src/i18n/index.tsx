import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import { en } from './en'
import { createFormatters, type Formatters } from './format'
import { ru } from './ru'
import { DefaultLocale, isLocale, type Locale, type Messages } from './types'

export type { Locale, Messages } from './types'
export { DefaultLocale, Locales, isLocale } from './types'

/** Where the chosen language is remembered, per browser. There is no server-side preference. */
export const LocaleStorageKey = 'infradesk.locale'

const dictionaries: Record<Locale, Messages> = { ru, en }

export interface I18n {
  locale: Locale
  t: Messages
  format: Formatters
  setLocale: (locale: Locale) => void
}

/** A complete, stateless view of one locale: for pure presentation functions and tests. */
export function createI18n(locale: Locale, setLocale: (locale: Locale) => void = () => undefined): I18n {
  const t = dictionaries[locale]
  return { locale, t, format: createFormatters(locale, t.units), setLocale }
}

interface LocaleStorage {
  getItem: (key: string) => string | null
  setItem: (key: string, value: string) => void
}

function browserStorage(): LocaleStorage | null {
  try {
    return typeof window === 'undefined' ? null : window.localStorage
  } catch {
    return null
  }
}

/** The remembered language, or Russian when nothing (or something unreadable) is stored. */
export function readStoredLocale(storage: LocaleStorage | null = browserStorage()): Locale {
  try {
    const stored = storage?.getItem(LocaleStorageKey)
    return isLocale(stored) ? stored : DefaultLocale
  } catch {
    return DefaultLocale
  }
}

export function storeLocale(locale: Locale, storage: LocaleStorage | null = browserStorage()): void {
  try {
    storage?.setItem(LocaleStorageKey, locale)
  } catch {
    // Private mode or blocked storage: the choice lasts for this page only.
  }
}

// Without a provider — in isolated component tests — the interface is in the default language.
const I18nContext = createContext<I18n>(createI18n(DefaultLocale))

export function I18nProvider({ children, initialLocale }: { children: ReactNode; initialLocale?: Locale }) {
  const [locale, setLocaleState] = useState<Locale>(() => initialLocale ?? readStoredLocale())

  const setLocale = useCallback((next: Locale) => {
    storeLocale(next)
    setLocaleState(next)
  }, [])

  useEffect(() => {
    if (typeof document !== 'undefined') document.documentElement.lang = locale
  }, [locale])

  const value = useMemo(() => createI18n(locale, setLocale), [locale, setLocale])
  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>
}

export function useI18n(): I18n {
  return useContext(I18nContext)
}
