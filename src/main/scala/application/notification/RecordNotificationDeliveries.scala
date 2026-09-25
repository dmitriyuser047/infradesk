package ru.bitec.app.ops
package application.notification

import application.monitor.MonitorTransition
import application.port.{IdGenerator, NotificationDeliveryRepository, TimeProvider}
import cats.MonadThrow
import cats.syntax.all._
import domain.notification.{
  NotificationChannelType,
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationEventType
}

/** Turns committed incident transitions into outbox rows, inside the very transaction that
  * produced them: an incident change and the intent to report it are written together or not at
  * all.
  *
  * With no channel configured this records nothing, so a deployment without a webhook does not
  * accumulate deliveries nobody will ever send.
  */
final class RecordNotificationDeliveries[Tx[_]: MonadThrow](
  deliveries: NotificationDeliveryRepository[Tx],
  idGenerator: IdGenerator[Tx],
  timeProvider: TimeProvider[Tx],
  channelTypes: List[NotificationChannelType]
) {

  def record(transitions: List[MonitorTransition]): Tx[Unit] =
    if (channelTypes.isEmpty || transitions.isEmpty) ().pure[Tx]
    else
      for {
        now <- timeProvider.now
        rows <- transitions.flatMap(transition => channelTypes.map(transition -> _))
          .traverse { case (transition, channelType) =>
            idGenerator.nextId.map(id => NotificationDelivery(
              id = id,
              organizationId = transition.organizationId,
              incidentId = transition.incidentId,
              resourceId = transition.resourceId,
              monitorRuleId = transition.monitorRuleId,
              eventType = eventTypeOf(transition),
              reason = transition.reason,
              channelType = channelType,
              occurredAt = transition.evaluatedAt,
              status = NotificationDeliveryStatus.Pending,
              attemptCount = 0,
              // Due immediately; the dispatcher picks it up on its next poll.
              nextAttemptAt = now,
              claimedBy = None,
              claimedUntil = None,
              sentAt = None,
              lastErrorCode = None,
              createdAt = now,
              updatedAt = now
            ))
          }
        _ <- deliveries.saveAll(rows)
      } yield ()

  private def eventTypeOf(transition: MonitorTransition): NotificationEventType =
    transition match {
      case _: MonitorTransition.Opened => NotificationEventType.IncidentOpened
      case _: MonitorTransition.Resolved => NotificationEventType.IncidentResolved
    }
}
