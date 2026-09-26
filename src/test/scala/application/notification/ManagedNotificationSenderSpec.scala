package ru.bitec.app.ops
package application.notification

import application.port.{
  NotificationChannelCryptography,
  NotificationChannelDispatchQuery,
  NotificationChannelDispatchTarget,
  NotificationChannelSecret,
  NotificationSendRequest,
  NotificationSendResult,
  TransactionRunner
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.IncidentReason
import domain.notification.{
  EmailSecurity,
  NotificationChannel,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationChannelType,
  NotificationDeliveryTarget,
  NotificationEventType,
  NotificationSubscriptions
}
import integration.notification.NotificationChannelCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import support.RecordingTransports

import java.time.Instant
import java.util.{Base64, UUID}

/** Sending to a configured channel: which transport, with what, and what happens when the
  * configuration no longer makes sense.
  */
final class ManagedNotificationSenderSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000002")
  private val ChannelId = UUID.fromString("30000000-0000-0000-0000-000000000001")
  private val SecretId = UUID.fromString("40000000-0000-0000-0000-000000000001")
  private val Now = Instant.parse("2026-09-25T10:00:00Z")

  private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
  private val cipher = NotificationChannelCipher.fromConfig(
    SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
      .toOption.get)

  private val event = NotificationEvent(
    eventId = UUID.fromString("c0000000-0000-0000-0000-000000000001"),
    eventType = NotificationEventType.IncidentOpened,
    occurredAt = Now,
    organizationId = OrganizationId,
    resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001"),
    monitorRuleId = UUID.fromString("90000000-0000-0000-0000-000000000001"),
    incidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001"),
    reason = IncidentReason.ThresholdViolation
  )

  private val telegramSettings = NotificationChannelSettings.Telegram("-100777")
  private val emailSettings = NotificationChannelSettings.Email("smtp.example.test", 587,
    EmailSecurity.StartTls, "alerts@example.test", "alerts@example.test",
    List("admin@example.test"))

  test("a webhook channel is reached at the URL it holds now") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(NotificationChannelSettings.Webhook),
      NotificationChannelCredential.WebhookUrl("https://hooks.example.test/t/secret"))

    assertEquals(send(sender), NotificationSendResult.Sent)
    assertEquals(transports.webhooks.map(_._1), List("https://hooks.example.test/t/secret"))
    // The event goes through untouched: the webhook contract is the event, not a message.
    assertEquals(transports.webhooks.map(_._2), List(event))
    assertEquals(transports.telegrams, Nil)
    assertEquals(transports.emails, Nil)
  }

  test("a Telegram channel is reached in the chat it holds now, with a readable message") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(telegramSettings),
      NotificationChannelCredential.TelegramBotToken("123:abc"))

    assertEquals(send(sender), NotificationSendResult.Sent)
    assertEquals(transports.telegrams.map { case (token, chat, _) => (token, chat) },
      List(("123:abc", "-100777")))
    assertEquals(transports.telegrams.map(_._3), List(NotificationMessage.of(event)))
    assertEquals(transports.webhooks, Nil)
  }

  test("a mail channel is reached through the relay it holds now") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(emailSettings),
      NotificationChannelCredential.EmailPassword("s3cret"))

    assertEquals(send(sender), NotificationSendResult.Sent)
    assertEquals(transports.emails.map { case (settings, password, _) => (settings, password) },
      List((emailSettings, "s3cret")))
    assertEquals(transports.emails.map(_._3), List(NotificationMessage.of(event)))
  }

  test("the transport follows the channel as it is now, not as the delivery recorded it") {
    val transports = new RecordingTransports
    // The delivery was routed when the channel was Telegram; it is a mail channel now.
    val sender = build(transports, channel(emailSettings),
      NotificationChannelCredential.EmailPassword("s3cret"))

    val result = sender.send(NotificationSendRequest(event,
      NotificationDeliveryTarget.Managed(ChannelId, NotificationChannelType.Telegram)))
      .unsafeRunSync()

    assertEquals(result, NotificationSendResult.Sent)
    assertEquals(transports.emails.size, 1)
    assertEquals(transports.telegrams, Nil)
  }

  test("a credential rotated after the delivery was recorded is the one that is used") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(telegramSettings),
      NotificationChannelCredential.TelegramBotToken("999:rotated"))

    assertEquals(send(sender), NotificationSendResult.Sent)
    assertEquals(transports.telegrams.map(_._1), List("999:rotated"))
  }

  test("a channel switched off after the delivery was recorded is still delivered to") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(telegramSettings).copy(enabled = false),
      NotificationChannelCredential.TelegramBotToken("123:abc"))

    // Being switched off decides which channels an event reaches, which was settled already.
    assertEquals(send(sender), NotificationSendResult.Sent)
    assertEquals(transports.telegrams.size, 1)
  }

  test("a channel that is gone is a configuration problem, not something to retry") {
    val transports = new RecordingTransports
    val sender = new ManagedNotificationSender[IO, IO](
      (_: UUID, _: UUID) => IO.pure(None), cipher, DirectRunner, transports.webhook,
      transports.telegram, transports.email)

    assertEquals(send(sender),
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.ChannelNotFound))
    assertEquals(transports.webhooks, Nil)
  }

  test("a channel whose credential is missing or unreadable is not retried either") {
    val transports = new RecordingTransports
    val missing = build(transports, channel(telegramSettings), secret = None)
    val unreadable = build(transports, channel(telegramSettings),
      secret = Some(NotificationChannelSecret(SecretId, OrganizationId,
        NotificationChannelCipher.CredentialKind, new Array[Byte](12), new Array[Byte](32))))

    assertEquals(send(missing),
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.SecretNotFound))
    assertEquals(send(unreadable),
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.CredentialInvalid))
    assertEquals(transports.telegrams, Nil)
  }

  test("a credential of another kind than the channel is refused rather than sent") {
    val transports = new RecordingTransports
    // The channel is Telegram now; what is stored is the webhook URL it used to be.
    val sender = build(transports, channel(telegramSettings),
      NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x"))

    assertEquals(send(sender),
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.CredentialInvalid))
    assertEquals(transports.webhooks, Nil)
    assertEquals(transports.telegrams, Nil)
  }

  test("the deployment's own webhook is not this worker's to send") {
    val transports = new RecordingTransports
    val sender = build(transports, channel(telegramSettings),
      NotificationChannelCredential.TelegramBotToken("123:abc"))

    val result = sender
      .send(NotificationSendRequest(event, NotificationDeliveryTarget.LegacyWebhook))
      .unsafeRunSync()

    assertEquals(result,
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.UnsupportedTarget))
    assertEquals(transports.telegrams, Nil)
  }

  test("a channel is looked up by its tenant, so another organization's is not found") {
    val transports = new RecordingTransports
    val stored = channel(telegramSettings)
    val query: NotificationChannelDispatchQuery[IO] = (organizationId: UUID, id: UUID) =>
      IO.pure(Option.when(organizationId == OtherOrganizationId && id == ChannelId)(
        NotificationChannelDispatchTarget(stored, Some(cipher.encrypt(SecretId,
          OtherOrganizationId, NotificationChannelCredential.TelegramBotToken("123:abc"))))))
    val sender = new ManagedNotificationSender[IO, IO](query, cipher, DirectRunner,
      transports.webhook, transports.telegram, transports.email)

    // The event belongs to OrganizationId; the channel exists, but not for this tenant.
    assertEquals(send(sender),
      NotificationSendResult.PermanentFailure(ManagedNotificationSender.ChannelNotFound))
    assertEquals(transports.telegrams, Nil)
  }

  test("what the transport answers is what the dispatcher is told") {
    for (result <- List(
      NotificationSendResult.Sent,
      NotificationSendResult.RetryableFailure("TELEGRAM_RATE_LIMITED"),
      NotificationSendResult.PermanentFailure("TELEGRAM_AUTH_ERROR")
    )) {
      val transports = new RecordingTransports(result)
      val sender = build(transports, channel(telegramSettings),
        NotificationChannelCredential.TelegramBotToken("123:abc"))

      assertEquals(send(sender), result)
    }
  }

  private def send(sender: ManagedNotificationSender[IO, IO]): NotificationSendResult =
    sender.send(NotificationSendRequest(event,
      NotificationDeliveryTarget.Managed(ChannelId, NotificationChannelType.Telegram)))
      .unsafeRunSync()

  private def channel(settings: NotificationChannelSettings): NotificationChannel =
    NotificationChannel(ChannelId, OrganizationId, "Channel", enabled = true,
      NotificationSubscriptions(Set(NotificationEventType.IncidentOpened),
        Set(IncidentReason.ThresholdViolation)), settings, SecretId, Now, Now)

  private def build(
    transports: RecordingTransports,
    stored: NotificationChannel,
    credential: NotificationChannelCredential
  ): ManagedNotificationSender[IO, IO] =
    build(transports, stored, Some(cipher.encrypt(SecretId, OrganizationId, credential)))

  private def build(
    transports: RecordingTransports,
    stored: NotificationChannel,
    secret: Option[NotificationChannelSecret]
  ): ManagedNotificationSender[IO, IO] = {
    val query: NotificationChannelDispatchQuery[IO] = (organizationId: UUID, id: UUID) =>
      IO.pure(Option.when(organizationId == stored.organizationId && id == stored.id)(
        NotificationChannelDispatchTarget(stored, secret)))
    new ManagedNotificationSender[IO, IO](query, cipher, DirectRunner, transports.webhook,
      transports.telegram, transports.email)
  }

  /** The resolution is a read, and in this spec it is not wrapped in anything. */
  private object DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }
}
