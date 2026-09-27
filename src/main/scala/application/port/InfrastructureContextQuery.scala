package ru.bitec.app.ops
package application.port

import domain.resource.Resource

import java.util.UUID

final case class ProjectReference(id: UUID, name: String)

final case class EnvironmentReference(id: UUID, name: String, kind: String)

/** Where something lives in the workspace, by name. */
final case class InfrastructureLocation(project: ProjectReference, environment: EnvironmentReference)

/** A resource named well enough to link to it. */
final case class ResourceReference(id: UUID, name: String, resourceTypeCode: String)

/** One connection through which a resource was discovered.
  *
  * A resource may be known to several connections through `external_ref`; every reader receives
  * all of them and none is singled out as the primary one.
  */
final case class SourceConnectionReference(id: UUID, name: String, connectorType: String, active: Boolean)

/** The typed condition of a monitor rule, enough to present it without reading the rule again. */
final case class MonitorConditionView(
  id: UUID,
  metricCode: String,
  operator: String,
  threshold: BigDecimal,
  forSeconds: Long,
  noDataSeconds: Long
)

/** A resource together with the environment it belongs to. */
final case class LocatedResource(resource: Resource, environment: EnvironmentReference)

final case class ResourceTypeCount(resourceTypeCode: String, count: Long)

/** What a connection discovered, counted. */
final case class ConnectionResourceCounts(
  activeResources: Long,
  inactiveResources: Long,
  openIncidents: Long,
  activeByType: List[ResourceTypeCount]
)

/** The place of one resource in the infrastructure graph. */
final case class ResourceContextView(
  location: InfrastructureLocation,
  parent: Option[ResourceReference],
  sourceConnections: List[SourceConnectionReference],
  children: List[Resource],
  activeChildCount: Long,
  openIncidentCount: Long
)

/** The lightweight indicators of one connection in the organization's connection list. */
final case class ConnectionInfrastructureCounts(
  connectionId: UUID,
  activeResourceCount: Long,
  openIncidentCount: Long
)

final case class ResourceSources(resourceId: UUID, sourceConnections: List[SourceConnectionReference])

/** Read models that place connections and resources in the infrastructure graph.
  *
  * The relation between a connection and a resource is `external_ref`: a connection is where a
  * resource was discovered, not its owner. Every method is scoped by organization and reads a fixed
  * number of statements however many rows it returns.
  */
trait InfrastructureContextQuery[F[_]] {

  def connectionExists(organizationId: UUID, connectionId: UUID): F[Boolean]

  def resourceExists(organizationId: UUID, resourceId: UUID): F[Boolean]

  /** The active resources a connection discovered, grouped by environment, roots before children. */
  def connectionResources(organizationId: UUID, connectionId: UUID, limit: Int): F[List[LocatedResource]]

  def connectionCounts(organizationId: UUID, connectionId: UUID): F[ConnectionResourceCounts]

  def organizationConnectionCounts(organizationId: UUID): F[List[ConnectionInfrastructureCounts]]

  def resourceContext(organizationId: UUID, resourceId: UUID, childLimit: Int): F[Option[ResourceContextView]]

  /** The source connections of every active resource of an environment, for its resource list. */
  def environmentResourceSources(organizationId: UUID, environmentId: UUID): F[List[ResourceSources]]
}
