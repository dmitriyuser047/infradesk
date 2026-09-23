package ru.bitec.app.ops
package application.scheduler

import application.port.{
  ConnectionScheduleRepository,
  ConnectionSyncResult,
  ConnectionSyncRunner,
  ConnectionSynchronizer,
  ResourceConnectorFailure,
  ResourceConnectorFailureCode,
  TimeProvider,
  TransactionRunner
}
import application.monitor.MonitorRuleEvaluator
import application.connector.{ConnectionSyncExecutionFailed, RunConnectionSync, SyncAlreadyRunning}
import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.ConnectionSchedule
import domain.resource.Resource
import munit.FunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final class SyncSchedulerSpec extends FunSuite {
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.scheduler")

  test("does not invoke synchronizer for a schedule that is not due") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val fixture = buildFixture(List(schedule(nextRunAt = now.plusSeconds(1))), List(now))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List.empty)
    assertEquals(fixture.repository.scheduledNext, List.empty)
  }

  test("invokes a due connection outside transactions and schedules its next run after success") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val finishedAt = now.plusSeconds(3)
    val due = schedule(nextRunAt = now, intervalSeconds = 600, consecutiveFailures = 2)
    val fixture = buildFixture(List(due), List(now, now.plusSeconds(2), finishedAt))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(due.connectionId))
    assertEquals(
      fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(600), 0L))
    )
    assertEquals(fixture.synchronizer.calledInsideTransaction, false)
    assertEquals(fixture.evaluator.calls, 1)
    assertEquals(fixture.evaluator.calledOutsideTransaction, false)
  }

  test("schedules next run after a failed sync") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val finishedAt = now.plusSeconds(5)
    val due = schedule(nextRunAt = now, intervalSeconds = 300)
    val fixture = buildFixture(List(due), List(now, finishedAt), failingConnectionIds = Set(due.connectionId))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(due.connectionId))
    assertEquals(
      fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(300), 1L))
    )
    assertEquals(fixture.evaluator.calls, 0)
  }

  test("second failure waits 15 minutes and later failures wait 30 minutes") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    for ((oldFailures, delay) <- List(1L -> 900L, 2L -> 1800L, 4L -> 1800L, 20L -> 1800L)) {
      val due = schedule(nextRunAt = now, intervalSeconds = 300, consecutiveFailures = oldFailures)
      val fixture = buildFixture(List(due), List(now, now.plusSeconds(5)), failingConnectionIds = Set(due.connectionId))
      fixture.scheduler.tick(limit = 10).unsafeRunSync()
      assertEquals(fixture.repository.scheduledNext,
        List((due.organizationId, due.connectionId, now.plusSeconds(5 + delay), oldFailures + 1)))
    }
  }

  test("backoff never retries sooner than the configured interval and count saturates") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    for (oldFailures <- List(0L, 1L, 2L, Long.MaxValue)) {
      val due = schedule(nextRunAt = now, intervalSeconds = 3600, consecutiveFailures = oldFailures)
      val fixture = buildFixture(List(due), List(now, now), failingConnectionIds = Set(due.connectionId))
      fixture.scheduler.tick(limit = 10).unsafeRunSync()
      assertEquals(fixture.repository.scheduledNext,
        List((due.organizationId, due.connectionId, now.plusSeconds(3600),
          SyncBackoffPolicy.nextFailureCount(oldFailures))))
    }
  }

  test("already-running attempt keeps failure count and schedules normal interval") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val due = schedule(nextRunAt = now, intervalSeconds = 300, consecutiveFailures = 2)
    val fixture = buildFixture(List(due), List(now, now),
      failures = Map(due.connectionId -> SyncAlreadyRunning()))
    fixture.scheduler.tick(limit = 10).unsafeRunSync()
    assertEquals(fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, now.plusSeconds(300), 2L)))
  }

  test("typed SSH failure still increments ordinary scheduled backoff") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val due = schedule(nextRunAt = now, intervalSeconds = 300, consecutiveFailures = 1)
    val failure = ResourceConnectorFailure(ResourceConnectorFailureCode.SshConnectTimeout,
      "SSH connection timed out", new IllegalStateException("raw internal detail"))
    val fixture = buildFixture(List(due), List(now, now), failures = Map(due.connectionId -> failure))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, now.plusSeconds(900), 2L)))
  }

  test("failed scheduled sync includes session ID without exposing connector cause") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val due = schedule(nextRunAt = now)
    val sessionId = UUID.randomUUID()
    val failure = ResourceConnectorFailure(ResourceConnectorFailureCode.SshConnectTimeout,
      "SSH connection timed out", new IllegalStateException("secret remote detail"))
    val recordingLogger = new RecordingLogger
      val fixture = buildFixture(List(due), List(now, now),
        failures = Map(due.connectionId -> ConnectionSyncExecutionFailed(sessionId, failure)),
        overrideLogger = recordingLogger)
      fixture.scheduler.tick(10).unsafeRunSync()
      val failed = recordingLogger.errors.find(_._1.startsWith("scheduler.sync.failed")).get
      assert(failed._1.contains(s"syncSessionId=$sessionId"))
      assert(failed._1.contains("errorCode=SSH_CONNECT_TIMEOUT"))
      assertEquals(failed._2, None)
      assert(!failed._1.contains("secret remote detail"))
  }

  private final class RecordingLogger extends Logger[IO] {
    var errors: List[(String, Option[Throwable])] = Nil
    override def error(message: => String): IO[Unit] = IO { errors = errors :+ (message -> None) }
    override def error(t: Throwable)(message: => String): IO[Unit] = IO { errors = errors :+ (message -> Some(t)) }
    override def warn(message: => String): IO[Unit] = IO.unit
    override def info(message: => String): IO[Unit] = IO.unit
    override def debug(message: => String): IO[Unit] = IO.unit
    override def trace(message: => String): IO[Unit] = IO.unit
    override def warn(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def info(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def debug(t: Throwable)(message: => String): IO[Unit] = IO.unit
    override def trace(t: Throwable)(message: => String): IO[Unit] = IO.unit
  }

  test("continues with later schedules when one sync fails") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val first = schedule(connectionId = UUID.fromString("60000000-0000-0000-0000-000000000010"), nextRunAt = now)
    val second = schedule(connectionId = UUID.fromString("60000000-0000-0000-0000-000000000011"), nextRunAt = now)
    val fixture = buildFixture(
      List(first, second),
      List(now, now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3)),
      failingConnectionIds = Set(first.connectionId)
    )

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(first.connectionId, second.connectionId))
    assertEquals(fixture.repository.scheduledNext.map(_._2), List(first.connectionId, second.connectionId))
  }

  test("a failed tick does not stop the next polling iteration") {
    val observed = (for {
      secondTick <- Deferred[IO, Unit]
      repository = new ConnectionScheduleRepository[IO] {
        private var calls = 0
        def save(schedule: ConnectionSchedule): IO[Unit] = IO.unit
        def findByConnection(organizationId: UUID, connectionId: UUID): IO[Option[ConnectionSchedule]] = IO.pure(None)
        def findDue(now: Instant, limit: Int): IO[List[ConnectionSchedule]] = IO.defer {
          calls += 1
          if (calls == 1) IO.raiseError(new IllegalStateException("first tick failed"))
          else secondTick.complete(()).as(List.empty[ConnectionSchedule])
        }
        def updateAfterRun(organizationId: UUID, connectionId: UUID, nextRunAt: Instant,
                           consecutiveFailures: Long): IO[Unit] = IO.unit
      }
      runner = new ConnectionSyncRunner[IO] {
        def execute(organizationId: UUID, connectionId: UUID): IO[ConnectionSyncResult] =
          IO.raiseError(new IllegalStateException("should not sync"))
      }
      transactionRunner = new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }
      clock = new TimeProvider[IO] { def now: IO[Instant] = IO.pure(Instant.parse("2026-09-21T10:00:00Z")) }
      scheduler = new SyncScheduler[IO, IO](repository, runner, transactionRunner, clock, logger)
      fiber <- scheduler.run(1.millis, 10).start
      _ <- secondTick.get.timeout(3.seconds).guarantee(fiber.cancel)
    } yield ()).unsafeRunSync()
    assertEquals(observed, ())
  }

  test("sync and updateAfterRun failures are both logged without blocking later schedules") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val first = schedule(connectionId = UUID.fromString("60000000-0000-0000-0000-000000000020"), nextRunAt = now)
    val second = schedule(connectionId = UUID.fromString("60000000-0000-0000-0000-000000000021"), nextRunAt = now)
    val recordingLogger = new RecordingLogger
      val fixture = buildFixture(List(first, second), List.fill(5)(now),
        failingConnectionIds = Set(first.connectionId), failingUpdateIds = Set(first.connectionId),
        overrideLogger = recordingLogger)
      fixture.scheduler.tick(10).unsafeRunSync()
      assertEquals(fixture.synchronizer.calls, List(first.connectionId, second.connectionId))
      assertEquals(fixture.repository.scheduledNext.map(_._2), List(second.connectionId))
      assert(recordingLogger.errors.exists(_._1
        .contains(s"scheduler.sync.failed organizationId=$OrganizationId connectionId=${first.connectionId}")))
      assert(recordingLogger.errors.exists(_._1
        .contains(s"scheduler.schedule.update.failed organizationId=$OrganizationId connectionId=${first.connectionId}")))
  }

  test("schedules next run when evaluator fails after a successful sync") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val finishedAt = now.plusSeconds(3)
    val due = schedule(nextRunAt = now)
    val fixture = buildFixture(
      List(due),
      List(now, now.plusSeconds(2), finishedAt),
      failingEvaluator = true
    )

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(due.connectionId))
    assertEquals(fixture.evaluator.calls, 1)
    assertEquals(
      fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(due.intervalSeconds), 0L))
    )
  }

  private def buildFixture(
                       schedules: List[ConnectionSchedule],
                       times: List[Instant],
                       failingConnectionIds: Set[UUID] = Set.empty,
                       failures: Map[UUID, Throwable] = Map.empty,
                       failingEvaluator: Boolean = false,
                       failingUpdateIds: Set[UUID] = Set.empty,
                       overrideLogger: Logger[IO] = logger
  ): SchedulerFixture = {
    val transactionRunner = new RecordingTransactionRunner
    val repository = new RecordingConnectionScheduleRepository(schedules, failingUpdateIds)
    val synchronizer = new RecordingConnectionSynchronizer(transactionRunner,
      failingConnectionIds.map(_ -> new IllegalStateException("Simulated sync failure")).toMap ++ failures)
    val evaluator = new RecordingMonitorRuleEvaluator(transactionRunner, failingEvaluator)
    val timeProvider = new SequenceTimeProvider(times)
    val runConnectionSync = new RunConnectionSync[IO, IO](
      synchronizer, evaluator, transactionRunner, timeProvider, overrideLogger
    )
    val scheduler = new SyncScheduler[IO, IO](
      repository,
      runConnectionSync,
      transactionRunner,
      timeProvider,
      overrideLogger
    )

    SchedulerFixture(scheduler, repository, synchronizer, evaluator)
  }

  private def schedule(
                        connectionId: UUID = UUID.fromString("60000000-0000-0000-0000-000000000003"),
                        nextRunAt: Instant,
                        intervalSeconds: Long = 60,
                        consecutiveFailures: Long = 0L
                      ): ConnectionSchedule =
    ConnectionSchedule(
      organizationId = OrganizationId,
      connectionId = connectionId,
      enabled = true,
      intervalSeconds = intervalSeconds,
      nextRunAt = nextRunAt,
      consecutiveFailures = consecutiveFailures
    )

  private final case class SchedulerFixture(
                                             scheduler: SyncScheduler[IO, IO],
                                             repository: RecordingConnectionScheduleRepository,
                                             synchronizer: RecordingConnectionSynchronizer,
                                             evaluator: RecordingMonitorRuleEvaluator
                                           )

  private final class RecordingConnectionScheduleRepository(
                                                             schedules: List[ConnectionSchedule],
                                                             failingUpdateIds: Set[UUID]
                                                           ) extends ConnectionScheduleRepository[IO] {
    override def save(schedule: ConnectionSchedule): IO[Unit] = IO.unit
    var scheduledNext: List[(UUID, UUID, Instant, Long)] = List.empty

    override def findByConnection(
                                   organizationId: UUID,
                                   connectionId: UUID
                                 ): IO[Option[ConnectionSchedule]] =
      IO.pure(schedules.find(schedule =>
        schedule.organizationId == organizationId && schedule.connectionId == connectionId
      ))

    override def findDue(now: Instant, limit: Int): IO[List[ConnectionSchedule]] =
      IO.pure(schedules.filter(schedule => schedule.enabled && !schedule.nextRunAt.isAfter(now)).take(limit))

    override def updateAfterRun(
                               organizationId: UUID,
                               connectionId: UUID,
                               nextRunAt: Instant,
                               consecutiveFailures: Long
                             ): IO[Unit] =
      IO {
        if (failingUpdateIds.contains(connectionId)) throw new IllegalStateException("update failed")
        scheduledNext = scheduledNext :+ (organizationId, connectionId, nextRunAt, consecutiveFailures)
      }
  }

  private final class RecordingConnectionSynchronizer(
                                                        transactionRunner: RecordingTransactionRunner,
                                                        failures: Map[UUID, Throwable]
                                                      ) extends ConnectionSynchronizer[IO] {
    var calls: List[UUID] = List.empty
    var calledInsideTransaction = false

    override def execute(organizationId: UUID, connectionId: UUID): IO[ConnectionSyncResult] =
      IO {
        calls = calls :+ connectionId
        calledInsideTransaction ||= transactionRunner.inTransaction
      } *> {
        failures.get(connectionId) match {
          case Some(error) => IO.raiseError(error)
          case None => IO.pure(ConnectionSyncResult(UUID.randomUUID(), List.empty))
        }
      }
  }

  private final class RecordingTransactionRunner extends TransactionRunner[IO, IO] {
    var inTransaction = false

    override def run[A](program: IO[A]): IO[A] =
      IO {
        inTransaction = true
      } *> program.guarantee(IO {
        inTransaction = false
      })
  }

  private final class RecordingMonitorRuleEvaluator(
                                                     transactionRunner: RecordingTransactionRunner,
                                                     failing: Boolean
                                                   ) extends MonitorRuleEvaluator[IO] {
    var calls = 0
    var calledOutsideTransaction = false

    override def execute(resources: List[Resource], evaluatedAt: Instant): IO[List[application.monitor.MonitorTransition]] =
      IO {
        calls += 1
        calledOutsideTransaction ||= !transactionRunner.inTransaction
      } *> {
        if (failing)
          IO.raiseError(new IllegalStateException("Simulated evaluator failure"))
        else
          IO.pure(List.empty)
      }
  }

  private final class SequenceTimeProvider(times: List[Instant]) extends TimeProvider[IO] {
    private var remaining = times

    override def now: IO[Instant] =
      IO {
        remaining match {
          case value :: tail =>
            remaining = tail
            value
          case Nil =>
            throw new IllegalStateException("No time values remain")
        }
      }
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
}
