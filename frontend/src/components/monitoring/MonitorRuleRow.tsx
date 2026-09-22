import { Pencil } from 'lucide-react'

import type { MonitorRuleResponse } from '../../types/monitorRule'
import { formatRuleDuration, getMetricLabel, getOperatorSymbol } from './monitorRulePresentation'

interface MonitorRuleRowProps {
  rule: MonitorRuleResponse
  onEdit: (rule: MonitorRuleResponse) => void
}

export function MonitorRuleRow({ rule, onEdit }: MonitorRuleRowProps) {
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
        <button className="text-button" type="button" onClick={() => onEdit(rule)}>
          <Pencil aria-hidden size={14} />
          Edit
        </button>
      </div>
    </div>
  )
}
