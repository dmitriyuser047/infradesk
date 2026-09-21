package ru.bitec.app.ops
package domain.resource

final case class ResourceData(
                               spec: Option[ResourceSpec],
                               status: Option[ResourceStatus]
                             )

object ResourceData {
  val empty: ResourceData = ResourceData(None, None)
}
