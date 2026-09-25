import type { I18n } from '../../i18n'

const tags = { ru: 'ru-RU', en: 'en-US' } as const

export function formatMemoryMb(memoryMb: number | null, i18n: I18n): string {
  if (memoryMb === null || memoryMb < 0 || !Number.isFinite(memoryMb)) {
    return '—'
  }

  const number = new Intl.NumberFormat(tags[i18n.locale], { maximumFractionDigits: 1 })
  if (memoryMb >= 1_024) {
    return `${number.format(memoryMb / 1_024)} ${i18n.t.units.gigabytes}`
  }

  return `${number.format(memoryMb)} ${i18n.t.units.megabytes}`
}

export function formatPercent(value: number | null, i18n: I18n): string {
  if (value === null || !Number.isFinite(value)) {
    return '—'
  }

  return `${new Intl.NumberFormat(tags[i18n.locale], { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(value)}%`
}
