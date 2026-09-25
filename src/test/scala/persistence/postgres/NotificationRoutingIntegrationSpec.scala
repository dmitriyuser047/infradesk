package ru.bitec.app.ops
package persistence.postgres

import application.auth.ActorContext
import application.monitor.MonitorTransition
import application.notification.{
  CreateNotificationChannelCommand,
  NotificationChannelManagement,
  RecordNotificationDeliveries
}
import application.port.{
  NotificationChannelRoutingQuery,
  NotificationDeliveryRepository,
  NotificationDeliveryScope,
  NotificationRoute,
  NotificationRoutingKey
}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.incident.IncidentReason
import domain.notification.{
  NotificationChannelCredential,
  NotificationChannelSettings,
  NotificationChannelType,
  NotificationDelivery,
  NotificationDeliveryTarget,
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

import java.time.Instant
import java.util.{Base64, UUID}

/** Where an incident transition goes, against a real database.
  *
  * The channels of an organization decide it; the outbox records one durable delivery per
  * destination it reached, and the deployment's own webhook stays the separate target it always
  * was.
  */
final class NotificationRoutingIntegrationSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000c0")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000c1")
  private val Now = Instant.parse("2026-09-25T12:00:00Z")

  private val opened = NotificationEventType.IncidentOpened
  private val resolved = NotificationEventType.IncidentResolved
  private val threshold = IncidentReason.ThresholdViolation
  private val noData = IncidentReason.NoData

  test("an event reaches every enabled channel that asked for it, and no other") {
    withFixture { fixture =>
      for {
        ops <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened, resolved), Set(threshold, noData))
        critical <- fixture.channel("Email Critical", NotificationChannelType.Email,
          Set(opened), Set(threshold))
        automation <- fixture.channel("Webhook Automation", NotificationChannelType.Webhook,
          Set(resolved), Set(threshold))

        openedNoData <- fixture.route(opened, noData)
        openedThreshold <- fixture.route(opened, threshold)
        resolvedThreshold <- fixture.route(resolved, threshold)
        resolvedNoData <- fixture.route(resolved, noData)
      } yield IO {
        assertEquals(openedNoData, Set(ops))
        assertEquals(openedThreshold, Set(ops, critical))
        assertEquals(resolvedThreshold, Set(ops, automation))
        // Nobody subscribed to a resolved no-data incident.
        assertEquals(resolvedNoData, Set(ops))
      }
    }
  }

  test("a disabled channel is not routed to, and keeps everything it had") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        whileEnabled <- fixture.route(opened, threshold)
        _ <- fixture.run(fixture.management.setEnabled(fixture.actor, channel, enabled = false))
        whileDisabled <- fixture.route(opened, threshold)
        stored <- fixture.run(fixture.channels.findById(OrganizationId, channel))
        secrets <- fixture.run(
          sql"select count(*) from notification_channel_secret where organization_id = $OrganizationId"
            .query[Long].unique)
      } yield IO {
        assertEquals(whileEnabled, Set(channel))
        assertEquals(whileDisabled, Set.empty[UUID])
        // The row and its credential are still there: disabled is a routing state, not a delete.
        assertEquals(stored.map(_.enabled), Some(false))
        assertEquals(secrets, 1L)
      }
    }
  }

  test("a channel of another organization is never reached, whatever it subscribed to") {
    withFixture { fixture =>
      for {
        mine <- fixture.channel("Mine", NotificationChannelType.Telegram, Set(opened), Set(threshold))
        _ <- fixture.channel("Theirs", NotificationChannelType.Telegram, Set(opened), Set(threshold),
          organizationId = OtherOrganizationId)
        reached <- fixture.route(opened, threshold)
        theirs <- fixture.route(opened, threshold, organizationId = OtherOrganizationId)
      } yield IO {
        assertEquals(reached, Set(mine))
        assertEquals(theirs.size, 1)
        assert(!theirs.contains(mine), "an event reached the channel of another organization")
      }
    }
  }

  test("several channels of one type are several destinations, not one") {
    withFixture { fixture =>
      for {
        first <- fixture.channel("Telegram A", NotificationChannelType.Telegram, Set(opened), Set(threshold))
        second <- fixture.channel("Telegram B", NotificationChannelType.Telegram, Set(opened), Set(threshold))
        third <- fixture.channel("Telegram C", NotificationChannelType.Telegram, Set(opened), Set(threshold))
        mail <- fixture.channel("Mail A", NotificationChannelType.Email, Set(opened), Set(threshold))
        mailToo <- fixture.channel("Mail B", NotificationChannelType.Email, Set(opened), Set(threshold))

        _ <- fixture.run(fixture.recorder(legacy = false).record(List(fixture.opened(threshold))))
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(rows.size, 5)
        assertEquals(rows.flatMap(_.target.channelId).toSet, Set(first, second, third, mail, mailToo))
        assertEquals(rows.map(_.channelType.code).sorted,
          List("EMAIL", "EMAIL", "TELEGRAM", "TELEGRAM", "TELEGRAM"))
        // Every row names the incident it reports and the channel it is addressed to.
        assertEquals(rows.map(_.eventType).distinct, List(opened))
      }
    }
  }

  test("the deployment's own webhook is a separate target, recorded alongside the channels") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        _ <- fixture.run(fixture.recorder(legacy = true).record(List(fixture.opened(threshold))))
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(rows.size, 2)
        assertEquals(rows.map(_.target).toSet, Set[NotificationDeliveryTarget](
          NotificationDeliveryTarget.LegacyWebhook,
          NotificationDeliveryTarget.Managed(channel, NotificationChannelType.Telegram)))
        // The legacy target has no channel row and never will.
        assertEquals(rows.count(_.target.channelId.isEmpty), 1)
      }
    }
  }

  test("the legacy webhook is recorded even when no channel subscribes to the event") {
    withFixture { fixture =>
      for {
        _ <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(resolved), Set(threshold))
        _ <- fixture.run(fixture.recorder(legacy = true).record(List(fixture.opened(threshold))))
        rows <- fixture.deliveryRows
      } yield IO {
        // Subscriptions belong to channels; the environment webhook predates them and takes all.
        assertEquals(rows.map(_.target), List(NotificationDeliveryTarget.LegacyWebhook))
      }
    }
  }

  test("recording the same transition twice adds nothing, per target") {
    withFixture { fixture =>
      for {
        first <- fixture.channel("Telegram A", NotificationChannelType.Telegram,
          Set(opened, resolved), Set(threshold))
        second <- fixture.channel("Telegram B", NotificationChannelType.Telegram,
          Set(opened, resolved), Set(threshold))
        recorder = fixture.recorder(legacy = true)
        _ <- fixture.run(recorder.record(List(fixture.opened(threshold))))
        _ <- fixture.run(recorder.record(List(fixture.opened(threshold))))
        _ <- fixture.run(recorder.record(List(fixture.opened(threshold))))
        rows <- fixture.deliveryRows
        // A different event of the same incident is a different delivery.
        _ <- fixture.run(recorder.record(List(fixture.resolvedTransition(threshold))))
        afterResolved <- fixture.deliveryRows
      } yield IO {
        assertEquals(rows.size, 3)
        assertEquals(rows.flatMap(_.target.channelId).toSet, Set(first, second))
        assertEquals(afterResolved.size, 6)
        assertEquals(afterResolved.map(_.eventType).toSet, Set[NotificationEventType](opened, resolved))
      }
    }
  }

  test("routing asks once and writes in batches, however many channels are reached") {
    withFixture { fixture =>
      val many = (1 to 40).toList
      for {
        _ <- fixture.channel("One", NotificationChannelType.Telegram,
          Set(opened, resolved), Set(threshold, noData))
        single <- fixture.cost(List(fixture.opened(threshold)))
        _ <- many.traverse_(index => fixture.channel(f"Channel $index%03d",
          NotificationChannelType.Email, Set(opened, resolved), Set(threshold, noData)).void)
        _ <- fixture.run(sql"delete from notification_delivery where organization_id = $OrganizationId".update.run)
        crowd <- fixture.cost(List(fixture.opened(threshold)))
        _ <- fixture.run(sql"delete from notification_delivery where organization_id = $OrganizationId".update.run)
        // Several transitions of one kind share a routing key, so they share the question too.
        batch <- fixture.cost(List.fill(25)(fixture.opened(threshold)))
      } yield IO {
        // One question and one insert statement, whether one channel answers or forty-one.
        assertEquals(single, (1, 1, 1))
        assertEquals(crowd, (1, 1, 41))
        assertEquals(batch, (1, 1, 41))
      }
    }
  }

  test("routing reads nothing but the channels: no credential is selected or decrypted") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        routes <- fixture.run(fixture.routing.matching(
          Set(NotificationRoutingKey(OrganizationId, opened, threshold))))
        // The secret of the channel is still exactly where it was, untouched by routing.
        stored <- fixture.run(fixture.channels.findById(OrganizationId, channel))
        rendered = routes.mkString(" ")
      } yield IO {
        assertEquals(routes.map(_.channelId), List(channel))
        assertEquals(routes.map(_.channelType), List(NotificationChannelType.Telegram))
        // A route names a destination and says nothing about how to reach it.
        assert(!rendered.contains("123:abc"), "routing carried a credential")
        assert(!rendered.contains("-100777"), "routing carried the chat of the channel")
        assert(stored.exists(_.secretId != null))
      }
    }
  }

  test("what a channel becomes after a delivery is recorded does not reach back into it") {
    withFixture { fixture =>
      for {
        channel <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        _ <- fixture.run(fixture.recorder(legacy = false).record(List(fixture.opened(threshold))))
        before <- fixture.deliveryRows
        // Switched off, re-subscribed, re-credentialled — the committed intent is untouched.
        _ <- fixture.run(fixture.management.setEnabled(fixture.actor, channel, enabled = false))
        _ <- fixture.run(fixture.management.update(fixture.actor, channel,
          application.notification.UpdateNotificationChannelCommand(
            "Renamed",
            NotificationSubscriptions(Set(resolved), Set(noData)),
            NotificationChannelSettings.Telegram("-100999"),
            Some(NotificationChannelCredential.TelegramBotToken("999:rotated"))
          )))
        after <- fixture.deliveryRows
      } yield IO {
        assertEquals(before.size, 1)
        assertEquals(after.map(_.target), before.map(_.target))
        assertEquals(after.map(_.status), before.map(_.status))
        assertEquals(after.head.target, NotificationDeliveryTarget.Managed(channel,
          NotificationChannelType.Telegram))
      }
    }
  }

  test("a transition and the deliveries it produced commit together or not at all") {
    withFixture { fixture =>
      for {
        _ <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        // The insert fails inside the transaction that routed the event.
        result <- fixture.run(
          fixture.recorder(legacy = false).record(List(fixture.opened(threshold))) *>
            sql"insert into notification_delivery (id) values (null)".update.run.void
        ).attempt
        rows <- fixture.deliveryRows
      } yield IO {
        assert(result.isLeft, "the failing statement did not fail the transaction")
        assertEquals(rows, List.empty[NotificationDelivery])
      }
    }
  }

  test("a delivery cannot name a channel of another organization") {
    withFixture { fixture =>
      for {
        theirs <- fixture.channel("Theirs", NotificationChannelType.Telegram, Set(opened),
          Set(threshold), organizationId = OtherOrganizationId)
        result <- fixture.run(fixture.deliveries.saveAll(List(
          fixture.delivery(NotificationDeliveryTarget.Managed(theirs, NotificationChannelType.Telegram))
        ))).attempt
        rows <- fixture.deliveryRows
      } yield IO {
        assert(result.isLeft, "a delivery borrowed the channel of another organization")
        assertEquals(rows, List.empty[NotificationDelivery])
      }
    }
  }

  test("only the deployment's own webhook may have no channel") {
    withFixture { fixture =>
      for {
        // A managed transport with no channel row is not a legacy target: there is no legacy
        // Telegram and no legacy mail.
        result <- fixture.run(sql"""
          insert into notification_delivery (
            id, organization_id, incident_id, resource_id, monitor_rule_id,
            event_type, reason, channel, notification_channel_id, occurred_at, status,
            attempt_count, next_attempt_at, created_at, updated_at
          ) values (
            ${UUID.randomUUID()}, $OrganizationId, ${fixture.incidentId}, ${fixture.resourceId},
            ${fixture.ruleId}, 'INCIDENT_OPENED', 'THRESHOLD', 'TELEGRAM', null, $Now, 'PENDING',
            0, $Now, $Now, $Now
          )
        """.update.run).attempt
      } yield IO(assert(result.isLeft, "a Telegram delivery was accepted with no channel"))
    }
  }

  /** The blocker: a worker never takes a delivery whose transport it cannot speak. */
  test("the legacy worker sees no managed delivery, and the managed worker sees them all") {
    withFixture { fixture =>
      for {
        telegram <- fixture.channel("Telegram Ops", NotificationChannelType.Telegram,
          Set(opened), Set(threshold))
        mail <- fixture.channel("Email Ops", NotificationChannelType.Email, Set(opened), Set(threshold))
        hook <- fixture.channel("Webhook Ops", NotificationChannelType.Webhook, Set(opened), Set(threshold))
        _ <- fixture.run(fixture.recorder(legacy = true).record(List(fixture.opened(threshold))))
        _ <- fixture.makeDue

        legacyClaim <- fixture.claimed(NotificationDeliveryScope.Legacy)
        managedClaim <- fixture.claimed(NotificationDeliveryScope.Managed)
        rows <- fixture.deliveryRows
      } yield IO {
        assertEquals(rows.size, 4)
        // The legacy worker reaches its own delivery and no other.
        assertEquals(legacyClaim.map(_.target), List(NotificationDeliveryTarget.LegacyWebhook))
        // A managed delivery addressed to a webhook is still not the legacy webhook's to send.
        assertEquals(managedClaim.flatMap(_.target.channelId).toSet, Set(telegram, mail, hook))
        assertEquals(managedClaim.map(_.channelType.code).sorted,
          List("EMAIL", "TELEGRAM", "WEBHOOK"))

        // Nothing was sent and nothing is held: until a managed worker exists, these wait.
        assertEquals(rows.map(_.status.code).distinct, List("PENDING"))
        assert(rows.forall(_.claimedBy.isEmpty), "a delivery was left leased")
        assert(rows.forall(_.claimedUntil.isEmpty))
      }
    }
  }

  private def withFixture(body: RoutingFixture => IO[IO[Unit]]): Unit =
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new RoutingFixture(new DoobieTransactionRunner(xa))
      fixture.setUp *> body(fixture).flatten.guarantee(fixture.reset)
    }.unsafeRunSync()

  /** A clock far enough ahead that nothing this spec records is ever due for a worker. */
  private object NotAnyTimeSoon extends application.port.TimeProvider[ConnectionIO] {
    private val moment = Instant.parse("2099-01-01T00:00:00Z")
    override def now: ConnectionIO[Instant] = cats.effect.Sync[ConnectionIO].pure(moment)
  }

  /** Counts the questions routing asks, so an N+1 shows up as a number rather than a hunch. */
  private final class CountingRoutingQuery(delegate: NotificationChannelRoutingQuery[ConnectionIO])
    extends NotificationChannelRoutingQuery[ConnectionIO] {
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    def questions: Int = calls.get()
    override def matching(keys: Set[NotificationRoutingKey]): ConnectionIO[List[NotificationRoute]] =
      cats.effect.Sync[ConnectionIO].delay(calls.incrementAndGet()) *> delegate.matching(keys)
  }

  /** Counts the write operations the outbox performs, which is what batching is about. */
  private final class CountingDeliveryRepository(delegate: NotificationDeliveryRepository[ConnectionIO])
    extends NotificationDeliveryRepository[ConnectionIO] {
    private val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    def writes: Int = calls.get()
    override def saveAll(rows: List[NotificationDelivery]): ConnectionIO[Unit] =
      cats.effect.Sync[ConnectionIO].delay(calls.incrementAndGet()) *> delegate.saveAll(rows)
    override def findById(organizationId: UUID, id: UUID): ConnectionIO[Option[NotificationDelivery]] =
      delegate.findById(organizationId, id)
    override def claimPending(
      scope: NotificationDeliveryScope,
      claimedBy: UUID,
      limit: Int,
      leaseSeconds: Long
    ): ConnectionIO[List[NotificationDelivery]] =
      delegate.claimPending(scope, claimedBy, limit, leaseSeconds)
    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): ConnectionIO[Boolean] =
      delegate.markSent(organizationId, id, claimedBy, sentAt)
    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): ConnectionIO[Boolean] =
      delegate.reschedule(organizationId, id, claimedBy, attemptCount, nextAttemptAt, errorCode, updatedAt)
    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): ConnectionIO[Boolean] =
      delegate.markDead(organizationId, id, claimedBy, attemptCount, errorCode, updatedAt)
  }

  private final class RoutingFixture(runner: DoobieTransactionRunner) {
    private val key = Base64.getEncoder.encodeToString(Array.tabulate[Byte](32)(_.toByte))
    private val cipher = NotificationChannelCipher.fromConfig(
      SecretEncryptionConfig.fromEnvironment(Map("INFRADESK_SECRET_MASTER_KEY_BASE64" -> key))
        .toOption.get)

    val incidentId: UUID = UUID.randomUUID()
    val resourceId: UUID = UUID.randomUUID()
    val ruleId: UUID = UUID.randomUUID()
    val workerId: UUID = UUID.randomUUID()
    private val environmentId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()
    private val connectionId = UUID.randomUUID()
    private val code = s"routing-${UUID.randomUUID().toString.take(8)}"

    val deliveries: NotificationDeliveryRepository[ConnectionIO] =
      new PostgresNotificationDeliveryRepository
    val channels = new PostgresNotificationChannelRepository
    val routing: NotificationChannelRoutingQuery[ConnectionIO] =
      new PostgresNotificationChannelRoutingQuery

    val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, OrganizationId)

    val management: NotificationChannelManagement[ConnectionIO] =
      new NotificationChannelManagement[ConnectionIO](channels,
        new PostgresNotificationChannelSecretRepository, new ConnectionIOIdGenerator,
        new ConnectionIOTimeProvider, cipher,
        new application.audit.AuditRecorder[ConnectionIO](new PostgresAuditEventRepository,
          new ConnectionIOIdGenerator, new ConnectionIOTimeProvider))

    /** Records with a clock far in the future, so the rows this spec writes are never due and no
      * worker of another suite can lease them while this one is asserting about them. The claim
      * path has no tenant of its own — a worker serves every organization — so the only way for
      * suites sharing a database to stay out of each other's way is to not be due.
      */
    def recorder(legacy: Boolean): RecordNotificationDeliveries[ConnectionIO] =
      new RecordNotificationDeliveries[ConnectionIO](deliveries, routing,
        new ConnectionIOIdGenerator, NotAnyTimeSoon,
        if (legacy) List(NotificationDeliveryTarget.LegacyWebhook) else Nil)

    /** Brings this spec's own deliveries forward, for the tests that are about claiming. */
    def makeDue: IO[Unit] = run(sql"""
      update notification_delivery set next_attempt_at = current_timestamp
       where organization_id = $OrganizationId
    """.update.run.void)

    /** What a worker of this scope would reach, of this organization's deliveries.
      *
      * The claim runs in a transaction that is rolled back, so the rows of other suites that it
      * touched on the way are released untouched, and this spec's own rows stay as they were.
      */
    def claimed(scope: NotificationDeliveryScope): IO[List[NotificationDelivery]] = {
      val captured = new java.util.concurrent.atomic.AtomicReference(List.empty[NotificationDelivery])
      // The error is raised inside the transaction and caught outside it: caught inside, doobie
      // would commit the very lease this is trying not to take.
      run(for {
        rows <- deliveries.claimPending(scope, workerId, 100, 60)
        _ <- cats.effect.Sync[ConnectionIO].delay(
          captured.set(rows.filter(_.organizationId == OrganizationId)))
        _ <- cats.effect.Sync[ConnectionIO].raiseError[Unit](
          new IllegalStateException("rolling the claim back"))
      } yield ()).attempt *> IO(captured.get())
    }

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    /** A transition as the evaluator produces one, pointing at this fixture's incident. */
    def opened(reason: IncidentReason): MonitorTransition =
      MonitorTransition.Opened(OrganizationId, resourceId, ruleId, incidentId, reason, Now)

    def resolvedTransition(reason: IncidentReason): MonitorTransition =
      MonitorTransition.Resolved(OrganizationId, resourceId, ruleId, incidentId, reason, Now)

    def delivery(target: NotificationDeliveryTarget): NotificationDelivery =
      NotificationDelivery(UUID.randomUUID(), OrganizationId, incidentId, resourceId, ruleId,
        NotificationRoutingIntegrationSpec.this.opened, threshold, target, Now,
        domain.notification.NotificationDeliveryStatus.Pending,
        0, Now, None, None, None, None, Now, Now)

    def channel(
      name: String,
      channelType: NotificationChannelType,
      events: Set[NotificationEventType],
      reasons: Set[IncidentReason],
      organizationId: UUID = OrganizationId
    ): IO[UUID] = {
      val settings = channelType match {
        case NotificationChannelType.Webhook => NotificationChannelSettings.Webhook
        case NotificationChannelType.Telegram => NotificationChannelSettings.Telegram("-100777")
        case NotificationChannelType.Email => NotificationChannelSettings.Email(
          "smtp.example.test", 587, domain.notification.EmailSecurity.StartTls,
          "alerts@example.test", "alerts@example.test", List("admin@example.test"))
      }
      val credential = channelType match {
        case NotificationChannelType.Webhook =>
          NotificationChannelCredential.WebhookUrl("https://hooks.example.test/x")
        case NotificationChannelType.Telegram =>
          NotificationChannelCredential.TelegramBotToken("123:abc")
        case NotificationChannelType.Email => NotificationChannelCredential.EmailPassword("pw")
      }
      run(management.create(ActorContext(AuthorizationFixtures.ActorUserId, organizationId),
        CreateNotificationChannelCommand(name, enabled = true,
          NotificationSubscriptions(events, reasons), settings, Some(credential)))).map(_.id)
    }

    /** Which channels an event of this kind reaches, by identifier. */
    def route(
      eventType: NotificationEventType,
      reason: IncidentReason,
      organizationId: UUID = OrganizationId
    ): IO[Set[UUID]] =
      run(routing.matching(Set(NotificationRoutingKey(organizationId, eventType, reason))))
        .map(_.map(_.channelId).toSet)

    /** What one recording costs: routing questions, delivery writes, and rows produced. */
    def cost(transitions: List[MonitorTransition]): IO[(Int, Int, Int)] = {
      val countedRouting = new CountingRoutingQuery(routing)
      val countedDeliveries = new CountingDeliveryRepository(deliveries)
      val counting = new RecordNotificationDeliveries[ConnectionIO](countedDeliveries,
        countedRouting, new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, Nil)
      run(counting.record(transitions)) *> deliveryRows.map(rows =>
        (countedRouting.questions, countedDeliveries.writes, rows.size))
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
          ($OrganizationId, ${s"routing-$code"}, 'Routing'),
          ($OtherOrganizationId, ${s"routing-other-$code"}, 'Routing other')
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
        values ($projectId, $OrganizationId, $code, 'Routing') on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into environment (id, organization_id, project_id, code, name, kind)
        values ($environmentId, $OrganizationId, $projectId, $code, 'Routing', 'TEST')
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into connection (id, organization_id, scope_type, connector_type, code, name)
        values ($connectionId, $OrganizationId, 'ORGANIZATION', 'SSH', $code, 'Routing')
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into resource (id, organization_id, environment_id, resource_type_id, code, name,
                              is_active)
        values ($resourceId, $OrganizationId, $environmentId,
                '10000000-0000-0000-0000-000000000001', $code, 'Routing', true)
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into monitor_rule (id, organization_id, resource_id, metric_code, operator,
                                  threshold, for_seconds, no_data_seconds, enabled,
                                  created_at, updated_at)
        values ($ruleId, $OrganizationId, $resourceId, 'CPU_USAGE_PERCENT', 'GREATER_THAN', 90, 0, 900, true,
                $Now, $Now)
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
