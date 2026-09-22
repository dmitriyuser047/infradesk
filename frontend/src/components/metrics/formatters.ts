export function formatDuration(seconds: number | null): string {
  if (seconds === null || seconds < 0 || !Number.isFinite(seconds)) {
    return '—'
  }

  const wholeSeconds = Math.floor(seconds)
  const days = Math.floor(wholeSeconds / 86_400)
  const hours = Math.floor((wholeSeconds % 86_400) / 3_600)
  const minutes = Math.floor((wholeSeconds % 3_600) / 60)
  const remainingSeconds = wholeSeconds % 60

  if (days > 0) {
    return `${days}d ${hours}h`
  }
  if (hours > 0) {
    return `${hours}h ${minutes}m`
  }
  if (minutes > 0) {
    return `${minutes}m ${remainingSeconds}s`
  }
  return `${remainingSeconds}s`
}

export function formatMemoryMb(memoryMb: number | null): string {
  if (memoryMb === null || memoryMb < 0 || !Number.isFinite(memoryMb)) {
    return '—'
  }

  if (memoryMb >= 1_024) {
    const gigabytes = memoryMb / 1_024
    return `${Number.isInteger(gigabytes) ? gigabytes : gigabytes.toFixed(1)} GB`
  }

  return `${memoryMb} MB`
}

export function formatPercent(value: number | null): string {
  if (value === null || !Number.isFinite(value)) {
    return '—'
  }

  return `${value.toFixed(1)}%`
}

export function formatMetricTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }

  return new Intl.DateTimeFormat(undefined, {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}
