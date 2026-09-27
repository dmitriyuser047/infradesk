package ru.bitec.app.ops
package infrastructure.http.dto

import application.context.ConnectionInfrastructureSummary
import application.port.{
  ConnectionInfrastructureCounts,
  EnvironmentReference,
  IncidentListItem,
  InfrastructureLocation,
  LocatedResource,
  MonitorConditionView,
  ResourceContextView,
  ResourceReference,
  ResourceSources,
  SourceConnectionReference
}
import cats.syntax.all._
import infrastructure.http.mapper.ResourceHttpMapper
import io.circe.{Encoder, Json}

import java.util.UUID

final case class ProjectReferenceResponse(id: UUID, name: String)
final case class EnvironmentReferenceResponse(id: UUID, name: String, kind: String)
final case class ResourceReferenceResponse(id: UUID, name: String, resourceTypeCode: String)
final case class SourceConnectionResponse(id: UUID, name: String, connectorType: String, active: Boolean)
final case class MonitorConditionResponse(
  id: UUID,
  metricCode: String,
  operator: String,
  threshold: BigDecimal,
  forSeconds: Long,
  noDataSeconds: Long
)

/** An incident with its infrastructure context. Encoded as the incident itself plus the context
  * fields, so every reader of the plain incident keeps working.
  */
final case class IncidentContextResponse(
  incident: IncidentResponse,
  resource: IncidentResourceResponse,
  project: ProjectReferenceResponse,
  environment: EnvironmentReferenceResponse,
  monitorRule: MonitorConditionResponse,
  parentResource: Option[ResourceReferenceResponse],
  sourceConnections: List[SourceConnectionResponse]
)

/** A resource plus its environment, encoded as the resource with an `environment` field. */
final case class LocatedResourceResponse(resource: ResourceResponse, environment: EnvironmentReferenceResponse)

final case class ResourceTypeCountResponse(resourceTypeCode: String, count: Long)

final case class ConnectionInfrastructureSummaryResponse(
  activeResourceCount: Long,
  inactiveResourceCount: Long,
  openIncidentCount: Long,
  resourceTypeCounts: List[ResourceTypeCountResponse],
  openIncidents: List[IncidentContextResponse],
  resources: List[LocatedResourceResponse]
)

final case class ResourceContextResponse(
  project: ProjectReferenceResponse,
  environment: EnvironmentReferenceResponse,
  parentResource: Option[ResourceReferenceResponse],
  sourceConnections: List[SourceConnectionResponse],
  children: List[ResourceResponse],
  activeChildCount: Long,
  openIncidentCount: Long
)

final case class ConnectionInfrastructureCountsResponse(connectionId: UUID, activeResourceCount: Long, openIncidentCount: Long)

final case class ResourceSourcesResponse(resourceId: UUID, sourceConnections: List[SourceConnectionResponse])

object InfrastructureContextResponses {

  def project(location: InfrastructureLocation): ProjectReferenceResponse =
    ProjectReferenceResponse(location.project.id, location.project.name)

  def environment(value: EnvironmentReference): EnvironmentReferenceResponse =
    EnvironmentReferenceResponse(value.id, value.name, value.kind)

  def resourceReference(value: ResourceReference): ResourceReferenceResponse =
    ResourceReferenceResponse(value.id, value.name, value.resourceTypeCode)

  def source(value: SourceConnectionReference): SourceConnectionResponse =
    SourceConnectionResponse(value.id, value.name, value.connectorType, value.active)

  def monitorCondition(value: MonitorConditionView): MonitorConditionResponse =
    MonitorConditionResponse(value.id, value.metricCode, value.operator, value.threshold, value.forSeconds, value.noDataSeconds)

  def incident(item: IncidentListItem): IncidentContextResponse = {
    val i = item.incident
    IncidentContextResponse(
      IncidentResponse(i.id, i.monitorRuleId, i.resourceId, i.status.code, i.reason.code, i.startedAt, i.openedAt,
        i.resolvedAt, i.createdAt, i.updatedAt),
      IncidentResourceResponse(item.resource.id, item.resource.name, item.resource.typeCode),
      project(item.location),
      environment(item.location.environment),
      monitorCondition(item.monitorRule),
      item.parentResource.map(resourceReference),
      item.sourceConnections.map(source)
    )
  }

  def located(value: LocatedResource): Either[IllegalArgumentException, LocatedResourceResponse] =
    ResourceHttpMapper.toResponse(value.resource).map(LocatedResourceResponse(_, environment(value.environment)))

  def summary(value: ConnectionInfrastructureSummary): Either[IllegalArgumentException, ConnectionInfrastructureSummaryResponse] =
    value.resources.traverse(located).map { resources =>
      ConnectionInfrastructureSummaryResponse(
        value.counts.activeResources,
        value.counts.inactiveResources,
        value.counts.openIncidents,
        value.counts.activeByType.map(count => ResourceTypeCountResponse(count.resourceTypeCode, count.count)),
        value.openIncidents.map(incident),
        resources
      )
    }

