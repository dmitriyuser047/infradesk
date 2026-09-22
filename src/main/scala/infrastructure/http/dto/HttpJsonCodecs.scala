package ru.bitec.app.ops
package infrastructure.http.dto

import io.circe.{Encoder, Json}
import infrastructure.http.IncidentResponse

import java.time.Instant
import java.util.UUID

object HttpJsonCodecs {

  implicit val uuidEncoder: Encoder[UUID] = Encoder.encodeString.contramap(_.toString)
  implicit val instantEncoder: Encoder[Instant] = Encoder.encodeString.contramap(_.toString)

  implicit val apiErrorResponseEncoder: Encoder[ApiErrorResponse] =
    Encoder.forProduct2("code", "message")(value => (value.code, value.message))

  implicit val metricObservationResponseEncoder: Encoder[MetricObservationResponse] =
    Encoder.forProduct3("metricCode", "value", "observedAt")(value => (value.metricCode, value.value, value.observedAt))
  implicit val incidentResponseEncoder: Encoder[IncidentResponse] = Encoder.forProduct9("id","monitorRuleId","resourceId","status","startedAt","openedAt","resolvedAt","createdAt","updatedAt")(v => (v.id,v.monitorRuleId,v.resourceId,v.status,v.startedAt,v.openedAt,v.resolvedAt,v.createdAt,v.updatedAt))

  implicit val nodeSpecResponseEncoder: Encoder[NodeSpecResponse] =
    Encoder.forProduct5("hostname", "operatingSystem", "architecture", "cpuCores", "memoryMb") { value =>
      (value.hostname, value.operatingSystem, value.architecture, value.cpuCores, value.memoryMb)
    }

  implicit val nodeStatusResponseEncoder: Encoder[NodeStatusResponse] =
    Encoder.forProduct4("online", "cpuUsagePercent", "memoryUsagePercent", "uptimeSeconds") { value =>
      (value.online, value.cpuUsagePercent, value.memoryUsagePercent, value.uptimeSeconds)
    }

  implicit val containerSpecResponseEncoder: Encoder[ContainerSpecResponse] =
    Encoder.forProduct1("image")(_.image)

  implicit val containerStatusResponseEncoder: Encoder[ContainerStatusResponse] =
    Encoder.forProduct1("state")(_.state)

  implicit val resourceDataResponseEncoder: Encoder[ResourceDataResponse] = Encoder.instance {
    case NodeResourceDataResponse(spec, status) =>
      Json.obj(
        "kind" -> Json.fromString("NODE"),
        "spec" -> Encoder.encodeOption[NodeSpecResponse].apply(spec),
        "status" -> Encoder.encodeOption[NodeStatusResponse].apply(status)
      )
    case ContainerResourceDataResponse(spec, status) =>
      Json.obj(
        "kind" -> Json.fromString("CONTAINER"),
        "spec" -> Encoder.encodeOption[ContainerSpecResponse].apply(spec),
        "status" -> Encoder.encodeOption[ContainerStatusResponse].apply(status)
      )
  }

  implicit val resourceResponseEncoder: Encoder[ResourceResponse] =
    Encoder.forProduct12(
      "id",
      "organizationId",
      "environmentId",
      "resourceTypeId",
      "parentResourceId",
      "code",
      "name",
      "resourceTypeCode",
      "active",
      "data",
      "createdAt",
      "updatedAt"
    ) { value =>
      (
        value.id,
        value.organizationId,
        value.environmentId,
        value.resourceTypeId,
        value.parentResourceId,
        value.code,
        value.name,
        value.resourceTypeCode,
        value.active,
        value.data,
        value.createdAt,
        value.updatedAt
      )
    }
}
