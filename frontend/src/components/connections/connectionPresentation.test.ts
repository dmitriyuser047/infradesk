import { describe, expect, it } from 'vitest'

import {
  formatConnectionDateTime,
  formatScheduleInterval,
  formatSyncDuration,
  getConnectionScopeLabel,
  getConnectorTypeLabel,
  getLastSyncSummary,
  getSyncFailureMessage,
  getSyncStatusLabel,
} from './connectionPresentation'

describe('connection presentation helpers', () => {
  it('labels known and unknown connector types safely', () => {
    expect(getConnectorTypeLabel('SSH')).toBe('SSH')
    expect(getConnectorTypeLabel('DOCKER')).toBe('Docker')
    expect(getConnectorTypeLabel('FUTURE')).toBe('FUTURE')
  })

  it('labels each known scope', () => {
    expect(getConnectionScopeLabel({ type: 'ORGANIZATION' })).toBe('Organization')
    expect(getConnectionScopeLabel({ type: 'PROJECT', projectId: 'project' })).toBe('Project')
    expect(getConnectionScopeLabel({ type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' })).toBe('Environment')
  })

  it('labels known and unknown sync statuses safely', () => {
    expect(getSyncStatusLabel('RUNNING')).toBe('Running')
    expect(getSyncStatusLabel('COMPLETED')).toBe('Completed')
    expect(getSyncStatusLabel('FAILED')).toBe('Failed')
    expect(getSyncStatusLabel('UNKNOWN')).toBe('UNKNOWN')
  })

  it('formats common schedule intervals', () => {
    expect(formatScheduleInterval(60)).toBe('Every 1m')
    expect(formatScheduleInterval(300)).toBe('Every 5m')
    expect(formatScheduleInterval(3_600)).toBe('Every 1h')
  })

  it('formats completed, running, and invalid sync durations', () => {
    expect(formatSyncDuration('2026-09-22T10:00:00Z', '2026-09-22T10:00:48Z')).toBe('48s')
    expect(formatSyncDuration('2026-09-22T10:00:00Z', null)).toBe('In progress')
    expect(formatSyncDuration('invalid', '2026-09-22T10:00:48Z')).toBe('—')
  })

  it('keeps last-sync secondary text free of duplicated status', () => {
    expect(getLastSyncSummary(null)).toBe('Never synchronized')
    expect(getLastSyncSummary({
      id: 'sync',
      status: 'RUNNING',
      startedAt: '2026-09-22T10:00:00Z',
      finishedAt: null,
      errorCode: null,
      errorMessage: null,
    })).toMatch(/^Started /)
    expect(getLastSyncSummary({
      id: 'sync',
      status: 'COMPLETED',
      startedAt: '2026-09-22T10:00:00Z',
      finishedAt: '2026-09-22T10:00:48Z',
      errorCode: null,
      errorMessage: null,
    })).not.toContain('Completed')
  })

  it('returns a placeholder for invalid dates', () => {
    expect(formatConnectionDateTime('invalid')).toBe('—')
  })

  it('uses a safe fallback for missing failure details', () => {
    expect(getSyncFailureMessage(null)).toBe('Synchronization failed')
    expect(getSyncFailureMessage('')).toBe('Synchronization failed')
    expect(getSyncFailureMessage('Synchronization failed')).toBe('Synchronization failed')
  })
})
