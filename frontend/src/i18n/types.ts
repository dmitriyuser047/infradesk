import type { en } from './en'

/** The languages the interface is translated into. Russian is the default. */
export type Locale = 'ru' | 'en'

export const Locales: readonly Locale[] = ['ru', 'en']

export const DefaultLocale: Locale = 'ru'

/**
 * Every user-facing text of the frontend, by screen.
 *
 * English is the reference shape; the Russian dictionary must provide every key with the same
 * signature, so a missing translation is a compile error rather than a mixed-language screen.
 */
export type Messages = typeof en

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (Locales as readonly string[]).includes(value)
}
