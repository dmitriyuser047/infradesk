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

/** What every request to write a channel carries.
  *
  * The settings hold the channel type, so there is no way to describe a Telegram channel with a
  * webhook configuration. A credential is required to create a channel; on an update an absent
  * one means "keep the stored credential".
  */
sealed trait NotificationChannelWrite {
  def name: String
  def subscriptions: NotificationSubscriptions
  def settings: NotificationChannelSettings
  def credential: Option[NotificationChannelCredential]
}

/** A new channel decides whether it starts switched on. */
final case class CreateNotificationChannelCommand(
  name: String,
  enabled: Boolean,
  subscriptions: NotificationSubscriptions,
  settings: NotificationChannelSettings,
  credential: Option[NotificationChannelCredential]
) extends NotificationChannelWrite

/** An update changes the configuration of a channel and nothing about its lifecycle.
  *
  * Switching a channel on or off is its own operation with its own journal entry, so this command
  * simply has nowhere to put an `enabled`: changing the lifecycle through an update has no
  * representation rather than being silently ignored.
  */
final case class UpdateNotificationChannelCommand(
  name: String,
  subscriptions: NotificationSubscriptions,
  settings: NotificationChannelSettings,
  credential: Option[NotificationChannelCredential]
) extends NotificationChannelWrite

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

  def create(
    actor: ActorContext,
    command: CreateNotificationChannelCommand
  ): Tx[NotificationChannel] =
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
    command: UpdateNotificationChannelCommand
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
      // The lifecycle is not part of an update: a stored channel keeps the state that enable
      // and disable gave it.
      next = stored.copy(name = command.name.trim,
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
    *
    * Asking for the state a channel is already in changes nothing and is journalled as nothing:
    * the journal claims a transition only where a transition happened, and the answer is the row
    * as it stands rather than a timestamp the database does not hold.
    */
  def setEnabled(actor: ActorContext, id: UUID, enabled: Boolean): Tx[NotificationChannel] =
    load(actor.organizationId, id).flatMap { stored =>
      if (stored.enabled == enabled) stored.pure[Tx]
      else for {
        now <- time.now
        next = stored.copy(enabled = enabled, updatedAt = now)
        _ <- channels.save(next)
        action = if (enabled) AuditAction.NotificationChannelEnabled
          else AuditAction.NotificationChannelDisabled
        _ <- audit.record(actor, action, AuditTargetType.NotificationChannel, Some(id))
      } yield next
    }

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
      case value: NotificationChannelSettings.Email =>
        // Recipients are compared after trimming, so the stored list is the normalized one.
        value.copy(host = value.host.trim, username = value.username.trim,
          fromAddress = value.fromAddress.trim, recipients = value.recipients.map(_.trim).distinct)
      case NotificationChannelSettings.Webhook => NotificationChannelSettings.Webhook
    }

  private def validate(command: NotificationChannelWrite): Either[NotificationChannelError, Unit] =
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
    case email: NotificationChannelSettings.Email => validateEmail(email)
  }

  /** What can be checked about a mail configuration without touching the network.
    *
    * No address is resolved and no relay is contacted: whether the host exists and whether it
    * accepts these credentials is something only a delivery attempt can answer.
    */
  private def validateEmail(
    settings: NotificationChannelSettings.Email
  ): Either[NotificationChannelError, Unit] = {
    val recipients = settings.recipients.map(_.trim).filter(_.nonEmpty).distinct
    for {
      _ <- check(NotificationChannelManagement.isHost(settings.host), "Invalid SMTP host")
      _ <- check(settings.port > 0 && settings.port <= 65535, "Invalid SMTP port")
      _ <- check(settings.username.trim.nonEmpty && settings.username.trim.length <= 255,
        "Invalid SMTP user name")
      _ <- check(NotificationChannelManagement.isEmailAddress(settings.fromAddress),
        "Invalid sender address")
      _ <- check(recipients.nonEmpty, "A mail channel needs at least one recipient")
      _ <- check(recipients.sizeIs <= NotificationChannelManagement.MaxRecipients,
        s"A mail channel takes at most ${NotificationChannelManagement.MaxRecipients} recipients")
      _ <- check(recipients.forall(NotificationChannelManagement.isEmailAddress),
        "Invalid recipient address")
    } yield ()
  }

  private def validateCredential(
    credential: NotificationChannelCredential
  ): Either[NotificationChannelError, Unit] = credential match {
    case NotificationChannelCredential.WebhookUrl(url) =>
      check(NotificationChannelManagement.isDeliverableUrl(url),
        "Webhook URL must be an absolute http or https address")
    case NotificationChannelCredential.TelegramBotToken(token) =>
      check(token.trim.nonEmpty && token.trim.length <= 256, "Invalid Telegram bot token")
    case NotificationChannelCredential.EmailPassword(password) =>
      // Not trimmed: leading and trailing spaces may be part of the password.
      check(password.nonEmpty && password.length <= 256, "Invalid SMTP password")
  }

  private def requireMatchingCredential(
    settings: NotificationChannelSettings,
    credential: NotificationChannelCredential
  ): Either[NotificationChannelError, Unit] =
    check(settings.channelType == credential.channelType,
      "The credential does not match the notification channel type")

  private def requireCredentialForTypeChange(
    stored: NotificationChannel,
    command: UpdateNotificationChannelCommand
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

  /** Enough addresses for a team, few enough that one channel cannot become a mailing list. */
  val MaxRecipients: Int = 50

  /** Conservative rather than complete: this is not an RFC 5322 parser, and it does not need to
    * be. It rejects what is plainly not an address and leaves the rest to the relay.
    */
  def isEmailAddress(value: String): Boolean = {
    val trimmed = value.trim
    val parts = trimmed.split('@')
    trimmed.length <= 320 && parts.length == 2 && parts.forall(_.nonEmpty) &&
      parts(1).contains('.') && !parts(1).startsWith(".") && !parts(1).endsWith(".") &&
      !trimmed.exists(character => character.isWhitespace || character.isControl)
  }

  /** A host as it is written in a configuration: a name, an address, or an IPv6 literal. It is
    * never resolved here.
    */
  def isHost(value: String): Boolean = {
    val trimmed = value.trim
    trimmed.nonEmpty && trimmed.length <= 255 &&
      !trimmed.exists(character => character.isWhitespace || character.isControl)
  }

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
