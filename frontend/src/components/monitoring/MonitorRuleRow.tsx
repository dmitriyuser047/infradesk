import { Pencil } from 'lucide-react'

import { useI18n } from '../../i18n'
import type { MonitorRuleResponse } from '../../types/monitorRule'
import { StatusIndicator } from '../layout/WorkspacePrimitives'
import { formatNoDataTimeout, formatRuleDuration, getMetricLabel, getMonitorRuleStatusPresentation,
  getOperatorSymbol, isSupportedMetricCode, isSupportedOperator } from './monitorRulePresentation'

export function MonitorRuleRow({ rule, onEdit }: {
  rule: MonitorRuleResponse
  onEdit?: (rule: MonitorRuleResponse) => void
}) {
  const i18n = useI18n()
  const t = i18n.t.monitoring
  const editable = isSupportedMetricCode(rule.metricCode) && isSupportedOperator(rule.operator)
  const state = getMonitorRuleStatusPresentation(rule.status, rule.enabled, i18n)
  return <tr>
    <td>{getMetricLabel(rule.metricCode, i18n)}</td>
    <td>{getOperatorSymbol(rule.operator)}</td>
    <td>{rule.threshold}%</td>
    <td>{t.durationRow(formatRuleDuration(rule.forSeconds, i18n), formatNoDataTimeout(rule.noDataSeconds, i18n))}</td>
    <td><StatusIndicator label={state.label} tone={state.tone} /></td>
    {onEdit ? <td><button className="text-button" type="button" onClick={() => onEdit(rule)} disabled={!editable}
      title={editable ? t.editRule : t.unsupported}>
      <Pencil aria-hidden size={14} />{editable ? i18n.t.common.edit : t.unavailable}
    </button></td> : null}
  </tr>
}
