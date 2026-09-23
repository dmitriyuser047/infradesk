import { Pencil } from 'lucide-react'

import type { MonitorRuleResponse } from '../../types/monitorRule'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { formatRuleDuration, getMetricLabel, getOperatorSymbol, isSupportedMetricCode,
  isSupportedOperator } from './monitorRulePresentation'

export function MonitorRuleRow({ rule, onEdit }: {
  rule: MonitorRuleResponse
  onEdit: (rule: MonitorRuleResponse) => void
}) {
  const editable = isSupportedMetricCode(rule.metricCode) && isSupportedOperator(rule.operator)
  return <tr>
    <td>{getMetricLabel(rule.metricCode)}</td>
    <td>{getOperatorSymbol(rule.operator)}</td>
    <td>{rule.threshold}%</td>
    <td>{formatRuleDuration(rule.forSeconds)}</td>
    <td><StatusIndicator label={rule.enabled ? 'Enabled' : 'Disabled'} tone={rule.enabled ? 'success' : 'neutral'} /></td>
    <td><button className="text-button" type="button" onClick={() => onEdit(rule)} disabled={!editable}
      title={editable ? 'Edit rule' : 'This rule type is not supported for editing yet'}>
      <Pencil aria-hidden size={14} />{editable ? 'Edit' : 'Unavailable'}
    </button></td>
  </tr>
}
