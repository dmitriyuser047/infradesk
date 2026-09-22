import { Pencil } from 'lucide-react'

import type { MonitorRuleResponse } from '../../types/monitorRule'
import {
  formatRuleDuration,
  getMetricLabel,
  getOperatorSymbol,
  isSupportedMetricCode,
  isSupportedOperator,
} from './monitorRulePresentation'

interface MonitorRuleRowProps {
  rule: MonitorRuleResponse
  onEdit: (rule: MonitorRuleResponse) => void
}

export function MonitorRuleRow({ rule, onEdit }: MonitorRuleRowProps) {
  const editable = isSupportedMetricCode(rule.metricCode) && isSupportedOperator(rule.operator)

  return (
    <div className="monitor-rule-row">
      <div className="monitor-rule-condition">
        <strong>{getMetricLabel(rule.metricCode)}</strong>
        <span>{getOperatorSymbol(rule.operator)} {rule.threshold}% for {formatRuleDuration(rule.forSeconds)}</span>
      </div>
      <div className="monitor-rule-actions">
        <span className={`rule-state rule-state-${rule.enabled ? 'enabled' : 'disabled'}`}>
          {rule.enabled ? 'Enabled' : 'Disabled'}
        </span>
        <button
          className="text-button"
          type="button"
          onClick={() => onEdit(rule)}
          disabled={!editable}
          title={editable ? 'Edit rule' : 'This rule type is not supported for editing yet'}
        >
          <Pencil aria-hidden size={14} />
          {editable ? 'Edit' : 'Editing unavailable'}
        </button>
      </div>
    </div>
  )
}
