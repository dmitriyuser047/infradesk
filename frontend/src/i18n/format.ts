import type { Locale } from './types'

const intlLocale: Record<Locale, string> = { ru: 'ru-RU', en: 'en-US' }

export interface DurationUnits {
  day: string
  hour: string
  minute: string
  second: string
}

/**
 * Dates, times, durations and numbers for one locale.
 *
 * Timestamps arrive from the API as UTC instants; they are shown in the browser's time zone.
 */
export interface Formatters {
  dateTime: (iso: string) => string
  time: (iso: string) => string
  relative: (iso: string, now?: number) => string
  duration: (seconds: number | null) => string
  number: (value: number) => string
}

export function createFormatters(locale: Locale, units: DurationUnits): Formatters {
  const tag = intlLocale[locale]
  const dateTime = new Intl.DateTimeFormat(tag, { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
  const time = new Intl.DateTimeFormat(tag, { hour: '2-digit', minute: '2-digit' })
  const relative = new Intl.RelativeTimeFormat(tag, { numeric: 'auto' })
  const number = new Intl.NumberFormat(tag)

  return {
    dateTime: iso => withDate(iso, date => dateTime.format(date)),
    time: iso => withDate(iso, date => time.format(date)),
    relative: (iso, now = Date.now()) => withDate(iso, date => {
      const seconds = Math.round((date.getTime() - now) / 1_000)
      const absolute = Math.abs(seconds)
      if (absolute < 45) return relative.format(0, 'second')
      if (absolute < 3_600) return relative.format(Math.round(seconds / 60), 'minute')
      if (absolute < 86_400) return relative.format(Math.round(seconds / 3_600), 'hour')
      if (absolute < 7 * 86_400) return relative.format(Math.round(seconds / 86_400), 'day')
      return dateTime.format(date)
    }),
    duration: seconds => formatDuration(seconds, units),
    number: value => number.format(value),
  }
}

function withDate(iso: string, format: (date: Date) => string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : format(date)
}

/** The two most significant units: "2d 3h", "4m 10s" — or "2 д 3 ч" in Russian. */
export function formatDuration(seconds: number | null, units: DurationUnits): string {
  if (seconds === null || !Number.isFinite(seconds) || seconds < 0) return '—'

  const whole = Math.floor(seconds)
  const days = Math.floor(whole / 86_400)
  const hours = Math.floor((whole % 86_400) / 3_600)
  const minutes = Math.floor((whole % 3_600) / 60)
  const rest = whole % 60
  const part = (value: number, unit: string) => `${value}${unit}`

  if (days > 0) return `${part(days, units.day)} ${part(hours, units.hour)}`
  if (hours > 0) return `${part(hours, units.hour)} ${part(minutes, units.minute)}`
  if (minutes > 0) return `${part(minutes, units.minute)} ${part(rest, units.second)}`
  return part(rest, units.second)
}

/** Seconds between two instants, or null when either is missing or they are out of order. */
export function secondsBetween(from: string, to: string | null, now = Date.now()): number | null {
  const start = new Date(from).getTime()
  const end = to === null ? now : new Date(to).getTime()
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null
  return (end - start) / 1_000
}
