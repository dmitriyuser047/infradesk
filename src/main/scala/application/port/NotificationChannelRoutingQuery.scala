package ru.bitec.app.ops
package application.port

import domain.incident.IncidentReason
import domain.notification.{NotificationChannelType, NotificationEventType}

import java.util.UUID

/** What an event has to say about itself in order to be routed: whose it is, what happened, and
  * why. Two transitions that agree on all three reach the same channels, so routing asks about
  * the distinct keys rather than about each transition.
  */
final case class NotificationRoutingKey(
  organizationId: UUID,
  eventType: NotificationEventType,
  reason: IncidentReason
)

/** One channel an event reaches, and nothing else about that channel.
  *
  * Routing decides where a notification is headed; it does not need to know how to get there, so
  * no address, no chat, no SMTP configuration and above all no credential is read here.
  */
final case class NotificationRoute(
  key: NotificationRoutingKey,
  channelId: UUID,
  channelType: NotificationChannelType
)

/** Selection, separate from the lifecycle repository that writes channels.
  *
  * The whole set of keys is answered in one statement: a hundred channels or a hundred
  * transitions cost the same number of queries as one.
  */
trait NotificationChannelRoutingQuery[F[_]] {
  def matching(keys: Set[NotificationRoutingKey]): F[List[NotificationRoute]]
}
