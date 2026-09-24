package ru.bitec.app.ops
package application.history

import application.monitor.{MonitorRuleEvaluator, MonitorTransition}
import cats.Monad
import cats.syntax.all._
import domain.history.HistoryEventType

import java.time.Instant
import java.util.UUID

/** Records the timeline entries of whatever the evaluation decided, in the same transaction.
  *
  * Monitoring stays unaware of the journal, exactly as it stays unaware of notifications: the
  * evaluator keeps returning transitions and the decorators turn them into durable facts. Both
  * writes happen inside the evaluation transaction, so an incident never exists without them.
  */
final class HistoryRecordingMonitorRuleEvaluator[Tx[_]: Monad](
  delegate: MonitorRuleEvaluator[Tx],
  recorder: HistoryRecorder[Tx]
) extends MonitorRuleEvaluator[Tx] {

  override def execute(
    organizationId: UUID,
    connectionId: UUID,
    evaluatedAt: Instant
  ): Tx[List[MonitorTransition]] =
    for {
      transitions <- delegate.execute(organizationId, connectionId, evaluatedAt)
      _ <- recorder.recordAll(transitions.map(entry))
    } yield transitions

  private def entry(transition: MonitorTransition): HistoryEntry =
    HistoryEntry
      .system(transition.organizationId, eventType(transition), transition.evaluatedAt)
      .copy(resourceId = Some(transition.resourceId), incidentId = Some(transition.incidentId))

  private def eventType(transition: MonitorTransition): HistoryEventType = transition match {
    case _: MonitorTransition.Opened => HistoryEventType.IncidentOpened
    case _: MonitorTransition.Resolved => HistoryEventType.IncidentResolved
  }
}
