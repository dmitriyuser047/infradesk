import type { I18n } from '../../i18n'
import { metricUnit } from '../../types/metric'

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

/**
 * A metric value in its own unit: percentages, milliseconds, days or degrees as they are, byte
 * counts and rates in binary multiples, plain counts without a unit.
 */
export function formatMetricValue(code: string, value: number, i18n: I18n): string {
  const unit = metricUnit(code)
  if (unit === 'B' || unit === 'B/s') {
    const scales: [number, string][] = [[1024 ** 3, 'GiB'], [1024 ** 2, 'MiB'], [1024, 'KiB'], [1, 'B']]
    const [scale, label] = scales.find(([size]) => Math.abs(value) >= size) ?? [1, 'B']
    return `${i18n.format.number(Math.round(value / scale * 100) / 100)} ${label}${unit === 'B/s' ? '/s' : ''}`
  }
  const rounded = i18n.format.number(Math.round(value * 100) / 100)
  // Symbols that read attached ("85%", "21°C", "14d") stay attached; words are spaced.
  if (unit === '' ) return rounded
  return unit === '%' || unit === 'd' || unit === '°C' ? `${rounded}${unit}` : `${rounded} ${unit}`
}
