package ru.bitec.app.ops
package application.port

import application.monitor.MonitorEvaluationInput

import java.util.UUID

trait MonitorEvaluationQuery[F[_]] {

  /** One projection of every enabled monitor rule of the resources a connection discovered,
    * together with the latest observation, the current rule state and the open incident.
    */
  def findEnabledForConnection(
    organizationId: UUID,
    connectionId: UUID,
    resourceTypeCodes: List[String]
  ): F[List[MonitorEvaluationInput]]
}
