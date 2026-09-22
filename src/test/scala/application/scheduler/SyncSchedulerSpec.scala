package ru.bitec.app.ops
package application.scheduler

import application.port.{
  ConnectionScheduleRepository,
  ConnectionSynchronizer,
  TimeProvider,
  TransactionRunner
}
import application.monitor.MonitorRuleEvaluator
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.ConnectionSchedule
import domain.resource.Resource
import munit.FunSuite

import java.time.Instant
import java.util.UUID

final class SyncSchedulerSpec extends FunSuite {

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
    val due = schedule(nextRunAt = now)
    val fixture = buildFixture(List(due), List(now, now.plusSeconds(2), finishedAt))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(due.connectionId))
    assertEquals(
      fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(due.intervalSeconds)))
    )
    assertEquals(fixture.synchronizer.calledInsideTransaction, false)
    assertEquals(fixture.evaluator.calls, 1)
    assertEquals(fixture.evaluator.calledOutsideTransaction, false)
  }

  test("schedules next run after a failed sync") {
    val now = Instant.parse("2026-09-21T10:00:00Z")
    val finishedAt = now.plusSeconds(5)
    val due = schedule(nextRunAt = now)
    val fixture = buildFixture(List(due), List(now, finishedAt), failingConnectionIds = Set(due.connectionId))

    fixture.scheduler.tick(limit = 10).unsafeRunSync()

    assertEquals(fixture.synchronizer.calls, List(due.connectionId))
    assertEquals(
      fixture.repository.scheduledNext,
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(due.intervalSeconds)))
    )
    assertEquals(fixture.evaluator.calls, 0)
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
      List((due.organizationId, due.connectionId, finishedAt.plusSeconds(due.intervalSeconds)))
    )
  }

  private def buildFixture(
                       schedules: List[ConnectionSchedule],
                       times: List[Instant],
                       failingConnectionIds: Set[UUID] = Set.empty,
                       failingEvaluator: Boolean = false
  ): SchedulerFixture = {
    val transactionRunner = new RecordingTransactionRunner
    val repository = new RecordingConnectionScheduleRepository(schedules)
    val synchronizer = new RecordingConnectionSynchronizer(transactionRunner, failingConnectionIds)
    val evaluator = new RecordingMonitorRuleEvaluator(transactionRunner, failingEvaluator)
    val scheduler = new SyncScheduler[IO, IO](
      repository,
      synchronizer,
      transactionRunner,
      new SequenceTimeProvider(times),
      evaluator
    )

    SchedulerFixture(scheduler, repository, synchronizer, evaluator)
  }

  private def schedule(
                        connectionId: UUID = UUID.fromString("60000000-0000-0000-0000-000000000003"),
                        nextRunAt: Instant
                      ): ConnectionSchedule =
    ConnectionSchedule(
      organizationId = OrganizationId,
      connectionId = connectionId,
      enabled = true,
      intervalSeconds = 60,
      nextRunAt = nextRunAt
    )

  private final case class SchedulerFixture(
                                             scheduler: SyncScheduler[IO, IO],
                                             repository: RecordingConnectionScheduleRepository,
                                             synchronizer: RecordingConnectionSynchronizer,
                                             evaluator: RecordingMonitorRuleEvaluator
                                           )

  private final class RecordingConnectionScheduleRepository(
                                                             schedules: List[ConnectionSchedule]
                                                           ) extends ConnectionScheduleRepository[IO] {
    override def save(schedule: ConnectionSchedule): IO[Unit] = IO.unit
    var scheduledNext: List[(UUID, UUID, Instant)] = List.empty

    override def findByConnection(
                                   organizationId: UUID,
                                   connectionId: UUID
                                 ): IO[Option[ConnectionSchedule]] =
      IO.pure(schedules.find(schedule =>
        schedule.organizationId == organizationId && schedule.connectionId == connectionId
      ))

    override def findDue(now: Instant, limit: Int): IO[List[ConnectionSchedule]] =
      IO.pure(schedules.filter(schedule => schedule.enabled && !schedule.nextRunAt.isAfter(now)).take(limit))

    override def scheduleNext(
                               organizationId: UUID,
                               connectionId: UUID,
                               nextRunAt: Instant
                             ): IO[Unit] =
      IO {
        scheduledNext = scheduledNext :+ (organizationId, connectionId, nextRunAt)
      }
  }

  private final class RecordingConnectionSynchronizer(
                                                        transactionRunner: RecordingTransactionRunner,
                                                        failingConnectionIds: Set[UUID]
                                                      ) extends ConnectionSynchronizer[IO] {
    var calls: List[UUID] = List.empty
    var calledInsideTransaction = false

    override def execute(organizationId: UUID, connectionId: UUID): IO[List[Resource]] =
      IO {
        calls = calls :+ connectionId
        calledInsideTransaction ||= transactionRunner.inTransaction
      } *> {
        if (failingConnectionIds.contains(connectionId))
          IO.raiseError(new IllegalStateException(s"Simulated sync failure for $connectionId"))
        else
          IO.pure(List.empty)
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

    override def execute(resources: List[Resource], evaluatedAt: Instant): IO[Unit] =
      IO {
        calls += 1
        calledOutsideTransaction ||= !transactionRunner.inTransaction
      } *> {
        if (failing)
          IO.raiseError(new IllegalStateException("Simulated evaluator failure"))
        else
          IO.unit
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
