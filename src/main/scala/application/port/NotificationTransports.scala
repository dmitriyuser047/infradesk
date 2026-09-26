package ru.bitec.app.ops
package application.port

import application.notification.{NotificationEvent, NotificationMessage}
import domain.notification.NotificationChannelSettings

/** The three ways this product reaches an external system.
  *
  * One typed port per transport rather than a registry of adapters: adding a fourth is a
  * deliberate change here, to the channel type it serves and to the sender that chooses between
  * them. Each takes the configuration and the credential of one channel and performs exactly one
  * attempt — no retrying, no scheduling, no persistence: that is the dispatcher's work.
  */
trait WebhookNotificationTransport[F[_]] {

  /** Posts the event to a URL a person configured, which is why the destination is checked
    * before the request rather than trusted because it parses.
    */
  def post(url: String, event: NotificationEvent): F[NotificationSendResult]
}

trait TelegramNotificationTransport[F[_]] {
  def send(botToken: String, chatId: String, message: NotificationMessage): F[NotificationSendResult]
}

trait EmailNotificationTransport[F[_]] {
  def send(
    settings: NotificationChannelSettings.Email,
    password: String,
    message: NotificationMessage
  ): F[NotificationSendResult]
}
