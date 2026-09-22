import { describe, expect, it } from 'vitest'

import {
  durationToSeconds,
  formatRuleDuration,
  getMetricLabel,
  getOperatorLabel,
  isSupportedMetricCode,
  isSupportedOperator,
  secondsToDurationInput,
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
})
