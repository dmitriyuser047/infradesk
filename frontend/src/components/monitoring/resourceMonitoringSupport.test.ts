import { describe, expect, it } from 'vitest'

import { supportsResourceMonitoring } from './resourceMonitoringSupport'

describe('resource monitoring support', () => {
  it('applies to nodes and containers', () => {
    expect(supportsResourceMonitoring('NODE')).toBe(true)
    expect(supportsResourceMonitoring('CONTAINER')).toBe(true)
    expect(supportsResourceMonitoring('NEW_SERVER_TYPE')).toBe(false)
    expect(supportsResourceMonitoring(undefined)).toBe(false)
  })
})
