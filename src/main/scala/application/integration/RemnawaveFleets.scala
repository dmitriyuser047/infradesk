package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.configuration.ConfigurationProfileKind
import domain.integration._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** What a person chooses; the exact revision identities and hashes are resolved by the backend, so
  * a client cannot pin a hash the database does not actually hold.
  */
final case class FleetDesiredInput(serverProfileId: UUID, serverProfileRevisionNumber: Int,
  inventoryConfigProfileId: UUID, configRevisionNumber: Int, activeInboundIds: List[UUID], nodePort: Int,
  panelCidrs: List[String], desiredNodeState: IntegrationDesiredNodeState)

final case class FleetSettings(staleAfter: FiniteDuration, recheckInterval: FiniteDuration)

/** Desired state and membership of Remnawave fleets. Nothing in this service performs a remote
  * mutation: creating, revising, promoting and member changes are all metadata, and a refresh only
  * asks the existing synchronization scheduler to run and marks members due for re-assessment.
  */
final class RemnawaveFleets[Tx[_]: MonadThrow](
  repo: RemnawaveFleetRepository[Tx], query: RemnawaveFleetQuery[Tx],
  integrations: IntegrationRepository[Tx], inventory: IntegrationInventoryRepository[Tx],
  bindings: IntegrationBindingRepository[Tx], configProfiles: IntegrationConfigProfileRepository[Tx],
  configurations: ConfigurationProfileQuery[Tx], serverProfiles: ServerProfileRepository[Tx],
  syncState: IntegrationSyncStateRepository[Tx], audit: AuditRecorder[Tx],
  runner: TransactionRunner[IO, Tx], settings: FleetSettings) {
  import RemnawaveFleets._

  private def error(code: String) = IntegrationError(code, messageFor(code))

  /** The integration must exist, be a Remnawave one and be the caller's own tenant. */
  private def remnawave(org: UUID, integrationId: UUID): Tx[Integration] =
    integrations.findById(org, integrationId).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND"))).flatTap(value =>
      MonadThrow[Tx].raiseUnless(value.providerType == IntegrationProviderType.Remnawave)(
        error("REMNAWAVE_FLEET_PROVIDER_UNSUPPORTED")))

  private def loaded(org: UUID, integrationId: UUID, fleetId: UUID): Tx[RemnawaveFleet] =
    repo.fleet(org, integrationId, fleetId).flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))

  /** Resolves a choice into an exact pinned revision, rejecting anything the database cannot back.
    * A member currently using another profile is never a reason to refuse: that is drift, not a
    * structural problem.
    */
  private def resolve(org: UUID, integration: Integration, input: FleetDesiredInput): Tx[FleetDesiredContent] = for {
    profile <- serverProfiles.profile(org, input.serverProfileId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_SERVER_PROFILE_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseWhen(profile.archived)(error("REMNAWAVE_FLEET_SERVER_PROFILE_ARCHIVED"))
    profileRevision <- serverProfiles.revision(org, input.serverProfileId, input.serverProfileRevisionNumber)
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_SERVER_REVISION_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseUnless(profileRevision.contentHash == profileRevision.content.hash)(
      error("REMNAWAVE_FLEET_SERVER_REVISION_NOT_FOUND"))
    // The configuration profile must be an adopted Remnawave one of this very integration.
    node <- inventory.findObject(org, integration.id, input.inventoryConfigProfileId, forUpdate = false)
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_CONFIG_PROFILE_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseUnless(node.objectType == IntegrationObjectType.ConfigProfile && node.isActive)(
      error("REMNAWAVE_FLEET_CONFIG_PROFILE_INACTIVE"))
    binding <- configProfiles.binding(org, integration.id, node.id).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_CONFIG_PROFILE_UNMANAGED")))
    managed <- configurations.find(org, binding.configurationProfileId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_CONFIG_PROFILE_UNMANAGED")))
    _ <- MonadThrow[Tx].raiseWhen(managed.archived)(error("REMNAWAVE_FLEET_CONFIG_PROFILE_ARCHIVED"))
    _ <- MonadThrow[Tx].raiseUnless(managed.kind == ConfigurationProfileKind.RemnawaveConfig)(
      error("REMNAWAVE_FLEET_CONFIG_PROFILE_UNMANAGED"))
    revisionView <- configurations.findRevision(org, binding.configurationProfileId, input.configRevisionNumber)
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_CONFIG_REVISION_NOT_FOUND")))
    hash <- configProfiles.revisionHash(org, binding.configurationProfileId, input.configRevisionNumber)
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_CONFIG_REVISION_NOT_FOUND")))
    // Every chosen inbound must really belong to the chosen profile; the client list proves nothing.
    inbounds = node.summary match {
      case value: RemnawaveConfigProfileSummary => value.inbounds.map(_.uuid).toSet
      case _ => Set.empty[String]
    }
    _ <- MonadThrow[Tx].raiseUnless(input.activeInboundIds.forall(id => inbounds(id.toString)))(
      error("REMNAWAVE_FLEET_INBOUND_INVALID"))
    externalId <- MonadThrow[Tx].fromOption(
      scala.util.Try(UUID.fromString(node.externalId)).toOption.filter(_.toString == node.externalId),
      error("REMNAWAVE_FLEET_CONFIG_PROFILE_NOT_FOUND"))
    content <- FleetDesiredContent(profile.id, profileRevision.id, profileRevision.number,
      profileRevision.contentHash, node.id, externalId, binding.configurationProfileId, revisionView.revision.id,
      input.configRevisionNumber, hash, input.activeInboundIds, input.nodePort, input.panelCidrs,
      input.desiredNodeState).normalized.leftMap(code => error(code)).liftTo[Tx]
  } yield content

  /** Reuses the same resolver at rollout admission; archived or re-bound managed objects are stale. */
  def pinnedContentAvailable(org: UUID, integrationId: UUID, content: FleetDesiredContent): Tx[Boolean] =
    (for {
      integration <- remnawave(org, integrationId)
      current <- resolve(org, integration, FleetDesiredInput(content.serverProfileId, content.serverProfileRevisionNumber,
        content.inventoryConfigProfileId, content.configRevisionNumber, content.activeInboundIds, content.nodePort,
        content.panelCidrs, content.desiredNodeState))
    } yield current == content).handleError(_ => false)

  def create(actor: ActorContext, integrationId: UUID, code: String, name: String, description: Option[String],
    input: FleetDesiredInput, memberNodeIds: List[UUID]): IO[RemnawaveFleet] = for {
    _ <- IO.raiseUnless(RemnawaveFleet.validCode(code) && RemnawaveFleet.validName(name) &&
      RemnawaveFleet.validDescription(description))(error("REMNAWAVE_FLEET_INVALID_INPUT"))
    _ <- IO.raiseUnless(memberNodeIds.distinct == memberNodeIds &&
      memberNodeIds.size <= RemnawaveFleet.MaxMembers)(error("REMNAWAVE_FLEET_INVALID_INPUT"))
    now <- IO.realTimeInstant
    created <- runner.run(for {
      integration <- remnawave(actor.organizationId, integrationId)
      content <- resolve(actor.organizationId, integration, input)
      fleetId <- UUID.randomUUID().pure[Tx]
      revisionId <- UUID.randomUUID().pure[Tx]
      fleet = RemnawaveFleet(fleetId, actor.organizationId, integrationId, code, name, description,
        None, 1L, archived = false, actor.userId, now, now)
      inserted <- repo.insertFleet(fleet)
      _ <- MonadThrow[Tx].raiseUnless(inserted)(error("REMNAWAVE_FLEET_CODE_TAKEN"))
      revision = RemnawaveFleetRevision(revisionId, actor.organizationId, fleetId, 1,
        FleetDesiredContentCodec.SchemaVersion, content.hash, content, actor.userId, now)
      _ <- repo.insertRevision(revision)
      // The first revision is what the fleet desires: a fleet without a desired state shows nothing.
      _ <- repo.promote(actor.organizationId, fleetId, revisionId, 1L, now)
      _ <- memberNodeIds.traverse_(node => attach(actor, integrationId, fleetId, node, now))
      _ <- audit.record(actor, AuditAction.RemnawaveFleetCreated, AuditTargetType.Integration, Some(integrationId))
      _ <- audit.record(actor, AuditAction.RemnawaveFleetRevisionCreated, AuditTargetType.Integration, Some(integrationId))
      stored <- loaded(actor.organizationId, integrationId, fleetId)
    } yield stored)
  } yield created

  def createRevision(actor: ActorContext, integrationId: UUID, fleetId: UUID, input: FleetDesiredInput,
    expectedVersion: Long): IO[RemnawaveFleetRevision] = IO.realTimeInstant.flatMap(now => runner.run(for {
    integration <- remnawave(actor.organizationId, integrationId)
    fleet <- repo.fleetForUpdate(actor.organizationId, integrationId, fleetId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseWhen(fleet.archived)(error("REMNAWAVE_FLEET_ARCHIVED"))
    _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
    content <- resolve(actor.organizationId, integration, input)
    number <- repo.nextRevisionNumber(actor.organizationId, fleetId)
    revision = RemnawaveFleetRevision(UUID.randomUUID(), actor.organizationId, fleetId, number,
      FleetDesiredContentCodec.SchemaVersion, content.hash, content, actor.userId, now)
    inserted <- repo.insertRevision(revision)
    _ <- MonadThrow[Tx].raiseUnless(inserted)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
    // Deliberately not promoted: the impact is reviewed first, then the pointer is moved.
    _ <- audit.record(actor, AuditAction.RemnawaveFleetRevisionCreated, AuditTargetType.Integration, Some(integrationId))
  } yield revision))

  /** Moves the desired pointer. Repeating it for the revision that is already desired changes
    * nothing and records nothing, so a double click is not two events.
    */
  def promote(actor: ActorContext, integrationId: UUID, fleetId: UUID, revisionId: UUID,
    expectedVersion: Long): IO[RemnawaveFleet] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- remnawave(actor.organizationId, integrationId)
    _ <- repo.lockFleet(actor.organizationId, fleetId)
    _ <- repo.rolloutActive(actor.organizationId, fleetId).flatMap(active =>
      MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE")))
    fleet <- repo.fleetForUpdate(actor.organizationId, integrationId, fleetId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseWhen(fleet.archived)(error("REMNAWAVE_FLEET_ARCHIVED"))
    revision <- repo.revision(actor.organizationId, fleetId, revisionId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_REVISION_NOT_FOUND")))
    result <- if (fleet.desiredRevisionId.contains(revision.id)) fleet.pure[Tx] else for {
      _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
      moved <- repo.promote(actor.organizationId, fleetId, revision.id, expectedVersion, now)
      _ <- MonadThrow[Tx].raiseUnless(moved)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
      // Every member now has to be judged against the new revision, and nothing else happens.
      _ <- repo.markDue(actor.organizationId, Some(fleetId), integrationId, now)
      _ <- audit.record(actor, AuditAction.RemnawaveFleetRevisionPromoted, AuditTargetType.Integration,
        Some(integrationId))
      stored <- loaded(actor.organizationId, integrationId, fleetId)
    } yield stored
  } yield result))

  private def attach(actor: ActorContext, integrationId: UUID, fleetId: UUID, inventoryNodeId: UUID,
    now: Instant): Tx[RemnawaveFleetMembership] = for {
    node <- inventory.findObject(actor.organizationId, integrationId, inventoryNodeId, forUpdate = false)
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_NODE_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseUnless(node.objectType == IntegrationObjectType.Node && node.isActive)(
      error("REMNAWAVE_FLEET_NODE_INACTIVE"))
    binding <- bindings.find(actor.organizationId, node.id).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_BINDING_REQUIRED")))
    resource <- bindings.resource(actor.organizationId, binding.resourceId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_RESOURCE_UNAVAILABLE")))
    _ <- MonadThrow[Tx].raiseUnless(resource.active && resource.resourceTypeCode == "NODE")(
      error("REMNAWAVE_FLEET_RESOURCE_UNAVAILABLE"))
    // Two owners would mean two conflicting desired states for the same node.
    existing <- repo.activeMembershipOf(actor.organizationId, integrationId, node.id)
    _ <- MonadThrow[Tx].raiseWhen(existing.nonEmpty)(error("REMNAWAVE_FLEET_NODE_ALREADY_MEMBER"))
    membership = RemnawaveFleetMembership(UUID.randomUUID(), actor.organizationId, fleetId, integrationId,
      node.id, binding.resourceId, 1L, actor.userId, now)
    inserted <- repo.insertMembership(membership, now)
    _ <- MonadThrow[Tx].raiseUnless(inserted)(error("REMNAWAVE_FLEET_NODE_ALREADY_MEMBER"))
    _ <- audit.record(actor, AuditAction.RemnawaveFleetMemberAdded, AuditTargetType.Integration, Some(integrationId))
  } yield membership

  /** Adding a member is metadata only. No profile is assigned, no desired state is set and no
    * configuration is deployed; the next assessment simply reports the difference.
    */
  def addMember(actor: ActorContext, integrationId: UUID, fleetId: UUID, inventoryNodeId: UUID,
    expectedVersion: Long): IO[RemnawaveFleetMembership] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- remnawave(actor.organizationId, integrationId)
    _ <- repo.lockFleet(actor.organizationId, fleetId)
    _ <- repo.rolloutActive(actor.organizationId, fleetId).flatMap(active =>
      MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE")))
    fleet <- loaded(actor.organizationId, integrationId, fleetId)
    _ <- MonadThrow[Tx].raiseWhen(fleet.archived)(error("REMNAWAVE_FLEET_ARCHIVED"))
    _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
    members <- repo.members(actor.organizationId, fleetId)
    _ <- MonadThrow[Tx].raiseWhen(members.count(_.active) >= RemnawaveFleet.MaxMembers)(
      error("REMNAWAVE_FLEET_TOO_MANY_MEMBERS"))
    membership <- attach(actor, integrationId, fleetId, inventoryNodeId, now)
  } yield membership))

  /** Removing a member ends the fleet's ownership of its desired state and touches nothing remote:
    * the node is not disabled, unbound, re-firewalled or deleted.
    */
  def removeMember(actor: ActorContext, integrationId: UUID, fleetId: UUID, membershipId: UUID,
    expectedVersion: Long): IO[Unit] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- remnawave(actor.organizationId, integrationId)
    _ <- repo.lockFleet(actor.organizationId, fleetId)
    _ <- repo.rolloutActive(actor.organizationId, fleetId).flatMap(active =>
      MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE")))
    fleet <- loaded(actor.organizationId, integrationId, fleetId)
    _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
    membership <- repo.membership(actor.organizationId, fleetId, membershipId).flatMap(
      _.liftTo[Tx](error("REMNAWAVE_FLEET_MEMBER_NOT_FOUND")))
    _ <- MonadThrow[Tx].raiseUnless(membership.active)(error("REMNAWAVE_FLEET_MEMBER_NOT_FOUND"))
    removed <- repo.removeMembership(actor.organizationId, fleetId, membershipId, now)
    _ <- MonadThrow[Tx].raiseUnless(removed)(error("REMNAWAVE_FLEET_MEMBER_NOT_FOUND"))
    _ <- audit.record(actor, AuditAction.RemnawaveFleetMemberRemoved, AuditTargetType.Integration, Some(integrationId))
  } yield ()))

  def update(actor: ActorContext, integrationId: UUID, fleetId: UUID, name: String, description: Option[String],
    expectedVersion: Long): IO[RemnawaveFleet] = for {
    _ <- IO.raiseUnless(RemnawaveFleet.validName(name) && RemnawaveFleet.validDescription(description))(
      error("REMNAWAVE_FLEET_INVALID_INPUT"))
    now <- IO.realTimeInstant
    result <- runner.run(for {
      _ <- remnawave(actor.organizationId, integrationId)
      fleet <- repo.fleetForUpdate(actor.organizationId, integrationId, fleetId).flatMap(
        _.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))
      _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
      saved <- repo.updateFleet(fleet.copy(name = name, description = description), expectedVersion, now)
      _ <- MonadThrow[Tx].raiseUnless(saved)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
      _ <- audit.record(actor, AuditAction.RemnawaveFleetUpdated, AuditTargetType.Integration, Some(integrationId))
      stored <- loaded(actor.organizationId, integrationId, fleetId)
    } yield stored)
  } yield result

  /** Archiving is metadata only, in one transaction and with no external call. It also ends the
    * fleet's ownership of its nodes: an archived fleet desires nothing, so each node is free to
    * join another active fleet. The membership rows stay as history and nothing remote is touched -
    * no node is disabled, unbound, re-firewalled, redeployed or unassigned.
    */
  def archive(actor: ActorContext, integrationId: UUID, fleetId: UUID, expectedVersion: Long): IO[RemnawaveFleet] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- remnawave(actor.organizationId, integrationId)
      _ <- repo.lockFleet(actor.organizationId, fleetId)
      _ <- repo.rolloutActive(actor.organizationId, fleetId).flatMap(active =>
        MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE")))
      fleet <- repo.fleetForUpdate(actor.organizationId, integrationId, fleetId).flatMap(
        _.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))
      // Archiving twice is the same archived fleet: no second release and no second journal entry.
      result <- if (fleet.archived) fleet.pure[Tx] else for {
        _ <- MonadThrow[Tx].raiseUnless(fleet.version == expectedVersion)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
        saved <- repo.updateFleet(fleet.copy(archived = true), expectedVersion, now)
        _ <- MonadThrow[Tx].raiseUnless(saved)(error("REMNAWAVE_FLEET_VERSION_CONFLICT"))
        _ <- repo.releaseMemberships(actor.organizationId, fleetId, now)
        _ <- audit.record(actor, AuditAction.RemnawaveFleetArchived, AuditTargetType.Integration, Some(integrationId))
        stored <- loaded(actor.organizationId, integrationId, fleetId)
      } yield stored
    } yield result))

  /** An observation action. It asks the existing synchronization scheduler for one integration-level
    * run - never one per member - and marks the fleet's members due. It applies nothing.
    */
  def refresh(actor: ActorContext, integrationId: UUID, fleetId: UUID): IO[Int] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- remnawave(actor.organizationId, integrationId)
      _ <- loaded(actor.organizationId, integrationId, fleetId)
      _ <- syncState.scheduleAt(actor.organizationId, integrationId, now)
      marked <- repo.markDue(actor.organizationId, Some(fleetId), integrationId, now)
    } yield marked))

  def list(org: UUID, integrationId: UUID, archived: Boolean): IO[List[(RemnawaveFleet, RemnawaveFleetSummary)]] =
    runner.run(for {
      _ <- remnawave(org, integrationId)
      fleets <- repo.fleets(org, integrationId, archived, 200)
      summaries <- query.summaries(org, integrationId, fleets.map(_.id))
    } yield fleets.map(fleet => fleet -> summaries.getOrElse(fleet.id, emptySummary)))

  def detail(org: UUID, integrationId: UUID, fleetId: UUID): IO[FleetDetail] = runner.run(for {
    _ <- remnawave(org, integrationId)
    fleet <- loaded(org, integrationId, fleetId)
    desired <- fleet.desiredRevisionId.flatTraverse(id => repo.revision(org, fleetId, id))
    revisions <- repo.revisions(org, fleetId, 50)
    rows <- query.memberRows(org, fleetId)
    summaries <- query.summaries(org, integrationId, List(fleetId))
    serverName <- desired.flatTraverse(value => query.serverProfileName(org, value.content.serverProfileId))
    configName <- desired.flatTraverse(value => query.configurationProfileName(org, value.content.configurationProfileId))
  } yield FleetDetail(fleet, desired, revisions, rows, summaries.getOrElse(fleetId, emptySummary),
    serverName, configName))

  def candidates(org: UUID, integrationId: UUID): IO[List[FleetCandidate]] = runner.run(for {
    _ <- remnawave(org, integrationId)
    rows <- query.candidates(org, integrationId, 500)
  } yield rows)

  /** A read-only estimate of what promoting a candidate revision would mean. It moves no pointer,
    * starts no deployment and opens no connection: only the dimensions the database already knows
    * are counted, which is why the numbers are an estimate.
    */
  def preview(org: UUID, integrationId: UUID, fleetId: UUID, revisionId: UUID): IO[FleetRevisionImpact] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- remnawave(org, integrationId)
      fleet <- loaded(org, integrationId, fleetId)
      candidate <- repo.revision(org, fleetId, revisionId).flatMap(
        _.liftTo[Tx](error("REMNAWAVE_FLEET_REVISION_NOT_FOUND")))
      current <- fleet.desiredRevisionId.flatTraverse(id => repo.revision(org, fleetId, id))
      members <- repo.members(org, fleetId).map(_.filter(_.active))
      evidence <- members.traverse(member => query.storedEvidence(org, member.id, candidate))
      candidateServer <- query.serverProfileName(org, candidate.content.serverProfileId)
      candidateConfig <- query.configurationProfileName(org, candidate.content.configurationProfileId)
      currentServer <- current.flatTraverse(value => query.serverProfileName(org, value.content.serverProfileId))
      currentConfig <- current.flatTraverse(value =>
        query.configurationProfileName(org, value.content.configurationProfileId))
    } yield {
      val verdicts = evidence.flatten.map { stored =>
        FleetAssessor.assess(RemnawaveFleetObserver.evidenceOf(stored, candidate.content, None, None,
          localObservationFailed = false, trustedSsh = true), now, settings.staleAfter, localDimensions = false)
      }
      FleetRevisionImpact(current, candidate, currentServer, candidateServer.getOrElse(""), currentConfig,
        candidateConfig.getOrElse(""), members.size,
        verdicts.count(_.compliance == FleetCompliance.Compliant),
        verdicts.count(_.compliance == FleetCompliance.Drifted),
        verdicts.count(_.compliance == FleetCompliance.Unknown) + (members.size - verdicts.size),
        verdicts.count(_.compliance == FleetCompliance.Blocked))
    }))
}