  def resourceContext(value: ResourceContextView): Either[IllegalArgumentException, ResourceContextResponse] =
    value.children.traverse(ResourceHttpMapper.toResponse).map { children =>
      ResourceContextResponse(
        project(value.location),
        environment(value.location.environment),
        value.parent.map(resourceReference),
        value.sourceConnections.map(source),
        children,
        value.activeChildCount,
        value.openIncidentCount
      )
    }

  def counts(value: ConnectionInfrastructureCounts): ConnectionInfrastructureCountsResponse =
    ConnectionInfrastructureCountsResponse(value.connectionId, value.activeResourceCount, value.openIncidentCount)

  def sources(value: ResourceSources): ResourceSourcesResponse =
    ResourceSourcesResponse(value.resourceId, value.sourceConnections.map(source))

  object Codecs {
    import HttpJsonCodecs._

    implicit val projectReferenceResponseEncoder: Encoder[ProjectReferenceResponse] =
      Encoder.forProduct2("id", "name")(v => (v.id, v.name))
    implicit val environmentReferenceResponseEncoder: Encoder[EnvironmentReferenceResponse] =
      Encoder.forProduct3("id", "name", "kind")(v => (v.id, v.name, v.kind))
    implicit val resourceReferenceResponseEncoder: Encoder[ResourceReferenceResponse] =
      Encoder.forProduct3("id", "name", "resourceTypeCode")(v => (v.id, v.name, v.resourceTypeCode))
    implicit val sourceConnectionResponseEncoder: Encoder[SourceConnectionResponse] =
      Encoder.forProduct4("id", "name", "connectorType", "active")(v => (v.id, v.name, v.connectorType, v.active))
    implicit val monitorConditionResponseEncoder: Encoder[MonitorConditionResponse] =
      Encoder.forProduct6("id", "metricCode", "operator", "threshold", "forSeconds", "noDataSeconds")(v =>
        (v.id, v.metricCode, v.operator, v.threshold, v.forSeconds, v.noDataSeconds))

    implicit val incidentContextResponseEncoder: Encoder[IncidentContextResponse] = Encoder.instance { v =>
      incidentResponseEncoder(v.incident).deepMerge(Json.obj(
        "resource" -> incidentResourceResponseEncoder(v.resource),
        "project" -> projectReferenceResponseEncoder(v.project),
        "environment" -> environmentReferenceResponseEncoder(v.environment),
        "monitorRule" -> monitorConditionResponseEncoder(v.monitorRule),
        "parentResource" -> Encoder.encodeOption(resourceReferenceResponseEncoder)(v.parentResource),
        "sourceConnections" -> Encoder.encodeList(sourceConnectionResponseEncoder)(v.sourceConnections)
      ))
    }

    implicit val locatedResourceResponseEncoder: Encoder[LocatedResourceResponse] = Encoder.instance { v =>
      resourceResponseEncoder(v.resource).deepMerge(Json.obj(
        "environment" -> environmentReferenceResponseEncoder(v.environment)))
    }

    implicit val resourceTypeCountResponseEncoder: Encoder[ResourceTypeCountResponse] =
      Encoder.forProduct2("resourceTypeCode", "count")(v => (v.resourceTypeCode, v.count))

    implicit val connectionInfrastructureSummaryResponseEncoder: Encoder[ConnectionInfrastructureSummaryResponse] =
      Encoder.forProduct6("activeResourceCount", "inactiveResourceCount", "openIncidentCount", "resourceTypeCounts",
        "openIncidents", "resources")(v =>
        (v.activeResourceCount, v.inactiveResourceCount, v.openIncidentCount, v.resourceTypeCounts, v.openIncidents,
          v.resources))

    implicit val resourceContextResponseEncoder: Encoder[ResourceContextResponse] =
      Encoder.forProduct7("project", "environment", "parentResource", "sourceConnections", "children",
        "activeChildCount", "openIncidentCount")(v =>
        (v.project, v.environment, v.parentResource, v.sourceConnections, v.children, v.activeChildCount,
          v.openIncidentCount))

    implicit val connectionInfrastructureCountsResponseEncoder: Encoder[ConnectionInfrastructureCountsResponse] =
      Encoder.forProduct3("connectionId", "activeResourceCount", "openIncidentCount")(v =>
        (v.connectionId, v.activeResourceCount, v.openIncidentCount))

    implicit val resourceSourcesResponseEncoder: Encoder[ResourceSourcesResponse] =
      Encoder.forProduct2("resourceId", "sourceConnections")(v => (v.resourceId, v.sourceConnections))
  }
}
