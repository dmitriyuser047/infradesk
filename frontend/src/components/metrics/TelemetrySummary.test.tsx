// @vitest-environment jsdom
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { I18nProvider } from '../../i18n'
import { TelemetrySummary } from './TelemetrySummary'
import { formatMonitorCondition } from '../monitoring/monitorRulePresentation'
import { formatMetricValue } from './formatters'
import { createI18n } from '../../i18n'

describe('resource telemetry', () => {
  it('shows device identity and readable byte units without inventing missing readings', () => {
    render(<I18nProvider initialLocale="en"><TelemetrySummary telemetry={{
      metrics: { DISK_FREE_BYTES: 1024 ** 3, LOAD_AVERAGE_1: 120 },
      devices: [{ kind: 'filesystem', name: '/var', metrics: { INODE_USAGE_PERCENT: 95 } }],
    }} /></I18nProvider>)
    expect(screen.getByText('1 GiB')).toBeTruthy()
    expect(screen.getByText('/var')).toBeTruthy()
    expect(screen.getByText('120')).toBeTruthy()
    expect(screen.queryByText('Swap usage')).toBeNull()
    expect(screen.getByText(/SMART\/NVMe unavailable/)).toBeTruthy()
  })

  it('formats rules in the actual metric units', () => {
    const i18n = createI18n('en')
    const rule = { operator: 'GREATER_THAN', threshold: 150, forSeconds: 0 }
    expect(formatMonitorCondition({ ...rule, metricCode: 'SERVICE_RESPONSE_MILLISECONDS' }, i18n)).toContain('150ms')
    expect(formatMonitorCondition({ ...rule, metricCode: 'LOAD_AVERAGE_1' }, i18n)).not.toContain('%')
    expect(formatMonitorCondition({ ...rule, metricCode: 'TLS_DAYS_REMAINING' }, i18n)).toContain('150d')
  })

  it('tells each value in its unit, bytes in binary multiples', () => {
    const i18n = createI18n('en')
    expect(formatMetricValue('DISK_FREE_BYTES', 1.5 * 1024 ** 3, i18n)).toBe('1.5 GiB')
    expect(formatMetricValue('NETWORK_RECEIVE_BYTES_PER_SECOND', 2048, i18n)).toBe('2 KiB/s')
    expect(formatMetricValue('CPU_USAGE_PERCENT', 12.345, i18n)).toBe('12.35%')
    expect(formatMetricValue('SERVICE_RESPONSE_MILLISECONDS', 87, i18n)).toBe('87 ms')
    expect(formatMetricValue('CONTAINER_RESTART_COUNT', 3, i18n)).toBe('3')
  })
})
