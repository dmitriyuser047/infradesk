package ru.bitec.app.ops
package infrastructure.http.mapper

import application.notification.NotificationChannelCommand
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannel,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationChannelType,
  NotificationEventType,
  NotificationSubscriptions
}
import infrastructure.http.dto.{
  NotificationChannelConfigResponse,
  NotificationChannelResponse,
  SaveNotificationChannelRequest
}

/** Between the wire and the domain, in both directions.
  *
  * The outbound direction is where the secret rule is enforced structurally: a response is built
  * from the channel alone, which never holds a credential, so there is no code path that could
  * put one into a body.
  */
object NotificationChannelHttpMapper {

  def toResponse(channel: NotificationChannel): NotificationChannelResponse =
    NotificationChannelResponse(
      id = channel.id,
      name = channel.name,
      channelType = channel.channelType.code,
      enabled = channel.enabled,
      events = channel.subscriptions.orderedEventTypes.map(_.code),
      reasons = channel.subscriptions.orderedReasons.map(_.code),
      config = NotificationChannelConfigResponse(
        // A stored channel always has a credential: it cannot be created without one.
        credentialConfigured = true,
        chatId = channel.settings match {
          case NotificationChannelSettings.Telegram(chatId) => Some(chatId)
          case NotificationChannelSettings.Webhook => None
        }
      ),
      createdAt = channel.createdAt,
      updatedAt = channel.updatedAt
    )

  /** Rejects anything the domain does not recognise before it reaches a use case: unknown codes,
    * and a configuration that belongs to another channel type.
    */
  def toCommand(
    request: SaveNotificationChannelRequest
  ): Either[IllegalArgumentException, NotificationChannelCommand] =
    for {
      channelType <- NotificationChannelType.fromCode(request.channelType)
      events <- traverse(request.events)(NotificationEventType.fromCode)
      reasons <- traverse(request.reasons)(IncidentReason.fromCode)
      settings <- settingsOf(channelType, request)
      credential = credentialOf(channelType, request)
    } yield NotificationChannelCommand(
      name = request.name,
      enabled = request.enabled,
      subscriptions = NotificationSubscriptions(events.toSet, reasons.toSet),
      settings = settings,
      credential = credential
    )

  private def settingsOf(
    channelType: NotificationChannelType,
    request: SaveNotificationChannelRequest
  ): Either[IllegalArgumentException, NotificationChannelSettings] = channelType match {
    case NotificationChannelType.Webhook => Right(NotificationChannelSettings.Webhook)
    case NotificationChannelType.Telegram =>
      request.telegram.flatMap(_.chatId).map(_.trim).filter(_.nonEmpty)
        .map(NotificationChannelSettings.Telegram)
        .toRight(new IllegalArgumentException("Telegram channel requires a chat"))
  }

  /** An absent secret is a valid request on update; the use case decides whether it is allowed. */
  private def credentialOf(
    channelType: NotificationChannelType,
    request: SaveNotificationChannelRequest
  ): Option[NotificationChannelCredential] = channelType match {
    case NotificationChannelType.Webhook =>
      request.webhook.flatMap(_.url).map(_.trim).filter(_.nonEmpty)
        .map(NotificationChannelCredential.WebhookUrl)
    case NotificationChannelType.Telegram =>
      request.telegram.flatMap(_.botToken).map(_.trim).filter(_.nonEmpty)
        .map(NotificationChannelCredential.TelegramBotToken)
  }

  private def traverse[A, B](
    values: List[A]
  )(parse: A => Either[IllegalArgumentException, B]): Either[IllegalArgumentException, List[B]] =
    values.foldRight[Either[IllegalArgumentException, List[B]]](Right(Nil)) { (value, accumulated) =>
      for { rest <- accumulated; parsed <- parse(value) } yield parsed :: rest
    }
}
