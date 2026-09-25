package ru.bitec.app.ops
package domain.notification

import domain.incident.IncidentReason

import java.time.Instant
import java.util.UUID

/** Which external system a notification is delivered to.
  *
  * Two typed kinds, not a plugin registry: adding a third one is a deliberate change to this
  * enumeration and to the sender that serves it.
  */
sealed trait NotificationChannelType {
  def code: String
}

object NotificationChannelType {

  case object Webhook extends NotificationChannelType {
    override val code: String = "WEBHOOK"
  }

  case object Telegram extends NotificationChannelType {
    override val code: String = "TELEGRAM"
  }

  val All: List[NotificationChannelType] = List(Webhook, Telegram)

  def fromCode(code: String): Either[IllegalArgumentException, NotificationChannelType] =
    All.find(_.code == code)
      .toRight(new IllegalArgumentException(s"Unsupported notification channel type '$code'"))
}

/** The part of a channel's configuration that is not a credential, and which therefore differs
  * per channel type.
  *
  * The type is carried by the settings themselves, so a channel cannot hold the configuration of
  * one kind while claiming to be another: the invalid combination has no representation.
  */
sealed trait NotificationChannelSettings {
  def channelType: NotificationChannelType
}

object NotificationChannelSettings {

  /** A webhook keeps nothing outside its URL, and the URL is a credential. */
  case object Webhook extends NotificationChannelSettings {
    override val channelType: NotificationChannelType = NotificationChannelType.Webhook
  }

  /** The chat is addressing, not a secret; the bot token that may post to it is. */
  final case class Telegram(chatId: String) extends NotificationChannelSettings {
    override val channelType: NotificationChannelType = NotificationChannelType.Telegram
  }
}

/** What a channel needs in order to speak to its external system, and nothing else.
  *
  * This is the only part of a channel that is encrypted at rest, and the only part no API
  * response ever carries.
  */
sealed trait NotificationChannelCredential {
  def channelType: NotificationChannelType
}

object NotificationChannelCredential {

  /** The endpoint may carry a token in its path or query, so it is treated as a secret. */
  final case class WebhookUrl(url: String) extends NotificationChannelCredential {
    override val channelType: NotificationChannelType = NotificationChannelType.Webhook
  }

  final case class TelegramBotToken(token: String) extends NotificationChannelCredential {
    override val channelType: NotificationChannelType = NotificationChannelType.Telegram
  }
}

/** Which events a channel asked for.
  *
  * An event type says what happened to an incident; a reason says why the incident exists. They
  * are separate dimensions, and a channel is only addressed when both match.
  */
final case class NotificationSubscriptions(
  eventTypes: Set[NotificationEventType],
  reasons: Set[IncidentReason]
) {
  def matches(eventType: NotificationEventType, reason: IncidentReason): Boolean =
    eventTypes.contains(eventType) && reasons.contains(reason)

  def isEmpty: Boolean = eventTypes.isEmpty || reasons.isEmpty

  /** Stable order, so a stored row and an API response do not depend on set iteration. */
  def orderedEventTypes: List[NotificationEventType] =
    NotificationEventType.All.filter(eventTypes.contains)

  def orderedReasons: List[IncidentReason] = IncidentReason.All.filter(reasons.contains)
}

/** One configured destination of an organization.
  *
  * The credential lives in its own encrypted row and is referenced by `secretId`: the channel
  * itself can be read, listed and audited without ever decrypting anything.
  */
final case class NotificationChannel(
  id: UUID,
  organizationId: UUID,
  name: String,
  enabled: Boolean,
  subscriptions: NotificationSubscriptions,
  settings: NotificationChannelSettings,
  secretId: UUID,
  createdAt: Instant,
  updatedAt: Instant
) {
  def channelType: NotificationChannelType = settings.channelType

  /** Whether this channel is addressed by an event, ignoring anything else about it. */
  def accepts(eventType: NotificationEventType, reason: IncidentReason): Boolean =
    enabled && subscriptions.matches(eventType, reason)
}
