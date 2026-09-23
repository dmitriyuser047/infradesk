import { describe, expect, it } from 'vitest'

import { supportsResourceMonitoring } from './resourceMonitoringSupport'

describe('resource monitoring support', () => {
  it('applies to nodes only', () => {
    expect(supportsResourceMonitoring('NODE')).toBe(true)
    expect(supportsResourceMonitoring('CONTAINER')).toBe(false)
    expect(supportsResourceMonitoring('NEW_SERVER_TYPE')).toBe(false)
    expect(supportsResourceMonitoring(undefined)).toBe(false)
  })
})
