package ru.bitec.app.ops
package application.notification

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{
  IdGenerator,
  NotificationChannelCryptography,
  NotificationChannelRepository,
  NotificationChannelSecretRepository,
  TimeProvider
}
import cats.MonadThrow
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.notification.{
  NotificationChannel,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationSubscriptions
}

import java.util.UUID

final case class NotificationChannelError(code: String, override val getMessage: String)
  extends RuntimeException(getMessage)

/** What a caller asks for when creating or changing a channel.
  *
  * The settings carry the channel type, so there is no way to describe a Telegram channel with a
  * webhook configuration. The credential is optional on update only: absent means "keep the one
  * that is stored".
  */
final case class NotificationChannelCommand(
  name: String,
  enabled: Boolean,
  subscriptions: NotificationSubscriptions,
  settings: NotificationChannelSettings,
  credential: Option[NotificationChannelCredential]
)

/** Creating, changing and switching off notification channels.
  *
  * Everything here is one database transaction: validation, the credential, the channel row and
  * the journal entry commit together or not at all. No network call is made from this class —
  * reaching an external system is the worker's job, after the commit.
  */
final class NotificationChannelManagement[Tx[_]: MonadThrow](
  channels: NotificationChannelRepository[Tx],
  secrets: NotificationChannelSecretRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx],
  cipher: NotificationChannelCryptography,
  audit: AuditRecorder[Tx]
) {

  def create(actor: ActorContext, command: NotificationChannelCommand): Tx[NotificationChannel] =
    for {
      _ <- raiseIfInvalid(validate(command))
      credential <- command.credential.liftTo[Tx](
        NotificationChannelError("NOTIFICATION_CREDENTIAL_REQUIRED",
          "A credential is required for a new notification channel"))
      _ <- raiseIfInvalid(requireMatchingCredential(command.settings, credential))
      id <- ids.nextId
      secretId <- ids.nextId
      now <- time.now
      secret = cipher.encrypt(secretId, actor.organizationId, credential)
      channel = NotificationChannel(id, actor.organizationId, command.name.trim, command.enabled,
        command.subscriptions, trimmed(command.settings), secretId, now, now)
      // The credential exists before the channel points at it: the foreign key holds at every
      // moment of the transaction, not only at its end.
      _ <- secrets.save(secret)
      _ <- channels.save(channel)
      _ <- audit.record(actor, AuditAction.NotificationChannelCreated,
        AuditTargetType.NotificationChannel, Some(id))
    } yield channel

  def update(
    actor: ActorContext,
    id: UUID,
    command: NotificationChannelCommand
  ): Tx[NotificationChannel] =
    for {
      _ <- raiseIfInvalid(validate(command))
      stored <- load(actor.organizationId, id)
      // Switching the channel type cannot reuse the credential of the previous one: a webhook
      // URL is not a bot token, and the stored payload knows which it is.
      _ <- raiseIfInvalid(requireCredentialForTypeChange(stored, command))
      _ <- raiseIfInvalid(command.credential
        .traverse_(requireMatchingCredential(command.settings, _)))
      now <- time.now
      replacement <- command.credential.traverse(credential =>
        ids.nextId.map(secretId => cipher.encrypt(secretId, actor.organizationId, credential)))
      _ <- replacement.traverse_(secrets.save)
      next = stored.copy(name = command.name.trim, enabled = command.enabled,
        subscriptions = command.subscriptions, settings = trimmed(command.settings),
        secretId = replacement.map(_.id).getOrElse(stored.secretId), updatedAt = now)
      _ <- channels.save(next)
      // The old credential has no owner left, so it does not outlive the change.
      _ <- replacement.traverse_(_ => secrets.delete(actor.organizationId, stored.secretId))
      _ <- audit.record(actor, AuditAction.NotificationChannelUpdated,
        AuditTargetType.NotificationChannel, Some(id))
    } yield next

  /** Switching a channel off is the lifecycle of this version: nothing is deleted, so the
    * delivery history of a channel keeps its subject.
    */
  def setEnabled(actor: ActorContext, id: UUID, enabled: Boolean): Tx[NotificationChannel] =
    for {
      stored <- load(actor.organizationId, id)
      now <- time.now
      next = stored.copy(enabled = enabled, updatedAt = now)
      _ <- if (stored.enabled == enabled) ().pure[Tx] else channels.save(next)
      action = if (enabled) AuditAction.NotificationChannelEnabled
        else AuditAction.NotificationChannelDisabled
      _ <- audit.record(actor, action, AuditTargetType.NotificationChannel, Some(id))
    } yield next

  /** Reads the row and holds it for the rest of the transaction, so two concurrent edits of one
    * channel are applied one after the other instead of one overwriting the other.
    */
  private def load(organizationId: UUID, id: UUID): Tx[NotificationChannel] =
    channels.findByIdForUpdate(organizationId, id).flatMap(_.liftTo[Tx](
      NotificationChannelError("NOTIFICATION_CHANNEL_NOT_FOUND",
        "Notification channel was not found")))

  private def trimmed(settings: NotificationChannelSettings): NotificationChannelSettings =
    settings match {
      case NotificationChannelSettings.Telegram(chatId) =>
        NotificationChannelSettings.Telegram(chatId.trim)
      case NotificationChannelSettings.Webhook => NotificationChannelSettings.Webhook
    }

  private def validate(command: NotificationChannelCommand): Either[NotificationChannelError, Unit] =
    for {
      _ <- check(command.name.trim.nonEmpty && command.name.trim.length <= 255,
        "Invalid notification channel name")
      // A channel that subscribes to nothing would be configuration nobody could ever reach.
      _ <- check(!command.subscriptions.isEmpty,
        "A notification channel must subscribe to at least one event type and one reason")
      _ <- validateSettings(command.settings)
      _ <- command.credential.traverse_(validateCredential)
    } yield ()

  private def validateSettings(
    settings: NotificationChannelSettings
  ): Either[NotificationChannelError, Unit] = settings match {
    case NotificationChannelSettings.Webhook => Right(())
    case NotificationChannelSettings.Telegram(chatId) =>
      check(chatId.trim.nonEmpty && chatId.trim.length <= 64, "Invalid Telegram chat")
  }

  private def validateCredential(
    credential: NotificationChannelCredential
  ): Either[NotificationChannelError, Unit] = credential match {
    case NotificationChannelCredential.WebhookUrl(url) =>
      check(NotificationChannelManagement.isDeliverableUrl(url),
        "Webhook URL must be an absolute http or https address")
    case NotificationChannelCredential.TelegramBotToken(token) =>
      check(token.trim.nonEmpty && token.trim.length <= 256, "Invalid Telegram bot token")
  }

  private def requireMatchingCredential(
    settings: NotificationChannelSettings,
    credential: NotificationChannelCredential
  ): Either[NotificationChannelError, Unit] =
    check(settings.channelType == credential.channelType,
      "The credential does not match the notification channel type")

  private def requireCredentialForTypeChange(
    stored: NotificationChannel,
    command: NotificationChannelCommand
  ): Either[NotificationChannelError, Unit] =
    if (stored.channelType == command.settings.channelType || command.credential.nonEmpty) Right(())
    else Left(NotificationChannelError("NOTIFICATION_CREDENTIAL_REQUIRED",
      "A new credential is required when the notification channel type changes"))

  private def check(valid: Boolean, message: String): Either[NotificationChannelError, Unit] =
    if (valid) Right(()) else Left(NotificationChannelError("INVALID_REQUEST", message))

  private def raiseIfInvalid(value: Either[NotificationChannelError, Unit]): Tx[Unit] =
    value.liftTo[Tx]
}

object NotificationChannelManagement {

  /** The URL is never echoed back, so it is validated rather than displayed. */
  def isDeliverableUrl(value: String): Boolean = {
    val trimmed = value.trim
    val absolute = trimmed.startsWith("http://") || trimmed.startsWith("https://")
    val host = trimmed.drop(trimmed.indexOf("://") + 3).takeWhile(character =>
      character != '/' && character != '?' && character != '#')
    absolute && host.nonEmpty && trimmed.length <= 2048 &&
      !trimmed.exists(character => character.isWhitespace || character.isControl)
  }
}
