package ru.bitec.app.ops
package application.notification

import application.port.{
  EmailNotificationTransport,
  NotificationChannelCryptography,
  NotificationChannelDispatchQuery,
  NotificationChannelDispatchTarget,
  NotificationSendRequest,
  NotificationSendResult,
  NotificationSender,
  TelegramNotificationTransport,
  TransactionRunner,
  WebhookNotificationTransport
}
import cats.MonadThrow
import cats.syntax.all._
import domain.notification.{
  NotificationChannel,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationDeliveryTarget
}

/** Delivers one event to the channel it was addressed to.
  *
  * The delivery names a channel, not a configuration: what to send through, where to, and with
  * which credential is read here, at the moment of sending. A channel reconfigured after the
  * incident is therefore honoured — a rotated credential, a new chat, a different SMTP relay,
  * even a different channel type — and the committed intent to report the event survives all of
  * them. Whether the channel is still switched on is deliberately not consulted: that decides
  * which channels an event reaches, which was settled when the delivery was recorded.
  *
  * The transport is chosen from the channel's own settings, so the type carried by the delivery
  * is what the event was routed by, never what it is sent by.
  *
  * The read is one short transaction and the send happens after it: no network call is made
  * while a database transaction is open.
  */
final class ManagedNotificationSender[F[_]: MonadThrow, Tx[_]](
  channels: NotificationChannelDispatchQuery[Tx],
  cipher: NotificationChannelCryptography,
  runner: TransactionRunner[F, Tx],
  webhook: WebhookNotificationTransport[F],
  telegram: TelegramNotificationTransport[F],
  email: EmailNotificationTransport[F]
) extends NotificationSender[F] {

  import ManagedNotificationSender._

  override def send(request: NotificationSendRequest): F[NotificationSendResult] =
    request.target match {
      case NotificationDeliveryTarget.Managed(channelId, _) =>
        runner.run(channels.find(request.event.organizationId, channelId)).flatMap {
          case None => permanent(ChannelNotFound)
          case Some(target) => credentialOf(target) match {
            case Left(code) => permanent(code)
            case Right(credential) => deliver(request, target.channel, credential)
          }
        }
      // A dispatcher serves one scope, so this is a wiring mistake rather than a bad delivery:
      // repeating it cannot help.
      case NotificationDeliveryTarget.LegacyWebhook => permanent(UnsupportedTarget)
    }

  /** Reads the credential back as a typed value, and insists it is of the channel's own kind.
    *
    * A stored credential that cannot be decrypted, or that belongs to a channel type the channel
    * no longer is, is a configuration problem: something has to be re-entered, and no number of
    * attempts will change that.
    */
  private def credentialOf(
    target: NotificationChannelDispatchTarget
  ): Either[String, NotificationChannelCredential] =
    for {
      secret <- target.secret.toRight(SecretNotFound)
      // The message of the failure is not kept: it is about a secret.
      credential <- Either.catchNonFatal(cipher.decrypt(secret)).leftMap(_ => CredentialInvalid)
      _ <- Either.cond(credential.channelType == target.channel.channelType, (), CredentialInvalid)
    } yield credential

  private def deliver(
    request: NotificationSendRequest,
    channel: NotificationChannel,
    credential: NotificationChannelCredential
  ): F[NotificationSendResult] = (channel.settings, credential) match {
    case (NotificationChannelSettings.Webhook, NotificationChannelCredential.WebhookUrl(url)) =>
      webhook.post(url, request.event)

    case (NotificationChannelSettings.Telegram(chatId),
      NotificationChannelCredential.TelegramBotToken(token)) =>
      telegram.send(token, chatId, NotificationMessage.of(request.event))

    case (settings: NotificationChannelSettings.Email,
      NotificationChannelCredential.EmailPassword(password)) =>
      email.send(settings, password, NotificationMessage.of(request.event))

    // The credential and the settings agree on their channel type, so this is unreachable
    // unless one of them changes shape without the other.
    case _ => permanent(CredentialInvalid)
  }

  private def permanent(code: String): F[NotificationSendResult] =
    (NotificationSendResult.PermanentFailure(code): NotificationSendResult).pure[F]
}

object ManagedNotificationSender {

  /** Bounded, technical, and free of anything a person configured: these end up in a column
    * that is read back and shown.
    */
  val ChannelNotFound: String = "CHANNEL_NOT_FOUND"
  val SecretNotFound: String = "CHANNEL_SECRET_NOT_FOUND"
  val CredentialInvalid: String = "CHANNEL_CREDENTIAL_INVALID"
  val UnsupportedTarget: String = "DELIVERY_TARGET_UNSUPPORTED"
}
