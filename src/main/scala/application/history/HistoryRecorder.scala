package ru.bitec.app.ops
package application.history

import application.port.{HistoryEventQuery, HistoryEventRepository, HistoryEventView, IdGenerator, TimeProvider}
import cats.Monad
import cats.syntax.all._
import domain.history.{HistoryEvent, HistoryEventCursor, HistoryEventSource, HistoryEventType}

import java.time.Instant
import java.util.UUID

/** What a caller states about a fact; the recorder supplies the id and the write time. */
final case class HistoryEntry(
  organizationId: UUID,
  eventType: HistoryEventType,
  source: HistoryEventSource,
  occurredAt: Instant,
  resourceId: Option[UUID] = None,
  connectionId: Option[UUID] = None,
  incidentId: Option[UUID] = None,
  operationExecutionId: Option[UUID] = None,
  syncSessionId: Option[UUID] = None,
  actorUserId: Option[UUID] = None
)

object HistoryEntry {

  /** The runtime acted on its own: monitoring, synchronization, reconciliation. */
  def system(organizationId: UUID, eventType: HistoryEventType, occurredAt: Instant): HistoryEntry =
    HistoryEntry(organizationId, eventType, HistoryEventSource.System, occurredAt)

  /** A person acted through the API, so the actor is part of the fact. */
  def user(
    organizationId: UUID,
    eventType: HistoryEventType,
    occurredAt: Instant,
    actorUserId: UUID
  ): HistoryEntry =
    HistoryEntry(organizationId, eventType, HistoryEventSource.User, occurredAt,
      actorUserId = Some(actorUserId))
}

/** Writes journal entries inside the transaction of the change that produced them.
  *
  * An ordinary dependency of a use case, not a bus and not an interceptor: if the insert fails,
  * the state transition fails with it. Several facts of one transition are written in one batch.
  */
final class HistoryRecorder[Tx[_]: Monad](
  events: HistoryEventRepository[Tx],
  ids: IdGenerator[Tx],
  time: TimeProvider[Tx]
) {

  def record(entry: HistoryEntry): Tx[Unit] = recordAll(List(entry))

  def recordAll(entries: List[HistoryEntry]): Tx[Unit] =
    if (entries.isEmpty) ().pure[Tx]
    else
      for {
        now <- time.now
        events <- entries.traverse(entry => ids.nextId.map(id => toEvent(id, entry, now)))
        _ <- this.events.saveAll(events)
      } yield ()

  private def toEvent(id: UUID, entry: HistoryEntry, now: Instant): HistoryEvent =
    HistoryEvent(id, entry.organizationId, entry.eventType, entry.source, entry.resourceId,
      entry.connectionId, entry.incidentId, entry.operationExecutionId, entry.syncSessionId,
      entry.actorUserId, entry.occurredAt, now)
}

/** Reads a bounded page of the timeline, newest first. */
final class ListHistoryEvents[Tx[_]](query: HistoryEventQuery[Tx]) {

  def organization(
    organizationId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): Tx[List[HistoryEventView]] =
    query.listByOrganization(organizationId, before, ListHistoryEvents.boundedLimit(limit))

  def resource(
    organizationId: UUID,
    resourceId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): Tx[List[HistoryEventView]] =
    query.listByResource(organizationId, resourceId, before, ListHistoryEvents.boundedLimit(limit))
}

object ListHistoryEvents {
  val DefaultLimit: Int = 50
  val MaxLimit: Int = 100

  def boundedLimit(limit: Int): Int = math.max(1, math.min(limit, MaxLimit))
}
