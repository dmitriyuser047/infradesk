package ru.bitec.app.ops
package persistence.postgres

import application.auth.ActorContext
import application.notification.{
  CreateNotificationChannelCommand,
  NotificationChannelManagement,
  UpdateNotificationChannelCommand
}
import application.port.{NotificationChannelRepository, NotificationChannelSecretRepository}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.audit.{AuditCursor, AuditEvent}
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannel,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationEventType,
  NotificationSubscriptions
}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import integration.notification.NotificationChannelCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.AuthorizationFixtures

import java.util.{Base64, UUID}

/** Notification channels against a real database: what the schema refuses, what a tenant can
  * reach, and the fact that a credential is bytes on disk rather than text.
  */
final class NotificationChannelIntegrationSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000b1")
  private val BotToken = "7654321:AA-integration-secret"

  test("a channel and its credential commit together, and the credential is bytes on disk") {
    withFixture { fixture =>
      for {
        channel <- fixture.run(fixture.management.create(fixture.actor, telegram()))
        stored <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
        secret <- fixture.run(fixture.secrets.find(OrganizationId, channel.secretId))
        plaintextRows <- fixture.run(sql"""
          select count(*) from notification_channel_secret
           where id = ${channel.secretId}
             and position(${BotToken.getBytes("UTF-8")}::bytea in ciphertext) > 0
        """.query[Long].unique)
      } yield IO {
        assertEquals(stored.map(_.name), Some("Ops Telegram"))
        assertEquals(stored.map(_.channelType.code), Some("TELEGRAM"))
        assertEquals(stored.map(_.settings), Some(NotificationChannelSettings.Telegram("-100777")))
        assertEquals(stored.map(_.subscriptions.orderedEventTypes.map(_.code)),
          Some(List("INCIDENT_OPENED", "INCIDENT_RESOLVED")))
        assertEquals(stored.map(_.subscriptions.orderedReasons.map(_.code)),
          Some(List("THRESHOLD", "NO_DATA")))
        // The token is in the row only as ciphertext.
        assertEquals(plaintextRows, 0L)
        assertEquals(secret.map(fixture.cipher.decrypt),
          Some(NotificationChannelCredential.TelegramBotToken(BotToken)))
      }
    }
  }

  test("a failed transaction leaves neither the channel nor its credential behind") {
    withFixture { fixture =>
      val management = fixture.managementWith(new FailingAuditRecorderRepository)
      for {
        result <- fixture.run(management.create(fixture.actor, telegram())).attempt
        channels <- fixture.run(fixture.channels.listByOrganization(OrganizationId))
        secrets <- fixture.run(
          sql"select count(*) from notification_channel_secret where organization_id = $OrganizationId"
            .query[Long].unique)
      } yield IO {
        assert(result.isLeft, "the journal failed but the mutation succeeded")
        assertEquals(channels, List.empty[NotificationChannel])
        // The credential is written in the same transaction, so it rolls back with it.
        assertEquals(secrets, 0L)
      }
    }
  }

  test("one tenant never reads or updates the channel of another") {
    withFixture { fixture =>
      val foreignActor = ActorContext(AuthorizationFixtures.ActorUserId, OtherOrganizationId)
      for {
        mine <- fixture.run(fixture.management.create(fixture.actor, telegram()))
        theirs <- fixture.run(fixture.management.create(foreignActor, telegram(name = "Foreign")))
        crossRead <- fixture.run(fixture.channels.findById(OrganizationId, theirs.id))
        mineList <- fixture.run(fixture.channels.listByOrganization(OrganizationId))
        crossUpdate <- fixture.run(
          fixture.management.setEnabled(fixture.actor, theirs.id, enabled = false)).attempt
        stillEnabled <- fixture.run(fixture.channels.findById(OtherOrganizationId, theirs.id))
      } yield IO {
        assertEquals(crossRead, None)
        assertEquals(mineList.map(_.id), List(mine.id))
        assert(crossUpdate.isLeft, "a foreign channel was changed")
        assertEquals(stillEnabled.map(_.enabled), Some(true))
      }
    }
  }

  test("an edit keeps the stored credential, and a new one replaces it without a leftover") {
    withFixture { fixture =>
      for {
        channel <- fixture.run(fixture.management.create(fixture.actor, telegram()))
        renamed <- fixture.run(fixture.management.update(fixture.actor, channel.id,
          edit(name = "Renamed")))
        keptSecret <- fixture.run(fixture.secrets.find(OrganizationId, renamed.secretId))
        rotated <- fixture.run(fixture.management.update(fixture.actor, channel.id,
          edit(name = "Renamed",
            credential = Some(NotificationChannelCredential.TelegramBotToken("999:rotated")))))
        remaining <- fixture.run(
          sql"select count(*) from notification_channel_secret where organization_id = $OrganizationId"
            .query[Long].unique)
        rotatedSecret <- fixture.run(fixture.secrets.find(OrganizationId, rotated.secretId))
      } yield IO {
        assertEquals(renamed.secretId, channel.secretId)
        assertEquals(keptSecret.map(fixture.cipher.decrypt),
          Some(NotificationChannelCredential.TelegramBotToken(BotToken)))
        assertNotEquals(rotated.secretId, channel.secretId)
        // The replaced credential does not outlive the change it was replaced by.
        assertEquals(remaining, 1L)
        assertEquals(rotatedSecret.map(fixture.cipher.decrypt),
          Some(NotificationChannelCredential.TelegramBotToken("999:rotated")))
      }
    }
  }

  test("a lifecycle transition is one commit; asking for the state it is in writes nothing") {
    withFixture { fixture =>
      for {
        channel <- fixture.run(fixture.management.create(fixture.actor, telegram(enabled = false)))
        // What the database holds, which is what a no-op has to answer with.
        created <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
        // The state it is already in: no update, no journal entry, and the row it already has.
        noop <- fixture.run(fixture.management.setEnabled(fixture.actor, channel.id, enabled = false))
        afterNoop <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
        enabled <- fixture.run(fixture.management.setEnabled(fixture.actor, channel.id, enabled = true))
        afterEnable <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
        disabled <- fixture.run(fixture.management.setEnabled(fixture.actor, channel.id, enabled = false))
        afterDisable <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
        journal <- fixture.run(sql"""
          select action from audit_event
           where organization_id = $OrganizationId and target_id = ${channel.id}
           order by created_at, action
        """.query[String].to[List])
      } yield IO {
        // The answer of a no-op is the stored row, timestamp included, and the row does not move.
        assertEquals(Some(noop), created)
        assertEquals(afterNoop, created)

        assertEquals(afterEnable.map(_.enabled), Some(true))
        assert(afterEnable.exists(_.updatedAt.isAfter(created.get.updatedAt)),
          "the transition left the timestamp where it was")
        assertEquals(afterDisable.map(_.enabled), Some(false))
        assert(afterDisable.exists(_.updatedAt.isAfter(afterEnable.get.updatedAt)),
          "the second transition left the timestamp where it was")
        // Both transitions answered with what they stored.
        assertEquals(afterEnable.map(_.enabled), Some(enabled.enabled))
        assertEquals(afterDisable.map(_.enabled), Some(disabled.enabled))

        // One entry per transition that happened, and none for the one that did not.
        assertEquals(journal.sorted, List("NOTIFICATION_CHANNEL_CREATED",
          "NOTIFICATION_CHANNEL_DISABLED", "NOTIFICATION_CHANNEL_ENABLED").sorted)
      }
    }
  }

  test("two concurrent edits of one channel are applied one after the other") {
    withFixture { fixture =>
      for {
        channel <- fixture.run(fixture.management.create(fixture.actor, telegram()))
        // The first transaction takes the row and holds it; the second must wait for the lock
        // rather than write over a snapshot taken before the first one committed.
        rename = fixture.run(
          fixture.channels.findByIdForUpdate(OrganizationId, channel.id) *>
            sql"select pg_sleep(0.4)".query[String].unique *>
            fixture.management.update(fixture.actor, channel.id, edit(name = "Renamed")))
        disable = IO.sleep(scala.concurrent.duration.DurationInt(100).millis) *>
          fixture.run(fixture.management.setEnabled(fixture.actor, channel.id, enabled = false))
        _ <- IO.both(rename, disable)
        stored <- fixture.run(fixture.channels.findById(OrganizationId, channel.id))
      } yield IO {
        // Neither change was lost: the second edit read what the first one wrote.
        assertEquals(stored.map(_.name), Some("Renamed"))
        assertEquals(stored.map(_.enabled), Some(false))
      }
    }
  }

  test("the schema refuses a configuration that belongs to another channel type") {
    withFixture { fixture =>
      val secretId = UUID.randomUUID()
      for {
        _ <- fixture.run(fixture.secrets.save(
          fixture.cipher.encrypt(secretId, OrganizationId,
            NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x"))))
        // A webhook that carries a Telegram chat, and a Telegram channel with no chat at all.
        webhookWithChat <- fixture.run(insertRaw(secretId, "WEBHOOK", Some("-100777"),
          List("INCIDENT_OPENED"), List("THRESHOLD"))).attempt
        telegramWithoutChat <- fixture.run(insertRaw(secretId, "TELEGRAM", None,
          List("INCIDENT_OPENED"), List("THRESHOLD"))).attempt
        unknownEvent <- fixture.run(insertRaw(secretId, "WEBHOOK", None,
          List("INCIDENT_FLAPPED"), List("THRESHOLD"))).attempt
        noEvents <- fixture.run(insertRaw(secretId, "WEBHOOK", None, Nil, List("THRESHOLD"))).attempt
        noReasons <- fixture.run(insertRaw(secretId, "WEBHOOK", None, List("INCIDENT_OPENED"), Nil)).attempt
        unknownType <- fixture.run(insertRaw(secretId, "EMAIL", None,
          List("INCIDENT_OPENED"), List("THRESHOLD"))).attempt
      } yield IO {
        for ((label, result) <- List(
          "webhook with a chat" -> webhookWithChat,
          "telegram without a chat" -> telegramWithoutChat,
          "unknown event type" -> unknownEvent,
          "no event types" -> noEvents,
          "no reasons" -> noReasons,
          "unknown channel type" -> unknownType
        )) assert(result.isLeft, s"the database accepted a channel with $label")
      }
    }
  }

  test("a credential belongs to the tenant of its channel") {
    withFixture { fixture =>
      val secretId = UUID.randomUUID()
      for {
        // The credential exists, but in another organization.
        _ <- fixture.run(fixture.secrets.save(
          fixture.cipher.encrypt(secretId, OtherOrganizationId,
            NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x"))))
        result <- fixture.run(insertRaw(secretId, "WEBHOOK", None,
          List("INCIDENT_OPENED"), List("THRESHOLD"))).attempt
      } yield IO(assert(result.isLeft, "a channel borrowed the credential of another tenant"))
    }
  }

  private def insertRaw(
    secretId: UUID,
    channelType: String,
    chatId: Option[String],
    events: List[String],
    reasons: List[String]
  ): ConnectionIO[Int] =
    sql"""
      insert into notification_channel (
        id, organization_id, name, channel_type, enabled,
        subscribed_event_types, subscribed_reasons, telegram_chat_id,
        secret_id, created_at, updated_at
      ) values (
        ${UUID.randomUUID()}, $OrganizationId, 'Raw', $channelType, true,
        $events, $reasons, $chatId, $secretId, current_timestamp, current_timestamp
      )
    """.update.run

  private val everything = NotificationSubscriptions(
    Set(NotificationEventType.IncidentOpened, NotificationEventType.IncidentResolved),
    Set(IncidentReason.ThresholdViolation, IncidentReason.NoData)
  )

  private def telegram(
    name: String = "Ops Telegram",
    enabled: Boolean = true
  ): CreateNotificationChannelCommand =
    CreateNotificationChannelCommand(
      name = name,
      enabled = enabled,
      subscriptions = everything,
      settings = NotificationChannelSettings.Telegram("-100777"),
      credential = Some(NotificationChannelCredential.TelegramBotToken(BotToken))
    )

  private def edit(
    name: String = "Ops Telegram",
    credential: Option[NotificationChannelCredential] = None
  ): UpdateNotificationChannelCommand =
    UpdateNotificationChannelCommand(
      name = name,
      subscriptions = everything,
      settings = NotificationChannelSettings.Telegram("-100777"),
      credential = credential
    )

  private def withFixture(body: ChannelFixture => IO[IO[Unit]]): Unit =
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new ChannelFixture(new DoobieTransactionRunner(xa))
      fixture.reset *> body(fixture).flatten.guarantee(fixture.reset)
    }.unsafeRunSync()

  private final class ChannelFixture(runner: DoobieTransactionRunner) {
    private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
    val cipher: NotificationChannelCipher = NotificationChannelCipher.fromConfig(
      SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
        .toOption.get)

    val channels: NotificationChannelRepository[ConnectionIO] =
      new PostgresNotificationChannelRepository
    val secrets: NotificationChannelSecretRepository[ConnectionIO] =
      new PostgresNotificationChannelSecretRepository

    val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, OrganizationId)

    val management: NotificationChannelManagement[ConnectionIO] =
      managementWith(new PostgresAuditEventRepository)

    def managementWith(
      auditEvents: application.port.AuditEventRepository[ConnectionIO]
    ): NotificationChannelManagement[ConnectionIO] =
      new NotificationChannelManagement[ConnectionIO](
        channels,
        secrets,
        new ConnectionIOIdGenerator,
        new ConnectionIOTimeProvider,
        cipher,
        new application.audit.AuditRecorder[ConnectionIO](auditEvents, new ConnectionIOIdGenerator,
          new ConnectionIOTimeProvider)
      )

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    /** The fixture owns both organizations of this spec and starts from an empty settings page. */
    def reset: IO[Unit] = run(for {
      _ <- sql"""
        insert into organization (id, code, name)
        values ($OtherOrganizationId, 'notification-channel-other', 'Other organization')
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into organization_membership (user_id, organization_id, role, created_at, updated_at)
        values (${AuthorizationFixtures.ActorUserId}, $OtherOrganizationId, 'OWNER',
                current_timestamp, current_timestamp)
        on conflict do nothing
      """.update.run
      _ <- sql"delete from notification_channel".update.run
      _ <- sql"delete from notification_channel_secret".update.run
      _ <- sql"""delete from audit_event where target_type = 'NOTIFICATION_CHANNEL'""".update.run
    } yield ())
  }

  /** A journal whose insert fails, so the mutation around it has to roll back. */
  private final class FailingAuditRecorderRepository
    extends application.port.AuditEventRepository[ConnectionIO] {
    override def save(event: AuditEvent): ConnectionIO[Unit] =
      new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]
    override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] = save(events.head)
    override def listByOrganization(
      organizationId: UUID,
      before: Option[AuditCursor],
      limit: Int
    ): ConnectionIO[List[AuditEvent]] = List.empty[AuditEvent].pure[ConnectionIO]
  }
}
