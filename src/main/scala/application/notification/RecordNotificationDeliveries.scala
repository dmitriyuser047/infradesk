package ru.bitec.app.ops
package application.notification

import application.monitor.MonitorTransition
import application.port.{
  IdGenerator,
  NotificationChannelRoutingQuery,
  NotificationDeliveryRepository,
  NotificationRoutingKey,
  TimeProvider
}
import cats.MonadThrow
import cats.syntax.all._
import domain.notification.{
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationDeliveryTarget,
  NotificationEventType
}

/** Turns committed incident transitions into outbox rows, inside the very transaction that
  * produced them: an incident change and the intent to report it are written together or not at
  * all.
  *
  * Where an event goes is decided here and nowhere else. Monitoring keeps returning transitions
  * and knows nothing about channels; the channels of an organization are asked once, for every
  * distinct event the batch produced, and the answer becomes one durable delivery per
  * destination.
  *
  * Routing looks at the channels as they stand at this moment. A channel switched off or
  * reconfigured afterwards does not reach back into what was already recorded: a delivery is the
  * committed intent to report an event to a channel, and only its identity is kept, never its
  * configuration.
  *
  * The deployment's own webhook, if it has one, is a second kind of target and is recorded for
  * every transition regardless of subscriptions, exactly as it was before channels existed.
  */
final class RecordNotificationDeliveries[Tx[_]: MonadThrow](
  deliveries: NotificationDeliveryRepository[Tx],
  routing: NotificationChannelRoutingQuery[Tx],
  idGenerator: IdGenerator[Tx],
  timeProvider: TimeProvider[Tx],
  legacyTargets: List[NotificationDeliveryTarget]
) {

  def record(transitions: List[MonitorTransition]): Tx[Unit] =
    if (transitions.isEmpty) ().pure[Tx]
    else
      for {
        // One question for the whole batch: two transitions of one organization reporting the
        // same kind of event reach the same channels.
        routes <- routing.matching(transitions.map(keyOf).toSet)
        byKey = routes.groupBy(_.key)
        now <- timeProvider.now
        rows <- transitions.flatMap { transition =>
          val managed = byKey.getOrElse(keyOf(transition), Nil)
            .map(route => NotificationDeliveryTarget.Managed(route.channelId, route.channelType))
          (legacyTargets ++ managed).map(transition -> _)
        }.traverse { case (transition, target) =>
          idGenerator.nextId.map(id => NotificationDelivery(
            id = id,
            organizationId = transition.organizationId,
            incidentId = transition.incidentId,
            resourceId = transition.resourceId,
            monitorRuleId = transition.monitorRuleId,
            eventType = eventTypeOf(transition),
            reason = transition.reason,
            target = target,
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
        _ <- if (rows.isEmpty) ().pure[Tx] else deliveries.saveAll(rows)
      } yield ()

  private def keyOf(transition: MonitorTransition): NotificationRoutingKey =
    NotificationRoutingKey(transition.organizationId, eventTypeOf(transition), transition.reason)

  private def eventTypeOf(transition: MonitorTransition): NotificationEventType =
    transition match {
      case _: MonitorTransition.Opened => NotificationEventType.IncidentOpened
      case _: MonitorTransition.Resolved => NotificationEventType.IncidentResolved
    }
}
