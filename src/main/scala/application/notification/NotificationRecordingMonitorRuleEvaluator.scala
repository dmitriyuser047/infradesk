package ru.bitec.app.ops
package application.notification

import application.monitor.{MonitorRuleEvaluator, MonitorTransition}
import cats.Monad
import cats.syntax.all._

import java.time.Instant
import java.util.UUID

/** Records the outbox rows for whatever the evaluation decided, in the same transaction.
  *
  * Monitoring itself stays unaware of notifications: the evaluator keeps returning transitions
  * and this decorator is what turns them into durable delivery intent.
  */
final class NotificationRecordingMonitorRuleEvaluator[Tx[_]: Monad](
  delegate: MonitorRuleEvaluator[Tx],
  recorder: RecordNotificationDeliveries[Tx]
) extends MonitorRuleEvaluator[Tx] {

  override def execute(
    organizationId: UUID,
    connectionId: UUID,
    evaluatedAt: Instant
  ): Tx[List[MonitorTransition]] =
    for {
      transitions <- delegate.execute(organizationId, connectionId, evaluatedAt)
      _ <- recorder.record(transitions)
    } yield transitions
}
