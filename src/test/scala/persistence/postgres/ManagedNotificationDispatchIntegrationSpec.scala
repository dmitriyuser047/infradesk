package ru.bitec.app.ops
package persistence.postgres

import application.auth.ActorContext
import application.notification.{
  CreateNotificationChannelCommand,
  ManagedNotificationSender,
  NotificationChannelManagement,
  NotificationDispatcher,
  UpdateNotificationChannelCommand
}
import application.port.{
  NotificationDeliveryRepository,
  NotificationDeliveryScope,
  NotificationSendResult
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{
  EmailSecurity,
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationChannelType,
  NotificationDelivery,
  NotificationDeliveryStatus,
  NotificationDeliveryTarget,
  NotificationEventType,
  NotificationSubscriptions
}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import infrastructure.runtime.SystemTimeProvider
import integration.notification.NotificationChannelCipher
import integration.ssh.SecretEncryptionConfig
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.{AuthorizationFixtures, RecordingTransports}

import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.duration._

/** The managed worker end to end, against a real database.
  *
  * What it claims, what it resolves, what it sends and what it writes back — and above all what
  * it does when the channel is not what it was when the delivery was recorded.
  */
final class ManagedNotificationDispatchIntegrationSpec extends FunSuite {

  override val munitTimeout: Duration = 120.seconds

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000d0")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000d1")
  private val Now = Instant.parse("2026-09-25T12:00:00Z")
  private val opened = NotificationEventType.IncidentOpened
  private val threshold = IncidentReason.ThresholdViolation

  test("a managed delivery is sent through the channel it names and marked as sent") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram)
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(transports.telegrams.map { case (token, chat, _) => (token, chat) },
          List(("123:abc", "-100777")))
        assertEquals(rows.map(_.status), List(NotificationDeliveryStatus.Sent))
        assertEquals(rows.map(_.attemptCount), List(1L))
        assert(rows.forall(_.sentAt.isDefined))
        // The claim is released by the completion, not held until the lease runs out.
        assert(rows.forall(_.claimedBy.isEmpty))
        assertEquals(rows.flatMap(_.lastErrorCode), Nil)
      }
    }
  }

  test("one worker serves every kind of channel, and the legacy one serves none of them") {
    withFixture { fixture =>
      for {
        telegram <- fixture.channel("Telegram", NotificationChannelType.Telegram)
        mail <- fixture.channel("Mail", NotificationChannelType.Email)
        hook <- fixture.channel("Webhook", NotificationChannelType.Webhook)
        _ <- List(
          telegram -> NotificationChannelType.Telegram,
          mail -> NotificationChannelType.Email,
          hook -> NotificationChannelType.Webhook
        ).traverse_ { case (id, channelType) => fixture.enqueue(id, channelType) }
        _ <- fixture.enqueueLegacy
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        // Three destinations, three transports, one worker.
        assertEquals(transports.telegrams.size, 1)
        assertEquals(transports.emails.size, 1)
        assertEquals(transports.webhooks.size, 1)

        val managed = rows.filter(_.target.channelId.isDefined)
        assertEquals(managed.map(_.status).distinct, List(NotificationDeliveryStatus.Sent))
        // The deployment's own webhook is not this worker's, and stays where it was.
        val legacy = rows.filter(_.target.channelId.isEmpty)
        assertEquals(legacy.map(_.status), List(NotificationDeliveryStatus.Pending))
        assert(legacy.forall(_.claimedBy.isEmpty))
      }
    }
  }

  test("a credential rotated after the delivery was recorded is the one that is used") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram)
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        _ <- fixture.run(fixture.management.update(fixture.actor, channel,
          UpdateNotificationChannelCommand("Telegram Ops", fixture.subscriptions,
            NotificationChannelSettings.Telegram("-100999"),
            Some(NotificationChannelCredential.TelegramBotToken("999:rotated")))))
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
      } yield IO {
        assertEquals(transports.telegrams.map { case (token, chat, _) => (token, chat) },
          List(("999:rotated", "-100999")))
      }
    }
  }

  test("a channel switched off after the delivery was recorded is still delivered to") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram)
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        _ <- fixture.run(fixture.management.setEnabled(fixture.actor, channel, enabled = false))
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        // Being switched off decides which channels an event reaches; this one already did.
        assertEquals(transports.telegrams.size, 1)
        assertEquals(rows.map(_.status), List(NotificationDeliveryStatus.Sent))
      }
    }
  }

  test("a channel changed to another type is sent through the type it is now") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Ops", NotificationChannelType.Telegram)
        // Recorded while it was a Telegram channel.
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        _ <- fixture.run(fixture.management.update(fixture.actor, channel,
          UpdateNotificationChannelCommand("Ops", fixture.subscriptions, fixture.emailSettings,
            Some(NotificationChannelCredential.EmailPassword("s3cret")))))
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(transports.emails.map(_._2), List("s3cret"))
        assertEquals(transports.telegrams, Nil)
        assertEquals(rows.map(_.status), List(NotificationDeliveryStatus.Sent))
      }
    }
  }

  test("changed subscriptions do not cancel a delivery that was already recorded") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram)
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        _ <- fixture.run(fixture.management.update(fixture.actor, channel,
          UpdateNotificationChannelCommand("Telegram Ops",
            // Subscribed to nothing this event is.
            NotificationSubscriptions(Set(NotificationEventType.IncidentResolved),
              Set(IncidentReason.NoData)),
            NotificationChannelSettings.Telegram("-100777"), None)))
        transports = new RecordingTransports
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(transports.telegrams.size, 1)
        assertEquals(rows.map(_.status), List(NotificationDeliveryStatus.Sent))
      }
    }
  }

  test("a channel of another organization is never resolved, and nothing is sent") {
    withFixture { fixture =>
      for {
        theirs <- fixture.channel("Theirs", NotificationChannelType.Telegram,
          organizationId = OtherOrganizationId)
        // A delivery of this organization naming a channel of the other one cannot be stored at
        // all: the tenant foreign key refuses it.
        refused <- fixture.run(fixture.deliveries.saveAll(List(
          fixture.delivery(NotificationDeliveryTarget.Managed(theirs,
            NotificationChannelType.Telegram))))).attempt
        // The resolution is scoped the same way, so a row that somehow named it finds nothing.
        resolved <- fixture.run(fixture.dispatchQuery.find(OrganizationId, theirs))
      } yield IO {
        assert(refused.isLeft, "a delivery borrowed the channel of another organization")
        assertEquals(resolved, None)
      }
    }
  }

  test("a transport that asks to be retried leaves the delivery pending, with a safe code") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram)
        _ <- fixture.enqueue(channel, NotificationChannelType.Telegram)
        transports = new RecordingTransports(
          NotificationSendResult.RetryableFailure("TELEGRAM_RATE_LIMITED"))
        _ <- fixture.dispatch(transports)
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(rows.map(_.status), List(NotificationDeliveryStatus.Pending))
        assertEquals(rows.map(_.attemptCount), List(1L))
        assertEquals(rows.flatMap(_.lastErrorCode), List("TELEGRAM_RATE_LIMITED"))
        // Nothing of the channel, and nothing of its credential, is in what was written back.
        val stored = rows.mkString(" ")
        assert(!stored.contains("123:abc"), "the credential reached the outbox")
        assert(!stored.contains("-100777"), "the chat reached the outbox")
        // Scheduled for later rather than immediately: this is the existing retry policy.
        assert(rows.forall(_.nextAttemptAt.isAfter(Now)))
      }
    }
  }

  private def withFixture(body: DispatchFixture => IO[IO[Unit]]): Unit =
    support.SharedNotificationQueue.exclusive { PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new DispatchFixture(new DoobieTransactionRunner(xa))
      fixture.setUp *> body(fixture).flatten.guarantee(fixture.reset)
    }.unsafeRunSync() }

  private final class DispatchFixture(runner: DoobieTransactionRunner) {
    private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
    private val cipher = NotificationChannelCipher.fromConfig(
      SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
        .toOption.get)

    val incidentId: UUID = UUID.randomUUID()
    val resourceId: UUID = UUID.randomUUID()
    val ruleId: UUID = UUID.randomUUID()
    private val environmentId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val code = s"dispatch-${UUID.randomUUID().toString.take(8)}"

    val deliveries: NotificationDeliveryRepository[ConnectionIO] =
      new PostgresNotificationDeliveryRepository
    val dispatchQuery = new PostgresNotificationChannelDispatchQuery
    private val channels = new PostgresNotificationChannelRepository

    val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, OrganizationId)

    val subscriptions: NotificationSubscriptions =
      NotificationSubscriptions(Set(opened), Set(threshold))

    val emailSettings: NotificationChannelSettings.Email = NotificationChannelSettings.Email(
      "smtp.example.test", 587, EmailSecurity.StartTls, "alerts@example.test",
      "alerts@example.test", List("admin@example.test"))

    val management: NotificationChannelManagement[ConnectionIO] =
      new NotificationChannelManagement[ConnectionIO](channels,
        new PostgresNotificationChannelSecretRepository, new ConnectionIOIdGenerator,
        new ConnectionIOTimeProvider, cipher,
        new application.audit.AuditRecorder[ConnectionIO](new PostgresAuditEventRepository,
          new ConnectionIOIdGenerator, new ConnectionIOTimeProvider))

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def channel(
      name: String,
      channelType: NotificationChannelType,
      organizationId: UUID = OrganizationId
    ): IO[UUID] = {
      val settings = channelType match {
        case NotificationChannelType.Webhook => NotificationChannelSettings.Webhook
        case NotificationChannelType.Telegram => NotificationChannelSettings.Telegram("-100777")
        case NotificationChannelType.Email => emailSettings
      }
      val credential = channelType match {
        case NotificationChannelType.Webhook =>
          NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x")
        case NotificationChannelType.Telegram =>
          NotificationChannelCredential.TelegramBotToken("123:abc")
        case NotificationChannelType.Email => NotificationChannelCredential.EmailPassword("pw")
      }
      run(management.create(ActorContext(AuthorizationFixtures.ActorUserId, organizationId),
        CreateNotificationChannelCommand(name, enabled = true, subscriptions, settings,
          Some(credential)))).map(_.id)
    }

    /** A delivery as routing would have written it, due now so the worker sees it. */
    def enqueue(channelId: UUID, channelType: NotificationChannelType): IO[Unit] =
      run(deliveries.saveAll(List(
        delivery(NotificationDeliveryTarget.Managed(channelId, channelType)))))

    /** The deployment's own webhook, recorded but never due: the worker that serves it belongs
      * to another suite, and a row of this one must not end up in its wave.
      */
    def enqueueLegacy: IO[Unit] =
      run(deliveries.saveAll(List(delivery(NotificationDeliveryTarget.LegacyWebhook)
        .copy(nextAttemptAt = Instant.parse("2099-01-01T00:00:00Z")))))

    def delivery(target: NotificationDeliveryTarget): NotificationDelivery =
      NotificationDelivery(UUID.randomUUID(), OrganizationId, incidentId, resourceId, ruleId,
        opened, threshold, target, Now, NotificationDeliveryStatus.Pending, 0, Now, None, None,
        None, None, Now, Now)

    /** The managed worker, with transports that record instead of connecting.
      *
      * A claim skips rows another suite holds at that instant, and a tick takes the first due
      * rows of every organization, so the tick is repeated while this spec's own deliveries are
      * still untouched. Once each has been attempted the loop stops, which is why a delivery that
      * asks to be retried is attempted exactly once. The bound is generous on purpose: under a
      * loaded parallel run other suites can hold or crowd the queue for a while, and the loop
      * costs nothing once this spec's rows are done.
      */
    def dispatch(transports: RecordingTransports): IO[Unit] = {
      val sender = new ManagedNotificationSender[IO, ConnectionIO](dispatchQuery, cipher, runner,
        transports.webhook, transports.telegram, transports.email)
      val dispatcher = new NotificationDispatcher[IO, ConnectionIO](deliveries, sender, runner,
        new SystemTimeProvider,
        Slf4jLogger.getLoggerFromName[IO]("test.notification.managed"),
        NotificationDeliveryScope.Managed, maxConcurrency = 4, UUID.randomUUID(), 60.seconds,
        maxAttempts = 3)

      def untouched: IO[Boolean] = deliveryRows.map(_.exists(row =>
        row.target.channelId.isDefined && row.attemptCount == 0))

      def attempt(remaining: Int): IO[Unit] =
        dispatcher.tick(10) *> untouched.flatMap {
          case true if remaining > 0 => IO.sleep(100.millis) *> attempt(remaining - 1)
          case _ => IO.unit
        }

      attempt(100)
    }

    def deliveryRows: IO[List[NotificationDelivery]] =
      run(sql"""
        select id from notification_delivery
         where organization_id = $OrganizationId and monitor_rule_id = $ruleId
         order by created_at, id
      """.query[UUID].to[List]
        .flatMap(_.traverse(deliveries.findById(OrganizationId, _)))
        .map(_.flatten))

    def setUp: IO[Unit] = reset *> run(for {
      _ <- sql"""
        insert into organization (id, code, name) values
          ($OrganizationId, ${s"dispatch-$code"}, 'Dispatch'),
          ($OtherOrganizationId, ${s"dispatch-other-$code"}, 'Dispatch other')
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into organization_membership (user_id, organization_id, role, created_at, updated_at)
        values
          (${AuthorizationFixtures.ActorUserId}, $OrganizationId, 'OWNER', current_timestamp, current_timestamp),
          (${AuthorizationFixtures.ActorUserId}, $OtherOrganizationId, 'OWNER', current_timestamp, current_timestamp)
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into project (id, organization_id, code, name)
        values ($projectId, $OrganizationId, $code, 'Dispatch') on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into environment (id, organization_id, project_id, code, name, kind)
        values ($environmentId, $OrganizationId, $projectId, $code, 'Dispatch', 'TEST')
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into resource (id, organization_id, environment_id, resource_type_id, code, name,
                              is_active)
        values ($resourceId, $OrganizationId, $environmentId,
                '10000000-0000-0000-0000-000000000001', $code, 'Dispatch', true)
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into monitor_rule (id, organization_id, resource_id, metric_code, operator,
                                  threshold, for_seconds, no_data_seconds, enabled,
                                  created_at, updated_at)
        values ($ruleId, $OrganizationId, $resourceId, 'CPU_USAGE_PERCENT', 'GREATER_THAN', 90, 0,
                900, true, $Now, $Now)
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into incident (id, organization_id, monitor_rule_id, resource_id, status, reason,
                              started_at, opened_at, created_at, updated_at)
        values ($incidentId, $OrganizationId, $ruleId, $resourceId, 'OPEN', 'THRESHOLD', $Now, $Now,
                $Now, $Now)
        on conflict do nothing
      """.update.run
    } yield ())

    /** This spec owns its organizations, so it clears only its own rows. */
    def reset: IO[Unit] = run(for {
      _ <- sql"""
        delete from notification_delivery
         where organization_id in ($OrganizationId, $OtherOrganizationId)
      """.update.run
      _ <- sql"""
        delete from notification_channel
         where organization_id in ($OrganizationId, $OtherOrganizationId)
      """.update.run
      _ <- sql"""
        delete from notification_channel_secret
         where organization_id in ($OrganizationId, $OtherOrganizationId)
      """.update.run
      _ <- sql"""
        delete from audit_event where organization_id in ($OrganizationId, $OtherOrganizationId)
      """.update.run
    } yield ())
  }
}
