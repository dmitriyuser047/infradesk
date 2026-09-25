import { describe, expect, it } from 'vitest'

import {
  durationToSeconds,
  formatNoDataTimeout,
  formatRuleDuration,
  getMetricLabel,
  getMonitorRuleStatusPresentation,
  getOperatorLabel,
  getOperatorSymbol,
  isSupportedMetricCode,
  isSupportedOperator,
  secondsToDurationInput,
  supportedOperators,
} from './monitorRulePresentation'
import { createI18n } from '../../i18n'

const en = createI18n('en')
const ru = createI18n('ru')

describe('monitor rule presentation helpers', () => {
  it('converts duration input to seconds', () => {
    expect(durationToSeconds(5, 'minutes')).toBe(300)
    expect(durationToSeconds(1, 'hours')).toBe(3_600)
  })

  it('chooses the largest exact duration unit for editing', () => {
    expect(secondsToDurationInput(300)).toEqual({ value: 5, unit: 'minutes' })
    expect(secondsToDurationInput(3_600)).toEqual({ value: 1, unit: 'hours' })
    expect(secondsToDurationInput(90)).toEqual({ value: 90, unit: 'seconds' })
  })

  it('formats rule durations with immediate semantics', () => {
    expect(formatRuleDuration(0, en)).toBe('Immediately')
    expect(formatRuleDuration(0, ru)).toBe('Сразу')
    expect(formatRuleDuration(60, en)).toBe('1m')
    expect(formatRuleDuration(300, en)).toBe('5m')
    expect(formatRuleDuration(3_600, en)).toBe('1h')
  })

  it('uses human labels and safely falls back for unknown codes', () => {
    expect(getMetricLabel('CPU_USAGE_PERCENT', en)).toBe('CPU usage')
    expect(getMetricLabel('CPU_USAGE_PERCENT', ru)).toBe('Загрузка процессора')
    expect(getMetricLabel('FUTURE_METRIC', en)).toBe('FUTURE_METRIC')
    expect(getOperatorLabel('GREATER_THAN', en)).toBe('Greater than')
    expect(getOperatorLabel('FUTURE_OPERATOR', en)).toBe('FUTURE_OPERATOR')
    expect(isSupportedMetricCode('CPU_USAGE_PERCENT')).toBe(true)
    expect(isSupportedMetricCode('FUTURE_METRIC')).toBe(false)
    expect(isSupportedOperator('GREATER_THAN')).toBe(true)
    expect(isSupportedOperator('FUTURE_OPERATOR')).toBe(false)
  })

  it('supports the four numeric comparison operators', () => {
    expect(supportedOperators()).toEqual([
      'GREATER_THAN', 'GREATER_THAN_OR_EQUAL', 'LESS_THAN', 'LESS_THAN_OR_EQUAL',
    ])
    expect(supportedOperators().map(getOperatorSymbol)).toEqual(['>', '>=', '<', '<='])
    expect(supportedOperators().map(operator => getOperatorLabel(operator, en))).toEqual([
      'Greater than', 'Greater than or equal', 'Less than', 'Less than or equal',
    ])
    expect(supportedOperators().every(isSupportedOperator)).toBe(true)
  })

  it('formats the no-data timeout and treats zero as disabled', () => {
    expect(formatNoDataTimeout(0, en)).toBe('off')
    expect(formatNoDataTimeout(0, ru)).toBe('выкл.')
    expect(formatNoDataTimeout(900, ru)).toBe('15 мин')
    expect(formatNoDataTimeout(900, en)).toBe('15m')
    expect(formatNoDataTimeout(3_600, en)).toBe('1h')
  })

  it('presents every rule status, including NO_DATA, with an existing tone', () => {
    expect(getMonitorRuleStatusPresentation('OK', true, en)).toEqual({ label: 'OK', tone: 'success' })
    expect(getMonitorRuleStatusPresentation('PENDING', true, en)).toEqual({ label: 'Pending', tone: 'warning' })
    expect(getMonitorRuleStatusPresentation('FIRING', true, en)).toEqual({ label: 'Firing', tone: 'danger' })
    expect(getMonitorRuleStatusPresentation('NO_DATA', true, en)).toEqual({ label: 'No data', tone: 'warning' })
    expect(getMonitorRuleStatusPresentation(null, true, en)).toEqual({ label: 'Enabled', tone: 'success' })
    expect(getMonitorRuleStatusPresentation('FIRING', false, en)).toEqual({ label: 'Disabled', tone: 'neutral' })
    expect(getMonitorRuleStatusPresentation('NO_DATA', true, ru)).toEqual({ label: 'Нет данных', tone: 'warning' })
  })
})
