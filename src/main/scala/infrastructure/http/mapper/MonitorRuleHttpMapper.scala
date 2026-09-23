package ru.bitec.app.ops
package infrastructure.http.mapper

import application.monitor.MonitorRuleView
import infrastructure.http.dto.MonitorRuleResponse

object MonitorRuleHttpMapper {
  def toResponse(view: MonitorRuleView): MonitorRuleResponse =
    MonitorRuleResponse(
      view.rule.id,
      view.rule.resourceId,
      view.rule.metricCode.code,
      view.rule.operator.code,
      view.rule.threshold,
      view.rule.forSeconds,
      view.rule.noDataSeconds,
      view.rule.enabled,
      view.status.map(_.code),
      view.rule.createdAt,
      view.rule.updatedAt
    )

  def toResponse(rule: domain.monitor.MonitorRule): MonitorRuleResponse =
    toResponse(MonitorRuleView(rule, None))
}
