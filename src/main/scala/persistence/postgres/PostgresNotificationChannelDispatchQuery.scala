package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  NotificationChannelDispatchQuery,
  NotificationChannelDispatchTarget,
  NotificationChannelSecret
}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

import PostgresNotificationChannelRepository.ChannelRow

/** The channel a delivery is addressed to, with its credential, in one statement.
  *
  * The tenant is part of the predicate rather than checked afterwards, so a delivery of one
  * organization cannot reach the channel — or the credential — of another even with a guessed
  * identifier. The credential is joined on the same pair, which is what makes a secret of
  * another tenant unreachable rather than merely unlikely.
  *
  * The join is left outer so that a channel whose credential row went missing is told apart
  * from a channel that is not there at all: both are configuration problems, but not the same
  * one.
  */
final class PostgresNotificationChannelDispatchQuery
  extends NotificationChannelDispatchQuery[ConnectionIO] {

  override def find(
    organizationId: UUID,
    channelId: UUID
  ): ConnectionIO[Option[NotificationChannelDispatchTarget]] =
    sql"""
      select c.id, c.organization_id, c.name, c.channel_type, c.enabled,
             c.subscribed_event_types, c.subscribed_reasons, c.telegram_chat_id,
             c.smtp_host, c.smtp_port, c.smtp_security, c.smtp_username, c.smtp_from_address,
             c.smtp_recipients, c.secret_id, c.created_at, c.updated_at,
             s.id, s.kind, s.nonce, s.ciphertext
        from notification_channel c
        left join notification_channel_secret s
          on s.id = c.secret_id
         and s.organization_id = c.organization_id
       where c.organization_id = $organizationId
         and c.id = $channelId
    """
      .query[(ChannelRow, Option[UUID], Option[String], Option[Array[Byte]], Option[Array[Byte]])]
      .option
      .flatMap {
        case None => none[NotificationChannelDispatchTarget].pure[ConnectionIO]
        case Some((row, secretId, kind, nonce, ciphertext)) =>
          row.toDomain.map { channel =>
            val secret = (secretId, kind, nonce, ciphertext).mapN(
              (id, secretKind, secretNonce, secretCiphertext) =>
                NotificationChannelSecret(id, organizationId, secretKind, secretNonce,
                  secretCiphertext))
            Option(NotificationChannelDispatchTarget(channel, secret))
          }.liftTo[ConnectionIO]
      }
}
