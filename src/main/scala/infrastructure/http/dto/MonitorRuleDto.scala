package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class MonitorRuleResponse(
  id: UUID,
  resourceId: UUID,
  metricCode: String,
  operator: String,
  threshold: BigDecimal,
  forSeconds: Long,
  enabled: Boolean,
  createdAt: Instant,
  updatedAt: Instant
)

final case class CreateMonitorRuleRequest(
  metricCode: String,
  operator: String,
  threshold: BigDecimal,
  forSeconds: Long,
  enabled: Boolean
)

final case class UpdateMonitorRuleRequest(
  metricCode: String,
  operator: String,
  threshold: BigDecimal,
  forSeconds: Long,
  enabled: Boolean
)
