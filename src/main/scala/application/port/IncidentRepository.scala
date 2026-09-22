package ru.bitec.app.ops
package application.port

import domain.incident.Incident

import java.util.UUID

trait IncidentRepository[F[_]] {
  def findOpenByRule(organizationId: UUID, monitorRuleId: UUID): F[Option[Incident]]

  def save(incident: Incident): F[Unit]
}
