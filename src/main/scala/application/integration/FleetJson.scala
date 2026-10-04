package ru.bitec.app.ops
package application.integration

import application.port.{FleetCandidate, FleetMemberRow, FleetRevisionImpact}
import domain.integration._
import io.circe.Json
import java.time.Instant
import java.util.UUID

/** The published shape of a fleet. It carries readable names and revision numbers alongside the
  * identifiers, and never a configuration body, a script or a credential.
  */
object FleetJson {
  private def str(value: String) = Json.fromString(value)
  private def id(value: UUID) = str(value.toString)
  private def at(value: Instant) = str(value.toString)
  private def strings(values: List[String]) = Json.arr(values.map(str): _*)

  def fleet(value: RemnawaveFleet, summary: RemnawaveFleetSummary): Json = Json.obj(
    "id" -> id(value.id), "code" -> str(value.code), "name" -> str(value.name),
    "description" -> value.description.fold(Json.Null)(str),
    "desiredRevisionId" -> value.desiredRevisionId.fold(Json.Null)(id),
    "desiredRevisionNumber" -> summary.desiredRevisionNumber.fold(Json.Null)(Json.fromInt),
    "version" -> Json.fromLong(value.version), "archived" -> Json.fromBoolean(value.archived),
    "createdAt" -> at(value.createdAt), "updatedAt" -> at(value.updatedAt),
    "summary" -> fleetSummary(summary))

  def fleetSummary(value: RemnawaveFleetSummary): Json = Json.obj(
    "totalNodes" -> Json.fromInt(value.totalNodes), "compliant" -> Json.fromInt(value.compliant),
    "drifted" -> Json.fromInt(value.drifted), "unknown" -> Json.fromInt(value.unknown),
    "blocked" -> Json.fromInt(value.blocked), "healthy" -> Json.fromInt(value.healthy),
    "degraded" -> Json.fromInt(value.degraded), "healthUnknown" -> Json.fromInt(value.healthUnknown),
    "assessed" -> Json.fromInt(value.assessed),
    "lastAssessmentAt" -> value.lastAssessmentAt.fold(Json.Null)(at),
    "oldestEvidenceAt" -> value.oldestEvidenceAt.fold(Json.Null)(at))

  def desired(content: FleetDesiredContent): Json = Json.obj(
    "serverProfileId" -> id(content.serverProfileId),
    "serverProfileRevisionNumber" -> Json.fromInt(content.serverProfileRevisionNumber),
    "inventoryConfigProfileId" -> id(content.inventoryConfigProfileId),
    "externalConfigProfileId" -> id(content.externalConfigProfileId),
    "configurationProfileId" -> id(content.configurationProfileId),
    "configRevisionNumber" -> Json.fromInt(content.configRevisionNumber),
    "activeInboundIds" -> strings(content.activeInboundIds.map(_.toString)),
    "nodePort" -> Json.fromInt(content.nodePort),
    "panelCidrs" -> strings(content.panelCidrs),
    "desiredNodeState" -> str(content.desiredNodeState.code))

  def revision(value: RemnawaveFleetRevision, desiredRevisionId: Option[UUID]): Json = Json.obj(
    "id" -> id(value.id), "number" -> Json.fromInt(value.number),
    "schemaVersion" -> Json.fromInt(value.schemaVersion), "contentHash" -> str(value.contentHash),
    "createdAt" -> at(value.createdAt), "desired" -> Json.fromBoolean(desiredRevisionId.contains(value.id)),
    "desiredConfiguration" -> desired(value.content))

  def assessment(value: RemnawaveFleetNodeAssessment): Json = Json.obj(
    "fleetRevisionId" -> id(value.fleetRevisionId),
    "compliance" -> str(value.compliance.code), "health" -> str(value.health.code),
    "driftReasons" -> strings(value.driftReasons.map(_.code)),
    "healthReasons" -> strings(value.healthReasons.map(_.code)),
    "rolloutBlockers" -> strings(value.rolloutBlockers.map(_.code)),
    "inventoryObservedAt" -> value.inventoryObservedAt.fold(Json.Null)(at),
    "serverObservedAt" -> value.serverObservedAt.fold(Json.Null)(at),
    "localObservedAt" -> value.localObservedAt.fold(Json.Null)(at),
    "oldestEvidenceAt" -> value.oldestEvidenceAt.fold(Json.Null)(at),
    "computedAt" -> at(value.computedAt))

