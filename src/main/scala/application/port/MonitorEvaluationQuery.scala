package ru.bitec.app.ops
package application.port

import application.monitor.MonitorEvaluationInput

import java.util.UUID

trait MonitorEvaluationQuery[F[_]] {
  def findEnabledForResources(
    organizationId: UUID,
    resourceIds: List[UUID]
  ): F[List[MonitorEvaluationInput]]
}
