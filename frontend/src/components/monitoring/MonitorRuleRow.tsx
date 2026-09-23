import { Pencil } from 'lucide-react'

import type { MonitorRuleResponse } from '../../types/monitorRule'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { formatNoDataTimeout, formatRuleDuration, getMetricLabel, getMonitorRuleStatusPresentation,
  getOperatorSymbol, isSupportedMetricCode, isSupportedOperator } from './monitorRulePresentation'

export function MonitorRuleRow({ rule, onEdit }: {
  rule: MonitorRuleResponse
  onEdit: (rule: MonitorRuleResponse) => void
}) {
  const editable = isSupportedMetricCode(rule.metricCode) && isSupportedOperator(rule.operator)
  const state = getMonitorRuleStatusPresentation(rule.status, rule.enabled)
  return <tr>
    <td>{getMetricLabel(rule.metricCode)}</td>
    <td>{getOperatorSymbol(rule.operator)}</td>
    <td>{rule.threshold}%</td>
    <td>{formatRuleDuration(rule.forSeconds)} · no data {formatNoDataTimeout(rule.noDataSeconds)}</td>
    <td><StatusIndicator label={state.label} tone={state.tone} /></td>
    <td><button className="text-button" type="button" onClick={() => onEdit(rule)} disabled={!editable}
      title={editable ? 'Edit rule' : 'This rule type is not supported for editing yet'}>
      <Pencil aria-hidden size={14} />{editable ? 'Edit' : 'Unavailable'}
    </button></td>
  </tr>
}
