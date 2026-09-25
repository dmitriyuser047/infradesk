package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

/** The configuration a response may carry.
  *
  * `credentialConfigured` states that a credential exists; it is not a masked copy of one. The
  * webhook URL is a credential in this product, so it has no representation here at all, and the
  * Telegram chat is addressing rather than a secret, so it does.
  */
final case class NotificationChannelConfigResponse(
  credentialConfigured: Boolean,
  chatId: Option[String]
)

final case class NotificationChannelResponse(
  id: UUID,
  name: String,
  channelType: String,
  enabled: Boolean,
  events: List[String],
  reasons: List[String],
  config: NotificationChannelConfigResponse,
  createdAt: Instant,
  updatedAt: Instant
)

final case class TelegramChannelRequest(chatId: Option[String], botToken: Option[String])

final case class WebhookChannelRequest(url: Option[String])

/** One request shape for both channel types: the type decides which section is read, and a
  * section of the other type is simply not consulted.
  *
  * On an update an absent token or URL means "keep the stored credential"; it never means "clear
  * it", and there is no way to ask for the stored one back.
  */
final case class SaveNotificationChannelRequest(
  name: String,
  channelType: String,
  enabled: Boolean,
  events: List[String],
  reasons: List[String],
  telegram: Option[TelegramChannelRequest],
  webhook: Option[WebhookChannelRequest]
)
