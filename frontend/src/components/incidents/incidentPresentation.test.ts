import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import {
  formatIncidentDuration,
  getIncidentStatusLabel,
  shortIdentifier,
  getIncidentReasonPresentation,
} from './incidentPresentation'

const en = createI18n('en')
const ru = createI18n('ru')

describe('incident presentation helpers', () => {
  it('labels known and unknown statuses safely', () => {
    expect(getIncidentStatusLabel('OPEN', en)).toBe('Open')
    expect(getIncidentStatusLabel('RESOLVED', ru)).toBe('Закрыт')
    expect(getIncidentStatusLabel('FUTURE', ru)).toBe('FUTURE')
  })

  it('shortens identifiers without changing short values', () => {
    expect(shortIdentifier('550e8400-e29b-41d4-a716-446655440000')).toBe('550e8400…0000')
    expect(shortIdentifier('short-id')).toBe('short-id')
  })

  it('formats resolved and ongoing durations', () => {
    expect(formatIncidentDuration('2026-09-22T10:00:00Z', '2026-09-22T10:08:14Z', en)).toBe('8m 14s')
    expect(formatIncidentDuration('2026-09-22T10:00:00Z', '2026-09-22T10:08:14Z', ru)).toBe('8 мин 14 с')
    expect(formatIncidentDuration('2026-09-22T10:00:00Z', null, en, Date.parse('2026-09-22T12:30:00Z'))).toBe('2h 30m')
  })

  it('keeps THRESHOLD and NO_DATA apart by wording and tone', () => {
    expect(getIncidentReasonPresentation('THRESHOLD', en)).toEqual({ label: 'Threshold exceeded', tone: 'danger' })
    expect(getIncidentReasonPresentation('NO_DATA', en)).toEqual({ label: 'No data', tone: 'warning' })
    expect(getIncidentReasonPresentation('NO_DATA', ru)).toEqual({ label: 'Нет данных', tone: 'warning' })
    expect(getIncidentReasonPresentation('FUTURE_REASON', ru)).toEqual({ label: 'FUTURE_REASON', tone: 'neutral' })
  })
})
