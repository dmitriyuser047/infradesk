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
    expect(formatRuleDuration(0)).toBe('Immediately')
    expect(formatRuleDuration(60)).toBe('1m')
    expect(formatRuleDuration(300)).toBe('5m')
    expect(formatRuleDuration(3_600)).toBe('1h')
  })

  it('uses human labels and safely falls back for unknown codes', () => {
    expect(getMetricLabel('CPU_USAGE_PERCENT')).toBe('CPU usage')
    expect(getMetricLabel('FUTURE_METRIC')).toBe('FUTURE_METRIC')
    expect(getOperatorLabel('GREATER_THAN')).toBe('Greater than')
    expect(getOperatorLabel('FUTURE_OPERATOR')).toBe('FUTURE_OPERATOR')
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
    expect(supportedOperators().map(getOperatorLabel)).toEqual([
      'Greater than', 'Greater than or equal', 'Less than', 'Less than or equal',
    ])
    expect(supportedOperators().every(isSupportedOperator)).toBe(true)
  })

  it('formats the no-data timeout and treats zero as disabled', () => {
    expect(formatNoDataTimeout(0)).toBe('off')
    expect(formatNoDataTimeout(900)).toBe('15m')
    expect(formatNoDataTimeout(3_600)).toBe('1h')
  })

  it('presents every rule status, including NO_DATA, with an existing tone', () => {
    expect(getMonitorRuleStatusPresentation('OK', true)).toEqual({ label: 'OK', tone: 'success' })
    expect(getMonitorRuleStatusPresentation('PENDING', true)).toEqual({ label: 'Pending', tone: 'warning' })
    expect(getMonitorRuleStatusPresentation('FIRING', true)).toEqual({ label: 'Firing', tone: 'danger' })
    expect(getMonitorRuleStatusPresentation('NO_DATA', true)).toEqual({ label: 'No data', tone: 'warning' })
    expect(getMonitorRuleStatusPresentation(null, true)).toEqual({ label: 'Enabled', tone: 'success' })
    expect(getMonitorRuleStatusPresentation('FIRING', false)).toEqual({ label: 'Disabled', tone: 'neutral' })
  })
})
