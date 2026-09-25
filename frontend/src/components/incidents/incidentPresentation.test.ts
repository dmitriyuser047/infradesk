import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import {
  formatIncidentDuration,
  getIncidentStatusLabel,
  shortIdentifier,
  getIncidentReasonPresentation,
  getIncidentStatusTone,
  getIncidentTimePresentation,
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

  it('turns reason codes into words, a tone and an icon, in English and Russian', () => {
    const threshold = getIncidentReasonPresentation('THRESHOLD', en)
    expect([threshold.label, threshold.tone, threshold.Icon.displayName]).toEqual(['Threshold exceeded', 'danger', 'TrendingUp'])
    const noData = getIncidentReasonPresentation('NO_DATA', en)
    expect([noData.label, noData.tone, noData.Icon.displayName]).toEqual(['No data received', 'warning', 'EyeOff'])
    expect(getIncidentReasonPresentation('THRESHOLD', ru).label).toBe('Пороговое значение превышено')
    expect(getIncidentReasonPresentation('NO_DATA', ru).label).toBe('Данные не поступают')
    // A reason this frontend does not know stays readable as its code.
    expect(getIncidentReasonPresentation('FUTURE_REASON', ru)).toMatchObject({ label: 'FUTURE_REASON', tone: 'neutral' })
  })

  it('marks open and resolved by tone as well as by word', () => {
    expect(getIncidentStatusTone('OPEN')).toBe('danger')
    expect(getIncidentStatusTone('RESOLVED')).toBe('success')
    expect(getIncidentStatusTone('FUTURE')).toBe('neutral')
  })

  it('dates an open incident by its opening and counts it up to now', () => {
    const now = Date.parse('2026-09-25T14:40:00Z')
    const opened = { openedAt: '2026-09-25T14:32:00Z', resolvedAt: null }

    expect(getIncidentTimePresentation(opened, en, now)).toEqual({ at: '2026-09-25T14:32:00Z',
      label: `Opened ${en.format.dateTime('2026-09-25T14:32:00Z')}`, duration: 'ongoing for 8m 0s' })
    expect(getIncidentTimePresentation(opened, ru, now)).toMatchObject({ duration: 'длится 8 мин 0 с' })
    expect(getIncidentTimePresentation(opened, ru, now).label).toMatch(/^Открыт /)
    expect(getIncidentTimePresentation({ ...opened, openedAt: '2026-09-23T12:00:00Z' }, en, now).duration).toBe('ongoing for 2d 2h')
  })

  it('dates a resolved incident by its resolution and measures it from opening to resolution', () => {
    const resolved = { openedAt: '2026-09-25T13:26:00Z', resolvedAt: '2026-09-25T14:40:00Z' }
    // Resolved incidents do not keep counting.
    const later = Date.parse('2026-09-30T00:00:00Z')

    expect(getIncidentTimePresentation(resolved, en, later)).toEqual({ at: '2026-09-25T14:40:00Z',
      label: `Resolved ${en.format.dateTime('2026-09-25T14:40:00Z')}`, duration: 'lasted 1h 14m' })
    expect(getIncidentTimePresentation(resolved, ru, later)).toMatchObject({ duration: 'длился 1 ч 14 мин' })
    expect(getIncidentTimePresentation(resolved, ru, later).label).toMatch(/^Закрыт /)
  })

  it('gives no duration, and does not fail, for broken timestamps', () => {
    expect(getIncidentTimePresentation({ openedAt: 'not a date', resolvedAt: null }, en).duration).toBeNull()
    expect(getIncidentTimePresentation({ openedAt: '2026-09-25T14:40:00Z', resolvedAt: '2026-09-25T14:00:00Z' }, ru).duration).toBeNull()
    expect(() => getIncidentTimePresentation({ openedAt: '', resolvedAt: null }, ru)).not.toThrow()
  })
})
