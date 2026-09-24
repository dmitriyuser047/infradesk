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
                              noDataSeconds: Long,
                              enabled: Boolean,
                              createdAt: Instant,
                              updatedAt: Instant
                            )

object MonitorRule {

  /** Whether the change makes the runtime state of the previous evaluation meaningless.
    *
    * Everything the state machine reads has to stay the same for a state to survive an update:
    * the condition itself, both durations and whether the rule is evaluated at all. Disabling a
    * rule stops evaluation, so its state and open incident have to be cleaned up; enabling one
    * again starts from scratch instead of resuming a window nobody observed.
    */
  def invalidatesEvaluation(previous: MonitorRule, updated: MonitorRule): Boolean =
    previous.metricCode != updated.metricCode ||
      previous.operator != updated.operator ||
      previous.threshold.compare(updated.threshold) != 0 ||
      previous.forSeconds != updated.forSeconds ||
      previous.noDataSeconds != updated.noDataSeconds ||
      previous.enabled != updated.enabled
}
