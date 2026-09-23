package ru.bitec.app.ops
package application.monitor

import java.time.Instant
import java.util.UUID

/** Evaluates the monitor rules of everything one connection discovered, whatever the outcome of
  * the synchronization that triggered it.
  */
trait MonitorRuleEvaluator[F[_]] {
  def execute(organizationId: UUID, connectionId: UUID, evaluatedAt: Instant): F[List[MonitorTransition]]
}
