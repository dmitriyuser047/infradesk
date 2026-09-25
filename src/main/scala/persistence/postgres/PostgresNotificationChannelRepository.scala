package ru.bitec.app.ops
package persistence.postgres

import application.port.{
  NotificationChannelRepository,
  NotificationChannelSecret,
  NotificationChannelSecretRepository
}
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannel,
  NotificationChannelSettings,
  NotificationChannelType,
  NotificationEventType,
  NotificationSubscriptions
}
import org.typelevel.doobie.{ConnectionIO, Fragment, Query0}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

import PostgresNotificationChannelRepository.ChannelRow

final class PostgresNotificationChannelRepository extends NotificationChannelRepository[ConnectionIO] {

  private val columns =
    fr"""
      id, organization_id, name, channel_type, enabled,
      subscribed_event_types, subscribed_reasons, telegram_chat_id,
      secret_id, created_at, updated_at
    """

  override def listByOrganization(organizationId: UUID): ConnectionIO[List[NotificationChannel]] =
    query(fr"where organization_id = $organizationId order by name, id")
      .to[List].flatMap(_.traverse(_.toDomain.liftTo[ConnectionIO]))

  override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[NotificationChannel]] =
    single(fr"where organization_id = $organizationId and id = $id")

  override def findByIdForUpdate(
    organizationId: UUID,
    id: UUID
  ): ConnectionIO[Option[NotificationChannel]] =
    single(fr"where organization_id = $organizationId and id = $id for update")

  /** Insert or replace by id. The tenant is part of the predicate on update, so a row of another
    * organization can never be reached even with a guessed identifier.
    */
  override def save(channel: NotificationChannel): ConnectionIO[Unit] = {
    val eventTypes = channel.subscriptions.orderedEventTypes.map(_.code)
    val reasons = channel.subscriptions.orderedReasons.map(_.code)
    val chatId = channel.settings match {
      case NotificationChannelSettings.Telegram(value) => Some(value)
      case NotificationChannelSettings.Webhook => None
    }

    sql"""
      insert into notification_channel (
        id, organization_id, name, channel_type, enabled,
        subscribed_event_types, subscribed_reasons, telegram_chat_id,
        secret_id, created_at, updated_at
      ) values (
        ${channel.id}, ${channel.organizationId}, ${channel.name}, ${channel.channelType.code},
        ${channel.enabled}, $eventTypes, $reasons, $chatId,
        ${channel.secretId}, ${channel.createdAt}, ${channel.updatedAt}
      )
      on conflict (id) do update set
        name = excluded.name,
        channel_type = excluded.channel_type,
        enabled = excluded.enabled,
        subscribed_event_types = excluded.subscribed_event_types,
        subscribed_reasons = excluded.subscribed_reasons,
        telegram_chat_id = excluded.telegram_chat_id,
        secret_id = excluded.secret_id,
        updated_at = excluded.updated_at
      where notification_channel.organization_id = ${channel.organizationId}
    """.update.run.flatMap {
      case 1 => ().pure[ConnectionIO]
      // The predicate held back the update: the identifier belongs to another tenant.
      case _ => new IllegalStateException(
        s"Notification channel ${channel.id} was not written"
      ).raiseError[ConnectionIO, Unit]
    }
  }

  private def single(where: Fragment): ConnectionIO[Option[NotificationChannel]] =
    query(where).option.flatMap {
      case None => Option.empty[NotificationChannel].pure[ConnectionIO]
      case Some(row) => row.toDomain.map(Option(_)).liftTo[ConnectionIO]
    }

  private def query(where: Fragment): Query0[ChannelRow] =
    (fr"select" ++ columns ++ fr"from notification_channel" ++ where).query[ChannelRow]
}

object PostgresNotificationChannelRepository {

  private[postgres] final case class ChannelRow(
    id: UUID,
    organizationId: UUID,
    name: String,
    channelType: String,
    enabled: Boolean,
    subscribedEventTypes: List[String],
    subscribedReasons: List[String],
    telegramChatId: Option[String],
    secretId: UUID,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: Either[IllegalArgumentException, NotificationChannel] =
      for {
        typedChannelType <- NotificationChannelType.fromCode(channelType)
        eventTypes <- subscribedEventTypes.traverse(NotificationEventType.fromCode)
        reasons <- subscribedReasons.traverse(IncidentReason.fromCode)
        settings <- settingsOf(typedChannelType)
      } yield NotificationChannel(
        id, organizationId, name, enabled,
        NotificationSubscriptions(eventTypes.toSet, reasons.toSet), settings, secretId,
        createdAt, updatedAt
      )

    /** The database constraint already pairs the public configuration with the channel type; a
      * row that somehow escaped it is a defect, not a value to guess at.
      */
    private def settingsOf(
      value: NotificationChannelType
    ): Either[IllegalArgumentException, NotificationChannelSettings] = value match {
      case NotificationChannelType.Webhook => Right(NotificationChannelSettings.Webhook)
      case NotificationChannelType.Telegram => telegramChatId
        .map(NotificationChannelSettings.Telegram)
        .toRight(new IllegalArgumentException("Telegram notification channel has no chat"))
    }
  }
}

final class PostgresNotificationChannelSecretRepository
  extends NotificationChannelSecretRepository[ConnectionIO] {

  override def save(secret: NotificationChannelSecret): ConnectionIO[Unit] =
    sql"""insert into notification_channel_secret
            (id, organization_id, kind, nonce, ciphertext, created_at)
          values (${secret.id}, ${secret.organizationId}, ${secret.kind}, ${secret.nonce},
                  ${secret.ciphertext}, current_timestamp)""".update.run.void

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[NotificationChannelSecret]] =
    sql"""select id, organization_id, kind, nonce, ciphertext from notification_channel_secret
           where organization_id = $organizationId and id = $id"""
      .query[(UUID, UUID, String, Array[Byte], Array[Byte])]
      .option.map(_.map { case (secretId, orgId, kind, nonce, ciphertext) =>
        NotificationChannelSecret(secretId, orgId, kind, nonce, ciphertext)
      })

  override def delete(organizationId: UUID, id: UUID): ConnectionIO[Unit] =
    sql"delete from notification_channel_secret where organization_id = $organizationId and id = $id"
      .update.run.void
}