  def member(row: FleetMemberRow, desiredRevisionId: Option[UUID]): Json = Json.obj(
    "membershipId" -> id(row.membership.id), "membershipVersion" -> Json.fromLong(row.membership.version),
    "inventoryNodeId" -> id(row.membership.inventoryNodeId), "resourceId" -> id(row.membership.resourceId),
    "nodeName" -> str(row.nodeName), "nodeAddress" -> str(row.nodeAddress),
    "countryCode" -> row.countryCode.fold(Json.Null)(str), "resourceName" -> str(row.resourceName),
    "resourceActive" -> Json.fromBoolean(row.resourceActive),
    "connected" -> Json.fromBoolean(row.connected), "disabled" -> Json.fromBoolean(row.disabled),
    "localManaged" -> Json.fromBoolean(row.localManaged),
    "actualServerProfileName" -> row.actualServerProfileName.fold(Json.Null)(str),
    "actualServerRevisionNumber" -> row.actualServerRevisionNumber.fold(Json.Null)(Json.fromInt),
    "actualConfigProfileName" -> row.actualConfigProfileName.fold(Json.Null)(str),
    "actualConfigProfileExternalId" -> row.actualConfigProfileExternalId.fold(Json.Null)(str),
    "actualInboundIds" -> row.actualInboundIds.fold(Json.Null)(strings),
    "actualDesiredNodeState" -> row.actualDesiredState.fold(Json.Null)(value => str(value.code)),
    // A verdict computed for an older revision is not this member's current state.
    "assessment" -> row.assessment.filter(value => desiredRevisionId.contains(value.fleetRevisionId))
      .fold(Json.Null)(assessment))

  def detail(value: FleetDetail): Json = Json.obj(
    "fleet" -> fleet(value.fleet, value.summary),
    "desiredRevision" -> value.desired.fold(Json.Null)(revision(_, value.fleet.desiredRevisionId)),
    "serverProfileName" -> value.serverProfileName.fold(Json.Null)(str),
    "configurationProfileName" -> value.configurationProfileName.fold(Json.Null)(str),
    "revisions" -> Json.arr(value.revisions.map(revision(_, value.fleet.desiredRevisionId)): _*),
    "members" -> Json.arr(value.members.map(member(_, value.fleet.desiredRevisionId)): _*))

  def candidate(value: FleetCandidate): Json = Json.obj(
    "inventoryNodeId" -> id(value.inventoryNodeId), "externalId" -> str(value.externalId),
    "nodeName" -> str(value.nodeName), "resourceId" -> id(value.resourceId),
    "resourceName" -> str(value.resourceName), "eligible" -> Json.fromBoolean(value.eligible),
    "blockedBy" -> value.blockedBy.fold(Json.Null)(str),
    "currentFleetName" -> value.currentFleetName.fold(Json.Null)(str))

  /** What a promotion would mean, as numbers only. Producing it changes nothing. */
  def impact(value: FleetRevisionImpact): Json = Json.obj(
    "currentRevisionNumber" -> value.current.fold(Json.Null)(revision => Json.fromInt(revision.number)),
    "candidateRevisionNumber" -> Json.fromInt(value.candidate.number),
    "currentServerProfileName" -> value.currentServerProfileName.fold(Json.Null)(str),
    "candidateServerProfileName" -> str(value.candidateServerProfileName),
    "currentServerRevisionNumber" -> value.current.fold(Json.Null)(revision =>
      Json.fromInt(revision.content.serverProfileRevisionNumber)),
    "candidateServerRevisionNumber" -> Json.fromInt(value.candidate.content.serverProfileRevisionNumber),
    "currentConfigProfileName" -> value.currentConfigProfileName.fold(Json.Null)(str),
    "candidateConfigProfileName" -> str(value.candidateConfigProfileName),
    "currentConfigRevisionNumber" -> value.current.fold(Json.Null)(revision =>
      Json.fromInt(revision.content.configRevisionNumber)),
    "candidateConfigRevisionNumber" -> Json.fromInt(value.candidate.content.configRevisionNumber),
    "currentPanelCidrs" -> value.current.fold(Json.Null)(revision => strings(revision.content.panelCidrs)),
    "candidatePanelCidrs" -> strings(value.candidate.content.panelCidrs),
    "currentNodePort" -> value.current.fold(Json.Null)(revision => Json.fromInt(revision.content.nodePort)),
    "candidateNodePort" -> Json.fromInt(value.candidate.content.nodePort),
    "currentDesiredNodeState" -> value.current.fold(Json.Null)(revision =>
      str(revision.content.desiredNodeState.code)),
    "candidateDesiredNodeState" -> str(value.candidate.content.desiredNodeState.code),
    "members" -> Json.fromInt(value.members), "compliantAfter" -> Json.fromInt(value.compliantAfter),
    "expectedDrift" -> Json.fromInt(value.expectedDrift), "unknown" -> Json.fromInt(value.unknown),
    "blocked" -> Json.fromInt(value.blocked))
}
