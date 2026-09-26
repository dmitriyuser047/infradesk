package ru.bitec.app.ops
package support

import application.notification.{NotificationEvent, NotificationMessage}
import application.port.{
  EmailNotificationTransport,
  NotificationSendResult,
  TelegramNotificationTransport,
  WebhookNotificationTransport
}
import bootstrap.NotificationTransports
import cats.effect.IO
import domain.notification.NotificationChannelSettings

/** Transports that record what they were asked to send and answer with a fixed result.
  *
  * A spec that is about the dispatcher, the channel resolution or the composition of the
  * application has no business opening a socket, so these stand in for the real ones.
  */
final class RecordingTransports(result: NotificationSendResult = NotificationSendResult.Sent) {

  private var webhookCalls: List[(String, NotificationEvent)] = Nil
  private var telegramCalls: List[(String, String, NotificationMessage)] = Nil
  private var emailCalls: List[(NotificationChannelSettings.Email, String, NotificationMessage)] = Nil

  def webhooks: List[(String, NotificationEvent)] = synchronized(webhookCalls)
  def telegrams: List[(String, String, NotificationMessage)] = synchronized(telegramCalls)
  def emails: List[(NotificationChannelSettings.Email, String, NotificationMessage)] =
    synchronized(emailCalls)

  val webhook: WebhookNotificationTransport[IO] = new WebhookNotificationTransport[IO] {
    override def post(url: String, event: NotificationEvent): IO[NotificationSendResult] =
      IO(synchronized { webhookCalls = webhookCalls :+ (url -> event) }).as(result)
  }

  val telegram: TelegramNotificationTransport[IO] = new TelegramNotificationTransport[IO] {
    override def send(
      botToken: String,
      chatId: String,
      message: NotificationMessage
    ): IO[NotificationSendResult] =
      IO(synchronized { telegramCalls = telegramCalls :+ ((botToken, chatId, message)) }).as(result)
  }

  val email: EmailNotificationTransport[IO] = new EmailNotificationTransport[IO] {
    override def send(
      settings: NotificationChannelSettings.Email,
      password: String,
      message: NotificationMessage
    ): IO[NotificationSendResult] =
      IO(synchronized { emailCalls = emailCalls :+ ((settings, password, message)) }).as(result)
  }

  /** As the application assembles them, with no webhook of the deployment's own. */
  def transports: NotificationTransports =
    NotificationTransports(legacyWebhookSender = None, webhook = webhook, telegram = telegram,
      email = email)
}
