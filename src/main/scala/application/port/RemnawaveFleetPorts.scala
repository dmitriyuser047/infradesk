package ru.bitec.app.ops
package application.port

import domain.integration._
import domain.provisioning.{ServerProfileAssignment, ServerProfileContent, ServerProfileObservation}
import java.time.Instant
import java.util.UUID

/** A member with everything the dashboard shows about it, read without any remote call. */
final case class FleetMemberRow(membership: RemnawaveFleetMembership, nodeName: String, nodeAddress: String,
  countryCode: Option[String], connected: Boolean, disabled: Boolean, resourceName: String,
  resourceActive: Boolean, actualServerProfileName: Option[String], actualServerRevisionNumber: Option[Int],
  actualConfigProfileName: Option[String], actualConfigProfileExternalId: Option[String],
  actualInboundIds: Option[List[String]], actualDesiredState: Option[IntegrationDesiredNodeState],
  localManaged: Boolean, assessment: Option[RemnawaveFleetNodeAssessment])

/** A node that may still be added to a fleet, with the reason it cannot when it cannot. */
final case class FleetCandidate(inventoryNodeId: UUID, externalId: String, nodeName: String, resourceId: UUID,
  resourceName: String, eligible: Boolean, blockedBy: Option[String], currentFleetName: Option[String])

/** Proof that Stage25C installed and still owns this node's local installation. */
final case class FleetLocalProvenance(onboardingId: UUID, externalNodeId: UUID, imageReference: String,
  provisioningReady: Boolean, succeededAt: Instant)

/** Everything one member's assessment needs that lives in the database. Remote reads are separate. */
final case class FleetStoredEvidence(membership: RemnawaveFleetMembership, bindingPresent: Boolean,
  bindingResourceId: Option[UUID], resourceActive: Boolean, node: Option[RemnawaveNodeSummary],
  inventoryActive: Boolean, inventoryObservedAt: Option[Instant], remoteConfigSha256: Option[String],
  configProfileAvailable: Boolean, serverProfileAvailable: Boolean,
  desiredServerContent: Option[ServerProfileContent], assignment: Option[ServerProfileAssignment],
  serverObservation: Option[ServerProfileObservation], desiredStateRecord: Option[IntegrationDesiredState],
  provenance: Option[FleetLocalProvenance], busy: Boolean)

/** What a candidate revision would change, as a read model. Computing it mutates nothing. */
final case class FleetRevisionImpact(current: Option[RemnawaveFleetRevision], candidate: RemnawaveFleetRevision,
  currentServerProfileName: Option[String], candidateServerProfileName: String,
  currentConfigProfileName: Option[String], candidateConfigProfileName: String,
  members: Int, compliantAfter: Int, expectedDrift: Int, unknown: Int, blocked: Int)

trait RemnawaveFleetRepository[F[_]] {
  def insertFleet(fleet: RemnawaveFleet): F[Boolean]
  def fleet(org: UUID, integrationId: UUID, id: UUID): F[Option[RemnawaveFleet]]
  def fleetForUpdate(org: UUID, integrationId: UUID, id: UUID): F[Option[RemnawaveFleet]]
  def fleets(org: UUID, integrationId: UUID, archived: Boolean, limit: Int): F[List[RemnawaveFleet]]
  def updateFleet(fleet: RemnawaveFleet, expectedVersion: Long, now: Instant): F[Boolean]
  def insertRevision(revision: RemnawaveFleetRevision): F[Boolean]
  def revision(org: UUID, fleetId: UUID, id: UUID): F[Option[RemnawaveFleetRevision]]
  def revisions(org: UUID, fleetId: UUID, limit: Int): F[List[RemnawaveFleetRevision]]
  def nextRevisionNumber(org: UUID, fleetId: UUID): F[Int]
  /** Moves the desired pointer only. Returns false when the expected version no longer holds. */
  def promote(org: UUID, fleetId: UUID, revisionId: UUID, expectedVersion: Long, now: Instant): F[Boolean]
  def insertMembership(membership: RemnawaveFleetMembership, nextCheckAt: Instant): F[Boolean]
  def membership(org: UUID, fleetId: UUID, id: UUID): F[Option[RemnawaveFleetMembership]]
  def members(org: UUID, fleetId: UUID): F[List[RemnawaveFleetMembership]]
  def activeMembershipOf(org: UUID, integrationId: UUID, inventoryNodeId: UUID): F[Option[RemnawaveFleetMembership]]
  def removeMembership(org: UUID, fleetId: UUID, id: UUID, now: Instant): F[Boolean]
  /** Makes members due now, which is all a refresh or a promotion does to the observer. */
  def markDue(org: UUID, fleetId: Option[UUID], integrationId: UUID, at: Instant): F[Int]
  def claimDue(owner: UUID, token: UUID, now: Instant, until: Instant, limit: Int): F[List[RemnawaveFleetMembership]]
  def renewClaim(membership: RemnawaveFleetMembership, token: UUID, now: Instant, until: Instant): F[Boolean]
  /** Writes the verdict and releases the claim, but only while nothing it was computed from changed. */
  def saveAssessment(assessment: RemnawaveFleetNodeAssessment, token: UUID, now: Instant,
    nextCheckAt: Instant): F[Boolean]
  /** Releases a claim without writing a verdict, so a discarded result is simply rescheduled. */
  def reschedule(membership: RemnawaveFleetMembership, token: UUID, now: Instant, nextCheckAt: Instant): F[Boolean]
  def assessments(org: UUID, fleetId: UUID): F[List[RemnawaveFleetNodeAssessment]]
  def lockFleet(org: UUID, fleetId: UUID): F[Unit]
}

trait RemnawaveFleetQuery[F[_]] {
  def summaries(org: UUID, integrationId: UUID, fleetIds: List[UUID]): F[Map[UUID, RemnawaveFleetSummary]]
  def memberRows(org: UUID, fleetId: UUID): F[List[FleetMemberRow]]
  def candidates(org: UUID, integrationId: UUID, limit: Int): F[List[FleetCandidate]]
  def storedEvidence(org: UUID, membershipId: UUID, revision: RemnawaveFleetRevision): F[Option[FleetStoredEvidence]]
  def provenance(org: UUID, integrationId: UUID, resourceId: UUID,
    inventoryNodeId: UUID): F[Option[FleetLocalProvenance]]
  def serverProfileName(org: UUID, profileId: UUID): F[Option[String]]
  def configurationProfileName(org: UUID, profileId: UUID): F[Option[String]]
  /** The latest successful inventory observation of the integration, for the sync freshness check. */
  def lastSuccessfulSyncAt(org: UUID, integrationId: UUID): F[Option[Instant]]
}
