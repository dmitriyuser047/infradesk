package ru.bitec.app.ops
package infrastructure.http.mapper

import application.notification.{CreateNotificationChannelCommand, UpdateNotificationChannelCommand}
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
  CreateNotificationChannelRequest,
  NotificationChannelConfigResponse,
  NotificationChannelResponse,
  SaveNotificationChannelRequest,
  UpdateNotificationChannelRequest
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
  def toCreateCommand(
    request: CreateNotificationChannelRequest
  ): Either[IllegalArgumentException, CreateNotificationChannelCommand] =
    parse(request).map { case (settings, credential) =>
      CreateNotificationChannelCommand(
        name = request.name,
        // A channel that does not say otherwise is created switched on: it was configured to be
        // used, and switching it off is a decision of its own.
        enabled = request.enabled.getOrElse(true),
        subscriptions = subscriptions(request),
        settings = settings,
        credential = credential
      )
    }

  def toUpdateCommand(
    request: UpdateNotificationChannelRequest
  ): Either[IllegalArgumentException, UpdateNotificationChannelCommand] =
    parse(request).map { case (settings, credential) =>
      UpdateNotificationChannelCommand(
        name = request.name,
        subscriptions = subscriptions(request),
        settings = settings,
        credential = credential
      )
    }

  private def parse(
    request: SaveNotificationChannelRequest
  ): Either[IllegalArgumentException, (NotificationChannelSettings, Option[NotificationChannelCredential])] =
    for {
      channelType <- NotificationChannelType.fromCode(request.channelType)
      _ <- traverse(request.events)(NotificationEventType.fromCode)
      _ <- traverse(request.reasons)(IncidentReason.fromCode)
      _ <- requireOneSection(channelType, request)
      settings <- settingsOf(channelType, request)
    } yield (settings, credentialOf(channelType, request))

  /** A request describes one channel type, so it carries the configuration of that type only.
    * A section belonging to another type is a contradiction the caller has to resolve, not
    * something to drop on their behalf.
    */
  private def requireOneSection(
    channelType: NotificationChannelType,
    request: SaveNotificationChannelRequest
  ): Either[IllegalArgumentException, Unit] = {
    val foreign = channelType match {
      case NotificationChannelType.Webhook => request.telegram.isDefined
      case NotificationChannelType.Telegram => request.webhook.isDefined
    }
    Either.cond(!foreign, (), new IllegalArgumentException(
      s"A ${channelType.code} channel carries no configuration of another channel type"))
  }

  /** Both lists are already known to parse; this is where they become the domain value. */
  private def subscriptions(request: SaveNotificationChannelRequest): NotificationSubscriptions =
    NotificationSubscriptions(
      request.events.flatMap(NotificationEventType.fromCode(_).toOption).toSet,
      request.reasons.flatMap(IncidentReason.fromCode(_).toOption).toSet
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
