package ru.bitec.app.ops
package application.port

import application.monitor.MonitorEvaluationInput

import java.time.Instant
import java.util.UUID

trait MonitorEvaluationQuery[F[_]] {

  /** One projection of every enabled monitor rule of the resources a connection discovered,
    * together with the latest observation, the current rule state, the open incident and whether
    * a maintenance window covers the resource at `at`.
    */
  def findEnabledForConnection(
    organizationId: UUID,
    connectionId: UUID,
    resourceTypeCodes: List[String],
    at: Instant
  ): F[List[MonitorEvaluationInput]]
}
