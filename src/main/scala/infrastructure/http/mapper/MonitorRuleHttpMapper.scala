package ru.bitec.app.ops
package infrastructure.http.mapper
import domain.monitor.MonitorRule
import infrastructure.http.dto.MonitorRuleResponse

object MonitorRuleHttpMapper {
  def toResponse(rule: MonitorRule): MonitorRuleResponse =
    MonitorRuleResponse(
      rule.id,
      rule.resourceId,
      rule.metricCode.code,
      rule.operator.code,
      rule.threshold,
      rule.forSeconds,
      rule.enabled,
      rule.createdAt,
      rule.updatedAt
    )
}