final case class FleetDetail(fleet: RemnawaveFleet, desired: Option[RemnawaveFleetRevision],
  revisions: List[RemnawaveFleetRevision], members: List[FleetMemberRow], summary: RemnawaveFleetSummary,
  serverProfileName: Option[String], configurationProfileName: Option[String])

object RemnawaveFleets {
  val emptySummary: RemnawaveFleetSummary = RemnawaveFleetSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, None, None, None)

  def messageFor(code: String): String = code match {
    case "INTEGRATION_NOT_FOUND" => "Integration was not found"
    case "REMNAWAVE_FLEET_NOT_FOUND" => "Fleet was not found"
    case "REMNAWAVE_FLEET_REVISION_NOT_FOUND" => "Fleet revision was not found"
    case "REMNAWAVE_FLEET_MEMBER_NOT_FOUND" => "Fleet member was not found"
    case "REMNAWAVE_FLEET_VERSION_CONFLICT" => "The fleet changed meanwhile; reload and try again"
    case "REMNAWAVE_FLEET_CODE_TAKEN" => "A fleet with this code already exists"
    case "REMNAWAVE_FLEET_ROLLOUT_ACTIVE" => "A rollout is in progress for this fleet"
    case "REMNAWAVE_FLEET_NODE_ALREADY_MEMBER" => "This node already belongs to another fleet"
    case _ => "The fleet request could not be completed"
  }
}
