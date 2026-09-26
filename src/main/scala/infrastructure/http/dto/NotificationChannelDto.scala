package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

/** The configuration a response may carry, which differs per channel type exactly as the
  * settings do.
  *
  * `credentialConfigured` states that a credential exists; it is never a masked copy of one. A
  * webhook URL and an SMTP password are credentials in this product, so neither has a
  * representation here at all; a Telegram chat, an SMTP host and an SMTP user name are
  * addressing rather than secrets, so they do.
  */
sealed trait NotificationChannelConfigResponse {
  def credentialConfigured: Boolean
}

object NotificationChannelConfigResponse {

  final case class Webhook(credentialConfigured: Boolean) extends NotificationChannelConfigResponse

  final case class Telegram(
    credentialConfigured: Boolean,
    chatId: String
  ) extends NotificationChannelConfigResponse

  final case class Email(
    credentialConfigured: Boolean,
    smtpHost: String,
    smtpPort: Int,
    security: String,
    username: String,
    fromAddress: String,
    recipients: List[String]
  ) extends NotificationChannelConfigResponse
}

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

/** The SMTP section of a request. An absent password on an update means "keep the stored one";
  * on a create it means the request is incomplete, which the use case says so.
  */
final case class EmailChannelRequest(
  smtpHost: Option[String],
  smtpPort: Option[Int],
  security: Option[String],
  username: Option[String],
  password: Option[String],
  fromAddress: Option[String],
  recipients: Option[List[String]]
)

/** What both write requests have in common.
  *
  * A request carries the configuration of the type it declares and of no other: sending a
  * Telegram section on a webhook channel is a contradiction, not a section to ignore.
  */
sealed trait SaveNotificationChannelRequest {
  def name: String
  def channelType: String
  def events: List[String]
  def reasons: List[String]
  def telegram: Option[TelegramChannelRequest]
  def webhook: Option[WebhookChannelRequest]
  def email: Option[EmailChannelRequest]
}

final case class CreateNotificationChannelRequest(
  name: String,
  channelType: String,
  enabled: Option[Boolean],
  events: List[String],
  reasons: List[String],
  telegram: Option[TelegramChannelRequest],
  webhook: Option[WebhookChannelRequest],
  email: Option[EmailChannelRequest]
) extends SaveNotificationChannelRequest

/** An update carries no `enabled`: a channel is switched on and off through its own operations,
  * which are journalled as what they are. A body that asks for the lifecycle here is rejected
  * rather than quietly dropped.
  *
  * An absent token or URL means "keep the stored credential"; it never means "clear it", and
  * there is no way to ask for the stored one back.
  */
final case class UpdateNotificationChannelRequest(
  name: String,
  channelType: String,
  events: List[String],
  reasons: List[String],
  telegram: Option[TelegramChannelRequest],
  webhook: Option[WebhookChannelRequest],
  email: Option[EmailChannelRequest]
) extends SaveNotificationChannelRequest

/** What a test send answers with: whether it went out, and a bounded technical code when it did
  * not. Never anything the channel's own configuration or the receiver's own answer said: no
  * webhook URL, no bot token, no SMTP password, no raw provider response.
  */
final case class TestNotificationChannelResponse(status: String, code: Option[String])
