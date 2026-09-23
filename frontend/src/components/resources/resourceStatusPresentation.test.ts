import { describe, expect, it } from 'vitest'
import { containerStatusPresentation } from './resourceStatusPresentation'

describe('container status presentation', () => {
  it('uses the existing Docker state to choose a semantic tone', () => {
    expect(containerStatusPresentation('running')).toEqual({ label: 'Running', tone: 'success' })
    expect(containerStatusPresentation('EXITED')).toEqual({ label: 'Exited', tone: 'danger' })
    expect(containerStatusPresentation('dead').tone).toBe('danger')
    expect(containerStatusPresentation('paused').tone).toBe('warning')
    expect(containerStatusPresentation('created').tone).toBe('info')
    expect(containerStatusPresentation(null)).toEqual({ label: 'Unknown', tone: 'neutral' })
  })
})
