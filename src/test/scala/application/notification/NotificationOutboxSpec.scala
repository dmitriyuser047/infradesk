package ru.bitec.app.ops
package application.notification

import application.monitor.{MonitorRuleEvaluator, MonitorTransition}
import application.port.{IdGenerator, NotificationDeliveryRepository, NotificationSendResult, NotificationSender, TimeProvider, TransactionRunner}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.IncidentReason
import domain.notification.{NotificationChannel, NotificationDelivery, NotificationDeliveryStatus, NotificationEventType}
import munit.FunSuite
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final class NotificationOutboxSpec extends FunSuite {

  // -------------------------------------------------------------------------------------------
  // Recording
  // -------------------------------------------------------------------------------------------

  test("an opened transition becomes a pending webhook delivery") {
    val repository = new RecordingRepository
    val recorded = recorder(repository).record(List(opened)).unsafeRunSync()

    val delivery = repository.saved.head
    assertEquals(recorded, ())
    assertEquals(repository.saved.size, 1)
    assertEquals(delivery.eventType, NotificationEventType.IncidentOpened)
    assertEquals(delivery.reason, IncidentReason.ThresholdViolation)
    assertEquals(delivery.channel, NotificationChannel.Webhook)
    assertEquals(delivery.status, NotificationDeliveryStatus.Pending)
    assertEquals(delivery.attemptCount, 0L)
    // The event reports when the incident changed, the row remembers when it was written.
    assertEquals(delivery.occurredAt, EvaluatedAt)
    assertEquals(delivery.createdAt, Now)
    assertEquals(delivery.nextAttemptAt, Now)
    assertEquals(delivery.claimedBy, None)
    assertEquals(delivery.sentAt, None)
    assertEquals(delivery.incidentId, IncidentId)
  }

  test("a resolved transition becomes its own delivery") {
    val repository = new RecordingRepository
    recorder(repository).record(List(resolved)).unsafeRunSync()

    assertEquals(repository.saved.map(_.eventType), List(NotificationEventType.IncidentResolved))
    assertEquals(repository.saved.map(_.reason), List(IncidentReason.NoData))
  }

  test("an evaluation that swaps the reason records both events in one batch") {
    val repository = new RecordingRepository
    recorder(repository).record(List(resolved, opened)).unsafeRunSync()

    assertEquals(repository.batches.size, 1)
    assertEquals(repository.saved.map(delivery => (delivery.eventType.code, delivery.reason.code)), List(
      ("INCIDENT_RESOLVED", "NO_DATA"),
      ("INCIDENT_OPENED", "THRESHOLD")
    ))
  }

  test("without a configured channel nothing is recorded at all") {
    val repository = new RecordingRepository
    new RecordNotificationDeliveries[IO](repository, new SequenceIdGenerator, new FixedTimeProvider, Nil)
      .record(List(opened, resolved)).unsafeRunSync()

    assertEquals(repository.batches, List.empty)
  }

  test("the recording evaluator returns the transitions it recorded") {
    val repository = new RecordingRepository
    val evaluator = new NotificationRecordingMonitorRuleEvaluator[IO](
      new FixedEvaluator(List(resolved, opened)), recorder(repository)
    )

    val transitions = evaluator.execute(OrganizationId, ConnectionId, EvaluatedAt).unsafeRunSync()

    assertEquals(transitions, List(resolved, opened))
    assertEquals(repository.saved.map(_.eventType.code), List("INCIDENT_RESOLVED", "INCIDENT_OPENED"))
  }

  // -------------------------------------------------------------------------------------------
  // Dispatching
  // -------------------------------------------------------------------------------------------

  test("a delivered event is marked sent and counted as an attempt") {
    val repository = new DispatchRepository(List(pending()))
    dispatcher(repository, new FixedSender(NotificationSendResult.Sent)).tick(10).unsafeRunSync()

    // A wave is never larger than maxConcurrency; the second claim finds the queue drained.
    assertEquals(repository.claims, List((DispatcherId, 1, 60L), (DispatcherId, 1, 60L)))
    assertEquals(repository.sent, List((DeliveryId, DispatcherId, Now)))
    assertEquals(repository.rescheduled, List.empty)
    assertEquals(repository.dead, List.empty)
  }

  test("a retryable failure reschedules with the backoff and keeps the error code") {
    val repository = new DispatchRepository(List(pending(attemptCount = 2)))
    dispatcher(repository, new FixedSender(NotificationSendResult.RetryableFailure("HTTP_500")))
      .tick(10).unsafeRunSync()

    assertEquals(repository.rescheduled, List(
      (DeliveryId, DispatcherId, 3L, Now.plusSeconds(120), "HTTP_500")
    ))
    assertEquals(repository.sent, List.empty)
    assertEquals(repository.dead, List.empty)
  }

  test("a permanent failure gives up immediately") {
    val repository = new DispatchRepository(List(pending()))
    dispatcher(repository, new FixedSender(NotificationSendResult.PermanentFailure("HTTP_404")))
      .tick(10).unsafeRunSync()

    assertEquals(repository.dead, List((DeliveryId, DispatcherId, 1L, "HTTP_404")))
    assertEquals(repository.rescheduled, List.empty)
  }

  test("the last allowed attempt gives up instead of rescheduling") {
    val lastAttempt = new DispatchRepository(List(pending(attemptCount = 2)))
    val beforeLast = new DispatchRepository(List(pending(attemptCount = 1)))
    val failure = new FixedSender(NotificationSendResult.RetryableFailure("HTTP_503"))

    dispatcher(lastAttempt, failure, maxAttempts = 3).tick(10).unsafeRunSync()
    dispatcher(beforeLast, failure, maxAttempts = 3).tick(10).unsafeRunSync()

    assertEquals(lastAttempt.dead, List((DeliveryId, DispatcherId, 3L, "HTTP_503")))
    assertEquals(lastAttempt.rescheduled, List.empty)
    assertEquals(beforeLast.dead, List.empty)
    assertEquals(beforeLast.rescheduled.map(_._3), List(2L))
  }

  test("a lost claim changes nothing and does not fail the tick") {
    val repository = new DispatchRepository(List(pending()), fenced = false)

    dispatcher(repository, new FixedSender(NotificationSendResult.Sent)).tick(10).unsafeRunSync()

    assertEquals(repository.sent.size, 1)
  }

  test("an empty claim does not call the sender") {
    val repository = new DispatchRepository(List.empty)
    val sender = new FixedSender(NotificationSendResult.Sent)

    dispatcher(repository, sender).tick(10).unsafeRunSync()

    assertEquals(sender.calls, 0)
    assertEquals(repository.claims.size, 1)
  }

  test("a batch larger than the concurrency limit is claimed wave by wave") {
    val deliveries = (1 to 5).toList.map(index => pending(id = deliveryId(index)))
    val repository = new DispatchRepository(deliveries)
    val sender = new FixedSender(NotificationSendResult.Sent)

    dispatcher(repository, sender, maxConcurrency = 2).tick(10).unsafeRunSync()

    // Two at a time, so at most two deliveries hold a lease while their requests are in flight.
    assertEquals(repository.claims.map(_._2), List(2, 2, 2))
    assertEquals(repository.sent.map(_._1).toSet, deliveries.map(_.id).toSet)
    assert(repository.waveSizes.forall(_ <= 2), repository.waveSizes.toString)
  }

  test("a tick never leases more than its budget") {
    val repository = new DispatchRepository((1 to 6).toList.map(index => pending(id = deliveryId(index))))

    dispatcher(repository, new FixedSender(NotificationSendResult.Sent), maxConcurrency = 2).tick(3)
      .unsafeRunSync()

    assertEquals(repository.claims.map(_._2), List(2, 1))
    assertEquals(repository.sent.size, 3)
  }

  test("every claimed delivery is sent once with its stable event id") {
    val second = pending(id = SecondDeliveryId)
    val repository = new DispatchRepository(List(pending(), second))
    val sender = new FixedSender(NotificationSendResult.Sent)

    dispatcher(repository, sender, maxConcurrency = 2).tick(10).unsafeRunSync()

    assertEquals(sender.events.map(_.eventId).toSet, Set(DeliveryId, SecondDeliveryId))
    assertEquals(sender.events.map(_.incidentId).toSet, Set(IncidentId))
    assertEquals(repository.sent.map(_._1).toSet, Set(DeliveryId, SecondDeliveryId))
  }

  // -------------------------------------------------------------------------------------------
  // Fixtures
  // -------------------------------------------------------------------------------------------

  private def recorder(repository: NotificationDeliveryRepository[IO]): RecordNotificationDeliveries[IO] =
    new RecordNotificationDeliveries[IO](repository, new SequenceIdGenerator, new FixedTimeProvider,
      List(NotificationChannel.Webhook))

  private def dispatcher(
    repository: NotificationDeliveryRepository[IO],
    sender: NotificationSender[IO],
    maxAttempts: Long = 10,
    maxConcurrency: Int = 1
  ): NotificationDispatcher[IO, IO] =
    new NotificationDispatcher[IO, IO](repository, sender, new DirectRunner, new FixedTimeProvider,
      Slf4jLogger.getLoggerFromName[IO]("test.notification"), maxConcurrency, DispatcherId,
      60.seconds, maxAttempts)

  private def deliveryId(index: Int): UUID = UUID.fromString(f"c0000000-0000-0000-0000-$index%012d")

  private def pending(id: UUID = DeliveryId, attemptCount: Long = 0): NotificationDelivery =
    NotificationDelivery(id, OrganizationId, IncidentId, ResourceId, RuleId,
      NotificationEventType.IncidentOpened, IncidentReason.ThresholdViolation,
      NotificationChannel.Webhook, EvaluatedAt, NotificationDeliveryStatus.Pending, attemptCount,
      EvaluatedAt, Some(DispatcherId), Some(Now.plusSeconds(60)), None, None, EvaluatedAt, EvaluatedAt)

  private final class FixedEvaluator(transitions: List[MonitorTransition]) extends MonitorRuleEvaluator[IO] {
    override def execute(organizationId: UUID, connectionId: UUID, evaluatedAt: Instant): IO[List[MonitorTransition]] =
      IO.pure(transitions)
  }

  /** A wave is delivered in parallel, so the recording fakes synchronize their bookkeeping. */
  private final class FixedSender(result: NotificationSendResult) extends NotificationSender[IO] {
    private var recorded: List[NotificationEvent] = List.empty
    def events: List[NotificationEvent] = synchronized(recorded)
    def calls: Int = events.size
    override def send(event: NotificationEvent): IO[NotificationSendResult] =
      IO(synchronized { recorded = recorded :+ event }) *> IO.pure(result)
  }

  private class RecordingRepository extends NotificationDeliveryRepository[IO] {
    var batches: List[List[NotificationDelivery]] = List.empty
    def saved: List[NotificationDelivery] = batches.flatten
    override def saveAll(deliveries: List[NotificationDelivery]): IO[Unit] =
      IO { batches = batches :+ deliveries }
    override def findById(organizationId: UUID, id: UUID): IO[Option[NotificationDelivery]] = IO.pure(None)
    override def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): IO[List[NotificationDelivery]] =
      IO.pure(List.empty)
    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): IO[Boolean] =
      IO.pure(true)
    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): IO[Boolean] = IO.pure(true)
  }

  private final class DispatchRepository(
    claimable: List[NotificationDelivery],
    fenced: Boolean = true
  ) extends RecordingRepository {
    var claims: List[(UUID, Int, Long)] = List.empty
    var waveSizes: List[Int] = List.empty
    private var queued: List[NotificationDelivery] = claimable
    var sent: List[(UUID, UUID, Instant)] = List.empty
    var rescheduled: List[(UUID, UUID, Long, Instant, String)] = List.empty
    var dead: List[(UUID, UUID, Long, String)] = List.empty

    override def claimPending(claimedBy: UUID, limit: Int, leaseSeconds: Long): IO[List[NotificationDelivery]] =
      IO(synchronized {
        claims = claims :+ ((claimedBy, limit, leaseSeconds))
        val (wave, rest) = queued.splitAt(limit)
        queued = rest
        waveSizes = waveSizes :+ wave.size
        wave.map(_.copy(claimedBy = Some(claimedBy)))
      })

    override def markSent(organizationId: UUID, id: UUID, claimedBy: UUID, sentAt: Instant): IO[Boolean] =
      IO(synchronized { sent = sent :+ ((id, claimedBy, sentAt)) }) *> IO.pure(fenced)

    override def reschedule(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      nextAttemptAt: Instant, errorCode: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized {
        rescheduled = rescheduled :+ ((id, claimedBy, attemptCount, nextAttemptAt, errorCode))
      }) *> IO.pure(fenced)

    override def markDead(organizationId: UUID, id: UUID, claimedBy: UUID, attemptCount: Long,
      errorCode: String, updatedAt: Instant): IO[Boolean] =
      IO(synchronized { dead = dead :+ ((id, claimedBy, attemptCount, errorCode)) }) *> IO.pure(fenced)
  }

  private final class DirectRunner extends TransactionRunner[IO, IO] {
    override def run[A](program: IO[A]): IO[A] = program
  }

  private final class SequenceIdGenerator extends IdGenerator[IO] {
    private var index = 0
    override def nextId: IO[UUID] = IO {
      index += 1
      UUID.fromString(f"c0000000-0000-0000-0000-$index%012d")
    }
  }

  private final class FixedTimeProvider extends TimeProvider[IO] {
    override def now: IO[Instant] = IO.pure(Now)
  }

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ConnectionId = UUID.fromString("60000000-0000-0000-0000-000000000003")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")
  private val DeliveryId = UUID.fromString("c0000000-0000-0000-0000-000000000001")
  private val SecondDeliveryId = UUID.fromString("c0000000-0000-0000-0000-000000000002")
  private val DispatcherId = UUID.fromString("d0000000-0000-0000-0000-000000000001")
  private val EvaluatedAt = Instant.parse("2026-09-24T10:00:00Z")
  private val Now = Instant.parse("2026-09-24T10:00:05Z")

  private val opened = MonitorTransition.Opened(OrganizationId, ResourceId, RuleId, IncidentId,
    IncidentReason.ThresholdViolation, EvaluatedAt)
  private val resolved = MonitorTransition.Resolved(OrganizationId, ResourceId, RuleId, IncidentId,
    IncidentReason.NoData, EvaluatedAt)
}
