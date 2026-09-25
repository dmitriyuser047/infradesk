package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  NotificationChannelRoutingQuery,
  NotificationRoute,
  NotificationRoutingKey
}
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{NotificationChannelType, NotificationEventType}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

/** Which enabled channels an event reaches, in one statement for every key asked about.
  *
  * The keys are joined against the channels as a table rather than looked up one at a time, so
  * the cost of routing follows neither the number of transitions nor the number of channels: a
  * single incident reaching one channel and a hundred incidents reaching a hundred channels are
  * both one query.
  *
  * The subscription arrays are read in place. Nothing outside the channel row is touched: no
  * secret is selected and nothing is decrypted, because where an event goes does not depend on
  * how to get there.
  */
final class PostgresNotificationChannelRoutingQuery
  extends NotificationChannelRoutingQuery[ConnectionIO] {

  override def matching(keys: Set[NotificationRoutingKey]): ConnectionIO[List[NotificationRoute]] =
    if (keys.isEmpty) List.empty[NotificationRoute].pure[ConnectionIO]
    else {
      val requested = keys.toList.map(key =>
        fr"(${key.organizationId}, ${key.eventType.code}, ${key.reason.code})")

      (fr"""
        with requested (organization_id, event_type, reason) as (values""" ++
        requested.intercalate(fr",") ++
        fr"""
        )
        select r.organization_id, r.event_type, r.reason, c.id, c.channel_type
        from requested r
        join notification_channel c
          on c.organization_id = r.organization_id
         and r.event_type = any (c.subscribed_event_types)
         and r.reason = any (c.subscribed_reasons)
        where c.enabled
      """).query[(UUID, String, String, UUID, String)].to[List].flatMap(_.traverse {
        case (organizationId, eventType, reason, channelId, channelType) =>
          (for {
            typedEventType <- NotificationEventType.fromCode(eventType)
            typedReason <- IncidentReason.fromCode(reason)
            typedChannelType <- NotificationChannelType.fromCode(channelType)
          } yield NotificationRoute(
            NotificationRoutingKey(organizationId, typedEventType, typedReason),
            channelId,
            typedChannelType
          )).liftTo[ConnectionIO]
      })
    }
}
