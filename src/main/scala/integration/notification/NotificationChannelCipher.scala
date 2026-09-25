package ru.bitec.app.ops
package integration.notification

import application.port.{NotificationChannelCryptography, NotificationChannelSecret}
import domain.notification.{NotificationChannelCredential, NotificationChannelType}
import integration.secret.AesGcmSecretEnvelope
import integration.ssh.SecretEncryptionConfig
import io.circe.{Json, parser}

import java.util.UUID

/** Encrypts a channel credential, whichever kind it is.
  *
  * The encoded payload carries its own type, so a webhook URL cannot be read back as a Telegram
  * token or an SMTP password: a channel whose type was changed without a new credential is
  * rejected before it is stored, and a stored credential of the wrong type fails loudly rather
  * than being sent somewhere it does not belong.
  *
  * The kind on the row separates a channel credential from every other kind of secret the
  * product keeps, an SSH credential among them; the discriminator inside the authenticated
  * payload separates the channel credentials from each other. Because the kind is part of the
  * additional authenticated data, giving each channel type its own kind would mean re-encrypting
  * the rows that already exist, which buys nothing: the payload is authenticated too.
  */
final class NotificationChannelCipher private (envelope: AesGcmSecretEnvelope)
  extends NotificationChannelCryptography {

  import NotificationChannelCipher._

  override def encrypt(
    id: UUID,
    organizationId: UUID,
    credential: NotificationChannelCredential
  ): NotificationChannelSecret = {
    val (nonce, ciphertext) = envelope.seal(id, organizationId, CredentialKind, encode(credential))
    NotificationChannelSecret(id, organizationId, CredentialKind, nonce, ciphertext)
  }

  override def decrypt(secret: NotificationChannelSecret): NotificationChannelCredential = {
    if (secret.kind != CredentialKind)
      throw new IllegalArgumentException(s"Unsupported secret kind '${secret.kind}'")
    decode(envelope.open(secret.id, secret.organizationId, secret.kind, secret.nonce, secret.ciphertext))
  }

  private def encode(credential: NotificationChannelCredential): String = credential match {
    case NotificationChannelCredential.WebhookUrl(url) =>
      Json.obj(
        "type" -> Json.fromString(NotificationChannelType.Webhook.code),
        "url" -> Json.fromString(url)
      ).noSpaces
    case NotificationChannelCredential.TelegramBotToken(token) =>
      Json.obj(
        "type" -> Json.fromString(NotificationChannelType.Telegram.code),
        "botToken" -> Json.fromString(token)
      ).noSpaces
    case NotificationChannelCredential.EmailPassword(password) =>
      Json.obj(
        "type" -> Json.fromString(NotificationChannelType.Email.code),
        "smtpPassword" -> Json.fromString(password)
      ).noSpaces
  }

  private def decode(payload: String): NotificationChannelCredential = {
    val cursor = parser.parse(payload).getOrElse(throw unreadable).hcursor
    val channelType = cursor.get[String]("type").toOption
      .flatMap(NotificationChannelType.fromCode(_).toOption)

    channelType match {
      case Some(NotificationChannelType.Webhook) =>
        NotificationChannelCredential.WebhookUrl(required(cursor.get[String]("url").toOption))
      case Some(NotificationChannelType.Telegram) =>
        NotificationChannelCredential.TelegramBotToken(required(cursor.get[String]("botToken").toOption))
      case Some(NotificationChannelType.Email) =>
        NotificationChannelCredential.EmailPassword(required(cursor.get[String]("smtpPassword").toOption))
      case None => throw unreadable
    }
  }

  private def required(value: Option[String]): String = value.getOrElse(throw unreadable)

  // The message never quotes the payload: it is the secret.
  private def unreadable = new IllegalArgumentException("Stored notification credential is not readable")
}

object NotificationChannelCipher {

  val CredentialKind: String = "NOTIFICATION_CHANNEL_CREDENTIAL"

  def fromConfig(config: SecretEncryptionConfig): NotificationChannelCipher =
    new NotificationChannelCipher(new AesGcmSecretEnvelope(config.keyBytes))
}
