package ru.bitec.app.ops
package persistence.postgres

import application.port.HistoryEventRepository
import cats.syntax.all._
import domain.history.HistoryEvent
import org.typelevel.doobie.{ConnectionIO, Update}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

/** Inserts only: the journal has no update and no delete anywhere in the application. */
final class PostgresHistoryEventRepository extends HistoryEventRepository[ConnectionIO] {

  override def save(event: HistoryEvent): ConnectionIO[Unit] = saveAll(List(event))

  /** One statement per batch, so a synchronization that discovers thirty resources still writes
    * its facts in a single round trip.
    */
  override def saveAll(events: List[HistoryEvent]): ConnectionIO[Unit] =
    if (events.isEmpty) ().pure[ConnectionIO]
    else
      Update[(UUID, UUID, String, String, Option[UUID], Option[UUID], Option[UUID], Option[UUID],
        Option[UUID], Option[UUID], Instant, Instant)]("""
        insert into history_event (
          id, organization_id, event_type, source, resource_id, connection_id, incident_id,
          operation_execution_id, sync_session_id, actor_user_id, occurred_at, created_at
        )
        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """).updateMany(events.map(event =>
        (event.id, event.organizationId, event.eventType.code, event.source.code, event.resourceId,
          event.connectionId, event.incidentId, event.operationExecutionId, event.syncSessionId,
          event.actorUserId, event.occurredAt, event.createdAt)
      )).void
}
