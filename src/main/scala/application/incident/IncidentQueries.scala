package ru.bitec.app.ops
package application.incident
import application.port.IncidentRepository
import domain.incident.{Incident, IncidentStatus}
import java.util.UUID
final case class GetIncident[Tx[_]](repository: IncidentRepository[Tx]) { def execute(org: UUID, id: UUID): Tx[Option[Incident]] = repository.findById(org,id) }
final case class ListIncidents[Tx[_]](repository: IncidentRepository[Tx]) { def execute(org: UUID, status: Option[IncidentStatus]): Tx[List[Incident]] = repository.findByOrganization(org,status) }
