package ru.bitec.app.ops
package application.port

import domain.incident.{Incident, IncidentStatus}

import java.time.Instant
import java.util.UUID

/** The identity of the resource an incident is about: enough to name it and link to it in a list,
  * nothing of its spec or status.
  */
final case class IncidentResourceReference(id: UUID, name: String, typeCode: String)

/** An incident together with everything a row or a detail page needs to place it in the
  * infrastructure: its resource, where that resource lives, the rule that opened it, the parent of
  * the resource and every connection that discovered it. Nothing of a connection's configuration or
  * credentials is part of it.
  */
final case class IncidentListItem(
  incident: Incident,
  resource: IncidentResourceReference,
  location: InfrastructureLocation,
  monitorRule: MonitorConditionView,
  parentResource: Option[ResourceReference],
  sourceConnections: List[SourceConnectionReference]
)

/** Where a page of incidents continues: the exact row the previous page ended on. */
final case class IncidentCursor(openedAt: Instant, id: UUID)

/** Incidents as read projections, each list read in one statement however many resources,
  * environments or connections its rows mention.
  *
  * It reads the same rows the incident lifecycle writes and changes nothing about them.
  */
trait IncidentListQuery[F[_]] {

  /** Every incident of an organization, newest first. */
  def list(organizationId: UUID, status: Option[IncidentStatus]): F[List[IncidentListItem]]

  /** The incidents of the resources a connection discovered, newest first, one page at a time. */
  def listByConnection(
    organizationId: UUID,
    connectionId: UUID,
    status: Option[IncidentStatus],
    before: Option[IncidentCursor],
    limit: Int
  ): F[List[IncidentListItem]]

  /** The incidents of one resource, newest first, one page at a time. */
  def listByResource(
    organizationId: UUID,
    resourceId: UUID,
    status: Option[IncidentStatus],
    before: Option[IncidentCursor],
    limit: Int
  ): F[List[IncidentListItem]]

  def find(organizationId: UUID, incidentId: UUID): F[Option[IncidentListItem]]
}
