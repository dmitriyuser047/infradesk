package ru.bitec.app.ops
package infrastructure.http.dto

import java.time.Instant
import java.util.UUID

final case class ResourceResponse(
                                   id: UUID,
                                   organizationId: UUID,
                                   environmentId: UUID,
                                   resourceTypeId: UUID,
                                   parentResourceId: Option[UUID],
                                   code: String,
                                   name: String,
                                   resourceTypeCode: String,
                                   active: Boolean,
                                   data: ResourceDataResponse,
                                   createdAt: Instant,
                                   updatedAt: Instant
                                 )

sealed trait ResourceDataResponse

final case class NodeResourceDataResponse(spec: Option[NodeSpecResponse], status: Option[NodeStatusResponse])
  extends ResourceDataResponse

final case class NodeSpecResponse(
                                   hostname: String,
                                   operatingSystem: Option[String],
                                   distribution: Option[String],
                                   kernelVersion: Option[String],
                                   architecture: Option[String],
                                   cpuModel: Option[String],
                                   cpuCores: Option[Int],
                                   memoryMb: Option[Long]
                                 )

final case class NodeStatusResponse(
                                     online: Boolean,
                                     cpuUsagePercent: Option[BigDecimal],
                                     memoryUsagePercent: Option[BigDecimal],
                                     uptimeSeconds: Option[Long],
                                     telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry()
                                   )

final case class ContainerResourceDataResponse(spec: Option[ContainerSpecResponse], status: Option[ContainerStatusResponse])
  extends ResourceDataResponse

final case class ContainerSpecResponse(image: Option[String])

final case class ContainerStatusResponse(state: Option[String], telemetry: domain.metric.ResourceTelemetry = domain.metric.ResourceTelemetry())
