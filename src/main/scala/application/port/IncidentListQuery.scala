package ru.bitec.app.ops
package application.port

import domain.incident.{Incident, IncidentStatus}

import java.util.UUID

/** The identity of the resource an incident is about: enough to name it and link to it in a list,
  * nothing of its spec or status.
  */
final case class IncidentResourceReference(id: UUID, name: String, typeCode: String)

final case class IncidentListItem(incident: Incident, resource: IncidentResourceReference)

/** The incident list as a read projection: every incident of an organization together with the
  * identity of its resource, read in one statement whatever the number of distinct resources.
  *
  * It reads the same rows the incident lifecycle writes and changes nothing about them.
  */
trait IncidentListQuery[F[_]] {
  def list(organizationId: UUID, status: Option[IncidentStatus]): F[List[IncidentListItem]]
}
