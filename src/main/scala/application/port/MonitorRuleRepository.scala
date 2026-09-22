package ru.bitec.app.ops
package application.port

import domain.monitor.MonitorRule

import java.util.UUID

trait MonitorRuleRepository[F[_]] {
  def findById(organizationId: UUID, id: UUID): F[Option[MonitorRule]]

  def findEnabledByResource(organizationId: UUID, resourceId: UUID): F[List[MonitorRule]]

  def save(rule: MonitorRule): F[Unit]
}
