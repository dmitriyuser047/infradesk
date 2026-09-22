package ru.bitec.app.ops
package domain.monitor

import domain.metric.MetricCode

import java.time.Instant
import java.util.UUID

final case class MonitorRule(
                              id: UUID,
                              organizationId: UUID,
                              resourceId: UUID,
                              metricCode: MetricCode,
                              operator: MonitorOperator,
                              threshold: BigDecimal,
                              forSeconds: Long,
                              enabled: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant
                            )
