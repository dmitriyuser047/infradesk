package ru.bitec.app.ops
package application.port

import domain.connection.ConnectionScope
import domain.history.{HistoryEvent, HistoryEventCursor, HistoryEventSource, HistoryEventType}

import java.time.Instant
import java.util.UUID

/** The operational journal.
  *
  * Append-only on purpose: no update and no delete, so a fact can only be corrected by recording
  * another fact. Every method is scoped by organization.
  */
trait HistoryEventRepository[F[_]] {

  def save(event: HistoryEvent): F[Unit]

  def saveAll(events: List[HistoryEvent]): F[Unit]
}

/** One row of the timeline, joined with just enough of what it points at to be displayed. */
final case class HistoryEventView(
  id: UUID,
  eventType: HistoryEventType,
  source: HistoryEventSource,
  occurredAt: Instant,
  resource: Option[HistoryResourceView],
  connection: Option[HistoryConnectionView],
  actor: Option[HistoryActorView],
  incident: Option[HistoryIncidentView],
  operation: Option[HistoryOperationView],
  sync: Option[HistorySyncView]
)

final case class HistoryResourceView(id: UUID, name: String, resourceTypeCode: String, environmentId: UUID)
final case class HistoryConnectionView(id: UUID, name: String)
final case class HistoryActorView(id: UUID, displayName: String)
final case class HistoryIncidentView(id: UUID, status: String, reason: String, monitorRuleId: UUID)
final case class HistoryOperationView(
  id: UUID,
  operationCode: String,
  status: String,
  errorCode: Option[String],
  errorMessage: Option[String]
)
final case class HistorySyncView(id: UUID, status: String, errorCode: Option[String])

/** Reads the timeline. One statement per page: the projection joins what it needs. */
trait HistoryEventQuery[F[_]] {

  def listByOrganization(
    organizationId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): F[List[HistoryEventView]]

  def listByResource(
    organizationId: UUID,
    resourceId: UUID,
    before: Option[HistoryEventCursor],
    limit: Int
  ): F[List[HistoryEventView]]

  /** The newest entries of a part of the hierarchy.
    *
    * An entry belongs to a project or an environment through its resource when it has one, and
    * through its connection otherwise. The organization scope is the whole timeline.
    */
  def listByScope(organizationId: UUID, scope: ConnectionScope, limit: Int): F[List[HistoryEventView]]
}
