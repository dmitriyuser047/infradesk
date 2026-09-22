import { describe, expect, it } from 'vitest'

import {
  formatIncidentDuration,
  getIncidentStatusLabel,
  shortIdentifier,
} from './incidentPresentation'

describe('incident presentation helpers', () => {
  it('labels known and unknown statuses safely', () => {
    expect(getIncidentStatusLabel('OPEN')).toBe('Open')
    expect(getIncidentStatusLabel('RESOLVED')).toBe('Resolved')
    expect(getIncidentStatusLabel('FUTURE')).toBe('FUTURE')
  })

  it('shortens identifiers without changing short values', () => {
    expect(shortIdentifier('550e8400-e29b-41d4-a716-446655440000')).toBe('550e8400…0000')
    expect(shortIdentifier('short-id')).toBe('short-id')
  })

  it('formats resolved and ongoing durations', () => {
    expect(formatIncidentDuration('2026-09-22T10:00:00Z', '2026-09-22T10:08:14Z')).toBe('8m 14s')
    expect(formatIncidentDuration('2026-09-22T10:00:00Z', null)).toBe('Ongoing')
  })
})
