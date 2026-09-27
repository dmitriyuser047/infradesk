package ru.bitec.app.ops
package application.incident

import application.port.{IncidentCursor, IncidentListItem, IncidentListQuery, InfrastructureContextQuery}
import cats.Monad
import cats.syntax.all._
import domain.incident.IncidentStatus

import java.util.UUID

/** One incident with its infrastructure context, read in one statement. */
final case class GetIncidentDetail[Tx[_]](query: IncidentListQuery[Tx]) {
  def execute(organizationId: UUID, incidentId: UUID): Tx[Option[IncidentListItem]] =
    query.find(organizationId, incidentId)
}

final case class ListIncidents[Tx[_]](query: IncidentListQuery[Tx]) {
  def execute(organizationId: UUID, status: Option[IncidentStatus]): Tx[List[IncidentListItem]] =
    query.list(organizationId, status)
}

/** One page of incidents, however it was filtered. */
final case class IncidentPageRequest(status: Option[IncidentStatus], before: Option[IncidentCursor], limit: Int)

object IncidentPageRequest {
  val DefaultLimit = 50
  val MaxLimit = 200
}

/** The incidents of the resources a connection discovered. An unknown connection is not found,
  * whether it does not exist or belongs to another organization.
  */
final case class ListConnectionIncidents[Tx[_]: Monad](
  context: InfrastructureContextQuery[Tx],
  query: IncidentListQuery[Tx]
) {
  def execute(organizationId: UUID, connectionId: UUID, page: IncidentPageRequest): Tx[Option[List[IncidentListItem]]] =
    context.connectionExists(organizationId, connectionId).flatMap {
      case false => none[List[IncidentListItem]].pure[Tx]
      case true =>
        query.listByConnection(organizationId, connectionId, page.status, page.before, page.limit).map(Some(_))
    }
}

/** The incidents of one resource. An unknown resource is not found. */
final case class ListResourceIncidents[Tx[_]: Monad](
  context: InfrastructureContextQuery[Tx],
  query: IncidentListQuery[Tx]
) {
  def execute(organizationId: UUID, resourceId: UUID, page: IncidentPageRequest): Tx[Option[List[IncidentListItem]]] =
    context.resourceExists(organizationId, resourceId).flatMap {
      case false => none[List[IncidentListItem]].pure[Tx]
      case true =>
        query.listByResource(organizationId, resourceId, page.status, page.before, page.limit).map(Some(_))
    }
}
