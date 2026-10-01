import { describe, expect, it } from 'vitest'

import { createI18n } from '../../i18n'
import {
  formatScheduleInterval,
  formatSyncDuration,
  getConnectionScopeLabel,
  getConnectorTypeLabel,
  getLastSyncSummary,
  getSyncFailureMessage,
  getSyncStatusLabel,
} from './connectionPresentation'

const en = createI18n('en')
const ru = createI18n('ru')

describe('connection presentation helpers', () => {
  it('labels known and unknown connector types safely', () => {
    expect(getConnectorTypeLabel('SSH', en)).toBe('SSH')
    expect(getConnectorTypeLabel('DOCKER', en)).toBe('Docker')
    expect(getConnectorTypeLabel('FUTURE', en)).toBe('FUTURE')
  })

  it('labels each known scope in both languages', () => {
    expect(getConnectionScopeLabel({ type: 'ORGANIZATION' }, en)).toBe('Organization')
    expect(getConnectionScopeLabel({ type: 'PROJECT', projectId: 'project' }, en)).toBe('Project')
    expect(getConnectionScopeLabel({ type: 'ENVIRONMENT', projectId: 'project', environmentId: 'environment' }, ru)).toBe('Окружение')
  })

  it('labels sync statuses and keeps an unknown code as it is', () => {
    expect(getSyncStatusLabel('RUNNING', en)).toBe('Running')
    expect(getSyncStatusLabel('COMPLETED', en)).toBe('Successful')
    expect(getSyncStatusLabel('FAILED', ru)).toBe('Ошибка')
    expect(getSyncStatusLabel('UNKNOWN', ru)).toBe('UNKNOWN')
  })

  it('formats common schedule intervals', () => {
    expect(formatScheduleInterval(60, en)).toBe('Every 1m')
    expect(formatScheduleInterval(300, en)).toBe('Every 5m')
    expect(formatScheduleInterval(3_600, en)).toBe('Every 1h')
    expect(formatScheduleInterval(600, ru)).toBe('Каждые 10 мин')
  })

  it('formats completed, running, and invalid sync durations', () => {
    expect(formatSyncDuration('2026-09-22T10:00:00Z', '2026-09-22T10:00:48Z', en)).toBe('48s')
    expect(formatSyncDuration('2026-09-22T10:00:00Z', '2026-09-22T10:01:05Z', ru)).toBe('1 мин 5 с')
    expect(formatSyncDuration('2026-09-22T10:00:00Z', null, en)).toBe('In progress')
    expect(formatSyncDuration('invalid', '2026-09-22T10:00:48Z', en)).toBe('—')
  })

  it('keeps last-sync secondary text free of duplicated status', () => {
    expect(getLastSyncSummary(null, en)).toBe('Never synchronized')
    expect(getLastSyncSummary({
      id: 'sync', status: 'RUNNING', startedAt: '2026-09-22T10:00:00Z', finishedAt: null,
      errorCode: null, errorMessage: null,
    }, en)).toMatch(/^Started /)
    expect(getLastSyncSummary({
      id: 'sync', status: 'COMPLETED', startedAt: '2026-09-22T10:00:00Z', finishedAt: '2026-09-22T10:00:48Z',
      errorCode: null, errorMessage: null,
    }, en)).not.toContain('Completed')
  })

  it('words a failure by its code, and never shows an English server message in Russian', () => {
    const failure = { errorCode: 'SSH_HOST_KEY_MISMATCH', errorMessage: 'SSH host key has changed' }
    expect(getSyncFailureMessage(failure, ru)).toBe('Ключ сервера изменился. Подключение заблокировано.')
    expect(getSyncFailureMessage(failure, en)).toBe('Server host key changed. Connection was blocked.')

    const novel = { errorCode: 'SOMETHING_NEW', errorMessage: 'Something new happened' }
    expect(getSyncFailureMessage(novel, en)).toBe('Something new happened')
    expect(getSyncFailureMessage(novel, ru)).toBe('Синхронизация не выполнена')
    expect(getSyncFailureMessage({ errorCode: null, errorMessage: null }, en)).toBe('Synchronization failed')
  })
})
