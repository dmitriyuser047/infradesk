package ru.bitec.app.ops
package infrastructure.http.mapper

import domain.resource.Resource
import domain.resource.container.{ContainerDefinition, ContainerSpec, ContainerStatus}
import domain.resource.node.{NodeDefinition, NodeSpec, NodeStatus}
import infrastructure.http.dto._

object ResourceHttpMapper {

  def toResponse(resource: Resource): Either[IllegalArgumentException, ResourceResponse] =
    toDataResponse(resource).map { data =>
      ResourceResponse(
        resource.id,
        resource.organizationId,
        resource.environmentId,
        resource.resourceTypeId,
        resource.parentResourceId,
        resource.code,
        resource.name,
        resource.resourceTypeCode,
        resource.isActive,
        data,
        resource.createdAt,
        resource.updatedAt
      )
    }

  private def toDataResponse(resource: Resource): Either[IllegalArgumentException, ResourceDataResponse] =
    (resource.resourceTypeCode, resource.data.spec, resource.data.status) match {
      case (resourceTypeCode, Some(spec: NodeSpec), Some(status: NodeStatus))
          if resourceTypeCode == NodeDefinition.code =>
        Right(
          NodeResourceDataResponse(
            Some(NodeSpecResponse(
              spec.hostname,
              spec.operatingSystem,
              spec.architecture,
              spec.cpuCores,
              spec.memoryMb
            )),
            Some(NodeStatusResponse(
              status.online,
              status.cpuUsagePercent,
              status.memoryUsagePercent,
              status.uptimeSeconds
            ))
          )
        )

      case (resourceTypeCode, None, None) if resourceTypeCode == NodeDefinition.code =>
        Right(NodeResourceDataResponse(None, None))

      case (resourceTypeCode, Some(spec: ContainerSpec), Some(status: ContainerStatus))
          if resourceTypeCode == ContainerDefinition.code =>
        Right(
          ContainerResourceDataResponse(
            Some(ContainerSpecResponse(spec.image)),
            Some(ContainerStatusResponse(status.state))
          )
        )

      case (resourceTypeCode, None, None) if resourceTypeCode == ContainerDefinition.code =>
        Right(ContainerResourceDataResponse(None, None))

      case (resourceTypeCode, _, _) =>
        Left(new IllegalArgumentException(s"Unsupported HTTP resource data for type '$resourceTypeCode'"))
    }
}
