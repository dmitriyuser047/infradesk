package ru.bitec.app.ops
package domain.resource.node

import domain.resource.ResourceStatus

final case class NodeStatus(
                             online: Boolean,
                             cpuUsagePercent: Option[BigDecimal],
                             memoryUsagePercent: Option[BigDecimal],
                             uptimeSeconds: Option[Long]
                           ) extends ResourceStatus
