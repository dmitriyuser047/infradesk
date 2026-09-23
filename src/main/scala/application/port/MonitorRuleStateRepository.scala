package ru.bitec.app.ops
package application.port

import domain.monitor.MonitorRuleState

import java.util.UUID

trait MonitorRuleStateRepository[F[_]] {
  def findByRuleId(organizationId: UUID, monitorRuleId: UUID): F[Option[MonitorRuleState]]

  /** States of every rule of one resource, in one query. */
  def findByResource(organizationId: UUID, resourceId: UUID): F[List[MonitorRuleState]]

  def save(state: MonitorRuleState): F[Unit]
  def saveAll(states: List[MonitorRuleState]): F[Unit]
}
