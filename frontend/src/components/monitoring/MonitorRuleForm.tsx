import { useState } from 'react'

import type { MonitorRuleRequest, MonitorRuleResponse, MonitorOperatorCode } from '../../types/monitorRule'
import { MetricCode, type KnownMetricCode } from '../../types/metric'
import {
  durationToSeconds,
  getMetricLabel,
  getOperatorLabel,
  secondsToDurationInput,
  supportedMetricCodes,
  supportedOperators,
  isSupportedMetricCode,
  isSupportedOperator,
  type DurationUnit,
} from './monitorRulePresentation'

interface MonitorRuleFormProps {
  rule?: MonitorRuleResponse
  pending: boolean
  errorMessage?: string
  onSubmit: (request: MonitorRuleRequest) => Promise<void>
  onCancel: () => void
}

interface FormState {
  metricCode: KnownMetricCode
  operator: MonitorOperatorCode
  threshold: string
  durationValue: string
  durationUnit: DurationUnit
  noDataValue: string
  noDataUnit: DurationUnit
  enabled: boolean
}

const defaultState: FormState = {
  metricCode: MetricCode.cpuUsagePercent,
  operator: 'GREATER_THAN',
  threshold: '80',
  durationValue: '5',
  durationUnit: 'minutes',
  noDataValue: '15',
  noDataUnit: 'minutes',
  enabled: true,
}

export function MonitorRuleForm({
  rule,
  pending,
  errorMessage,
  onSubmit,
  onCancel,
}: MonitorRuleFormProps) {
  const [form, setForm] = useState<FormState | null>(() => rule === undefined ? defaultState : stateFromRule(rule))
  const [validationError, setValidationError] = useState<string | undefined>()

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    if (form === null) {
      return
    }
    setForm({ ...form, [key]: value })
    setValidationError(undefined)
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (form === null) {
      return
    }
    const threshold = Number(form.threshold)
    const durationValue = Number(form.durationValue)
    const noDataValue = Number(form.noDataValue)

    if (form.threshold.trim() === '' || !Number.isFinite(threshold)) {
      setValidationError('Enter a finite threshold.')
      return
    }
    if (threshold < 0 || threshold > 100) {
      setValidationError('Threshold must be between 0 and 100 percent.')
      return
    }
    if (form.durationValue.trim() === '' || !Number.isFinite(durationValue) || durationValue < 0) {
      setValidationError('Duration must be zero or greater.')
      return
    }
    if (form.noDataValue.trim() === '' || !Number.isFinite(noDataValue) || noDataValue < 0) {
      setValidationError('No data timeout must be zero or greater.')
      return
    }

    await onSubmit({
      metricCode: form.metricCode,
      operator: form.operator,
      threshold,
      forSeconds: durationToSeconds(durationValue, form.durationUnit),
      noDataSeconds: durationToSeconds(noDataValue, form.noDataUnit),
      enabled: form.enabled,
    })
  }

  if (form === null) {
    return (
      <div className="monitor-rule-form">
        <p className="form-error" role="alert">This rule type is not supported for editing yet.</p>
        <div className="dialog-actions">
          <button className="secondary-button" type="button" onClick={onCancel}>Close</button>
        </div>
      </div>
    )
  }

  const error = validationError ?? errorMessage

  return (
    <form className="monitor-rule-form" onSubmit={submit}>
      <label>
        Metric
        <select value={form.metricCode} onChange={(event) => update('metricCode', event.target.value as KnownMetricCode)}>
          {supportedMetricCodes().map((metricCode) => (
            <option key={metricCode} value={metricCode}>{getMetricLabel(metricCode)}</option>
          ))}
        </select>
      </label>
      <label>
        Operator
        <select value={form.operator} onChange={(event) => update('operator', event.target.value as MonitorOperatorCode)}>
          {supportedOperators().map((operator) => (
            <option key={operator} value={operator}>{getOperatorLabel(operator)}</option>
          ))}
        </select>
      </label>
      <label>
        Threshold
        <input type="number" step="0.1" value={form.threshold} onChange={(event) => update('threshold', event.target.value)} />
      </label>
      <div className="duration-fields">
        <label>
          For
          <input type="number" min="0" step="1" value={form.durationValue} onChange={(event) => update('durationValue', event.target.value)} />
        </label>
        <label>
          Unit
          <select value={form.durationUnit} onChange={(event) => update('durationUnit', event.target.value as DurationUnit)}>
            <option value="seconds">Seconds</option>
            <option value="minutes">Minutes</option>
            <option value="hours">Hours</option>
          </select>
        </label>
      </div>
      <div className="duration-fields">
        <label>
          No data for
          <input type="number" min="0" step="1" value={form.noDataValue} onChange={(event) => update('noDataValue', event.target.value)} />
        </label>
        <label>
          Unit
          <select value={form.noDataUnit} onChange={(event) => update('noDataUnit', event.target.value as DurationUnit)}>
            <option value="seconds">Seconds</option>
            <option value="minutes">Minutes</option>
            <option value="hours">Hours</option>
          </select>
        </label>
      </div>
      <label className="checkbox-field">
        <input type="checkbox" checked={form.enabled} onChange={(event) => update('enabled', event.target.checked)} />
        Enabled
      </label>
      {error !== undefined ? <p className="form-error" role="alert">{error}</p> : null}
      <div className="dialog-actions">
        <button className="secondary-button" type="button" onClick={onCancel}>Cancel</button>
        <button className="primary-button" type="submit" disabled={pending}>
          {pending ? 'Saving…' : 'Save'}
        </button>
      </div>
    </form>
  )
}

function stateFromRule(rule: MonitorRuleResponse): FormState | null {
  if (!isSupportedMetricCode(rule.metricCode) || !isSupportedOperator(rule.operator)) {
    return null
  }

  const duration = secondsToDurationInput(rule.forSeconds)
  const noData = secondsToDurationInput(rule.noDataSeconds)

  return {
    metricCode: rule.metricCode,
    operator: rule.operator,
    threshold: String(rule.threshold),
    durationValue: String(duration.value),
    durationUnit: duration.unit,
    noDataValue: String(noData.value),
    noDataUnit: noData.unit,
    enabled: rule.enabled,
  }
}
