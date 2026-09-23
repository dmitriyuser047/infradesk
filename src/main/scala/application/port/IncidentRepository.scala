package ru.bitec.app.ops
package application.port

import domain.incident.Incident
import domain.incident.IncidentStatus

import java.util.UUID

trait IncidentRepository[F[_]] {
  def findOpenByRule(organizationId: UUID, monitorRuleId: UUID): F[Option[Incident]]
  def findById(organizationId: UUID, incidentId: UUID): F[Option[Incident]]
  def findByOrganization(organizationId: UUID, status: Option[IncidentStatus]): F[List[Incident]]

  def save(incident: Incident): F[Unit]
  def saveAll(incidents: List[Incident]): F[Unit]
}
