import { describe, expect, it } from 'vitest'

import { formatDuration, formatMemoryMb } from './formatters'

describe('formatters', () => {
  it('formats uptime without a date library', () => {
    expect(formatDuration(65)).toBe('1m 5s')
    expect(formatDuration(3_665)).toBe('1h 1m')
    expect(formatDuration(183_600)).toBe('2d 3h')
    expect(formatDuration(null)).toBe('—')
  })

  it('formats memory in MB or GB', () => {
    expect(formatMemoryMb(512)).toBe('512 MB')
    expect(formatMemoryMb(8_192)).toBe('8 GB')
    expect(formatMemoryMb(null)).toBe('—')
  })
})
