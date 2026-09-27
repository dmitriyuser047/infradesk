package ru.bitec.app.ops
package application.context

import application.port.{
  ConnectionInfrastructureCounts,
  ConnectionResourceCounts,
  IncidentListItem,
  IncidentListQuery,
  InfrastructureContextQuery,
  LocatedResource,
  ResourceContextView,
  ResourceSources
}
import cats.Monad
import cats.syntax.all._
import domain.incident.IncidentStatus

import java.util.UUID

/** What a connection's overview shows: counts, the newest open incidents and the first resources
  * of its hierarchy. Bounded on purpose; the full lists are their own reads.
  */
final case class ConnectionInfrastructureSummary(
  counts: ConnectionResourceCounts,
  openIncidents: List[IncidentListItem],
  resources: List[LocatedResource]
)

/** Five statements for an existing connection: the existence check, the counts and the counts by
  * type, the open incident preview and the resource preview. Meant for one read-only snapshot, so the counts and the
  * previews they summarize cannot disagree.
  */
final case class GetConnectionInfrastructureSummary[Tx[_]: Monad](
  context: InfrastructureContextQuery[Tx],
  incidents: IncidentListQuery[Tx]
) {
  import GetConnectionInfrastructureSummary._

  def execute(organizationId: UUID, connectionId: UUID): Tx[Option[ConnectionInfrastructureSummary]] =
    context.connectionExists(organizationId, connectionId).flatMap {
      case false => none[ConnectionInfrastructureSummary].pure[Tx]
      case true =>
        for {
          counts <- context.connectionCounts(organizationId, connectionId)
          open <- incidents.listByConnection(organizationId, connectionId, Some(IncidentStatus.Open), None, IncidentPreview)
          resources <- context.connectionResources(organizationId, connectionId, ResourcePreview)
        } yield Some(ConnectionInfrastructureSummary(counts, open, resources))
    }
}

object GetConnectionInfrastructureSummary {
  val IncidentPreview = 5
  val ResourcePreview = 12
}

/** The active resources a connection discovered, capped: a connection with more than the cap shows
  * its first resources and the summary count tells how many there are.
  */
final case class ListConnectionResources[Tx[_]: Monad](context: InfrastructureContextQuery[Tx]) {
  def execute(organizationId: UUID, connectionId: UUID): Tx[Option[List[LocatedResource]]] =
    context.connectionExists(organizationId, connectionId).flatMap {
      case false => none[List[LocatedResource]].pure[Tx]
      case true => context.connectionResources(organizationId, connectionId, ListConnectionResources.MaxResources).map(Some(_))
    }
}

object ListConnectionResources {
  val MaxResources = 500
}

final case class GetResourceContext[Tx[_]](context: InfrastructureContextQuery[Tx]) {
  def execute(organizationId: UUID, resourceId: UUID): Tx[Option[ResourceContextView]] =
    context.resourceContext(organizationId, resourceId, GetResourceContext.ChildPreview)
}

object GetResourceContext {
  val ChildPreview = 25
}

final case class ListConnectionInfrastructureCounts[Tx[_]](context: InfrastructureContextQuery[Tx]) {
  def execute(organizationId: UUID): Tx[List[ConnectionInfrastructureCounts]] =
    context.organizationConnectionCounts(organizationId)
}

final case class ListEnvironmentResourceSources[Tx[_]](context: InfrastructureContextQuery[Tx]) {
  def execute(organizationId: UUID, environmentId: UUID): Tx[List[ResourceSources]] =
    context.environmentResourceSources(organizationId, environmentId)
}
