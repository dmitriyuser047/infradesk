package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

final case class FleetRolloutSettings(planTtl: FiniteDuration, staleAfter: FiniteDuration, maxWaveSize: Int)

sealed trait RolloutPreview
object RolloutPreview {
  final case class Ready(plan: RemnawaveFleetRollout, warnings: List[RolloutIssue], estimatedMutations: Int)
    extends RolloutPreview
  final case class RefreshRequired(issues: List[RolloutIssue]) extends RolloutPreview
  final case class Blocked(issues: List[RolloutIssue], warnings: List[RolloutIssue]) extends RolloutPreview
}

final case class RolloutDetail(rollout: RemnawaveFleetRollout, members: List[RemnawaveFleetRolloutMember],
  actions: List[RemnawaveFleetRolloutAction])

/** Preview, start and control of Remnawave fleet rollouts. It never mutates a remote system: it writes
  * the plan and the control markers that the rollout worker acts on.
  */
final class RemnawaveFleetRollouts[Tx[_]: MonadThrow](
  fleets: RemnawaveFleetRepository[Tx], query: RemnawaveFleetQuery[Tx], rollouts: RemnawaveFleetRolloutRepository[Tx],
  integrations: IntegrationRepository[Tx], inventory: IntegrationInventoryRepository[Tx],
  bindings: IntegrationBindingRepository[Tx], children: FleetRolloutChildren, audit: AuditRecorder[Tx],
  runner: TransactionRunner[IO, Tx], settings: FleetRolloutSettings,
  managedContentAvailable: Option[(UUID, UUID, FleetDesiredContent) => Tx[Boolean]] = None,
  sourcesAvailable: Option[(UUID, List[FleetRolloutMemberPlan]) => Tx[Boolean]] = None) {
  import RemnawaveFleetRollouts._

  private def error(code: String) = IntegrationError(code, messageFor(code))

  private def remnawave(org: UUID, integrationId: UUID): Tx[Integration] =
    integrations.findById(org, integrationId).flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND"))).flatTap(value =>
      MonadThrow[Tx].raiseUnless(value.providerType == IntegrationProviderType.Remnawave)(
        error("REMNAWAVE_FLEET_PROVIDER_UNSUPPORTED")))

  private def loadedFleet(org: UUID, integrationId: UUID, fleetId: UUID) =
    fleets.fleet(org, integrationId, fleetId).flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_NOT_FOUND")))

  private def loadedRollout(org: UUID, integrationId: UUID, fleetId: UUID, id: UUID): Tx[RemnawaveFleetRollout] =
    rollouts.rollout(org, fleetId, id).map(_.filter(_.integrationId == integrationId))
      .flatMap(_.liftTo[Tx](error("REMNAWAVE_FLEET_ROLLOUT_NOT_FOUND")))

  def preview(actor: ActorContext, integrationId: UUID, fleetId: UUID,
    input: RolloutPreviewInput): IO[RolloutPreview] = {
    val org = actor.organizationId
    for {
      now <- IO.realTimeInstant
      loaded <- runner.run(for {
        integration <- remnawave(org, integrationId)
        fleet <- loadedFleet(org, integrationId, fleetId)
        revision <- fleets.revision(org, fleetId, input.revisionId).flatMap(
          _.liftTo[Tx](error("REMNAWAVE_FLEET_REVISION_NOT_FOUND")))
        rows <- query.memberRows(org, fleetId)
        sync <- query.lastSuccessfulSyncAt(org, integrationId)
        nodes <- rows.traverse(row => inventory.findObject(org, integrationId, row.membership.inventoryNodeId,
          forUpdate = false))
      } yield (integration, fleet, revision, rows.filter(_.membership.active), sync, nodes.flatten))
      (integration, fleet, revision, rows, sync, nodes) = loaded
      facts <- rows.parTraverseN(8) { row =>
        val externalId = nodes.find(_.id == row.membership.inventoryNodeId).map(_.externalId)
        for {
          assignment <- children.assignment(org, row.membership.resourceId)
          desired <- children.desiredRecord(org, integrationId, row.membership.inventoryNodeId)
          source <- children.sshSource(org, row.membership.resourceId)
        } yield RolloutMemberFacts(row, externalId.getOrElse(""), assignment, desired, source)
      }
      anyConfigDrift = facts.exists(_.row.assessment.exists(_.driftReasons.contains(FleetDriftReason.ConfigRevisionDrift)))
      sharedResult <- if (!anyConfigDrift) IO.pure((None, None)) else
        children.sharedImpact(org, integrationId, revision.content.inventoryConfigProfileId,
          revision.content.configRevisionNumber).map(value => (value, Option.empty[String])).handleError(e =>
          (None, Some(FleetRolloutChildren.codeOf(e) match {
            case "REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED" => "REMNAWAVE_FLEET_ROLLOUT_SHARED_CONFIG_UNAVAILABLE"
            case code => code
          })))
      consumers <- if (sharedResult._1.isEmpty) IO.pure(List.empty[FleetRolloutConfigConsumer])
        else runner.run(query.configConsumers(org, integrationId, revision.content.externalConfigProfileId.toString))
      outcome = RemnawaveFleetRolloutPlanner.plan(fleet, revision, integration, facts, sync, sharedResult._1,
        sharedResult._2, input, now, settings.staleAfter, settings.maxWaveSize)
      result <- outcome match {
        case RolloutPlanOutcome.RefreshRequired(issues) => IO.pure[RolloutPreview](RolloutPreview.RefreshRequired(issues))
        case RolloutPlanOutcome.Blocked(issues, warnings) => IO.pure[RolloutPreview](RolloutPreview.Blocked(issues, warnings))
        case RolloutPlanOutcome.Ready(_, warnings) if consumers.exists(n => !n.disabled && !n.connected &&
          !rows.exists(_.membership.inventoryNodeId == n.inventoryNodeId)) =>
          IO.pure[RolloutPreview](RolloutPreview.Blocked(List(RolloutIssue(
            "REMNAWAVE_FLEET_SHARED_CONFIG_EXTERNAL_DEPENDENCY")), warnings))
        case RolloutPlanOutcome.Ready(_, _) if consumers.exists(n => !n.observedAt.isAfter(now.minusMillis(settings.staleAfter.toMillis))) =>
          IO.pure[RolloutPreview](RolloutPreview.RefreshRequired(consumers.filter(n =>
            !n.observedAt.isAfter(now.minusMillis(settings.staleAfter.toMillis))).map(n => RolloutIssue("REFRESH_REQUIRED", Some(n.nodeName)))))
        case RolloutPlanOutcome.Ready(rawSnapshot, warnings) =>
          val snapshot = rawSnapshot.copy(shared = rawSnapshot.shared.copy(consumers = consumers))
          val id = UUID.randomUUID()
          val plan = RemnawaveFleetRollout(id, org, integrationId, fleetId, revision.id, None, FleetRolloutState.Planned,
            FleetRolloutPhase.Validate, snapshot, snapshot.hash, 0, snapshot.waveCount, input.pauseAfterCanary,
            input.automaticRollback, FleetRollbackScope.CurrentWave, actor.userId, now,
            now.plusMillis(settings.planTtl.toMillis), None, None, None, None, rollbackIncomplete = false, None, None,
            None, None, None, None, now, now, None, 1L, now)
          val members = snapshot.members.map { m =>
            RemnawaveFleetRolloutMember(UUID.randomUUID(), org, id, fleetId, m.membershipId, m.membershipVersion,
              m.inventoryNodeId, m.resourceId, m.externalNodeId, m.wave, m.position,
              if (m.mutable) FleetRolloutMemberState.Pending else FleetRolloutMemberState.Skipped, m.skipReason,
              m.actions, None, None, None, None, None, 1L, now)
          }
          runner.run(rollouts.insertPlan(plan, members)).as[RolloutPreview](
            RolloutPreview.Ready(plan, warnings, RemnawaveFleetRolloutPlanner.estimatedMutations(snapshot)))
      }
    } yield result
  }

  /** Rechecks current Stage25D observations even while the approved snapshot is still unexpired. */
  def admissionEvidence(org: UUID, rollout: RemnawaveFleetRollout, now: Instant): Tx[Option[String]] = for {
    rows <- query.memberRows(org, rollout.fleetId)
    members <- rollouts.members(rollout.id)
    sync <- query.lastSuccessfulSyncAt(org, rollout.integrationId)
    consumers <- if (rollout.snapshot.shared.required)
      query.configConsumers(org, rollout.integrationId, rollout.snapshot.content.externalConfigProfileId.toString)
      else List.empty[FleetRolloutConfigConsumer].pure[Tx]
  } yield FleetRolloutPreconditions.members(rollout.snapshot, rows,
    members.filter(_.state == FleetRolloutMemberState.Succeeded).map(_.membershipId).toSet, sync, now, settings.staleAfter)
    .orElse(Option.when(rollout.snapshot.shared.required)(
      FleetRolloutPreconditions.consumers(rollout.snapshot, consumers, now, settings.staleAfter)).flatten)

  /** Used only before the first Config child; recovery must observe the existing child instead. */
  def sharedAdmission(org: UUID, rollout: RemnawaveFleetRollout,
    impact: Option[IntegrationConfigRolloutPreview], now: Instant): Tx[Option[String]] = for {
    evidence <- admissionEvidence(org, rollout, now)
    consumers <- query.configConsumers(org, rollout.integrationId, rollout.snapshot.content.externalConfigProfileId.toString)
    config <- inventory.findObject(org, rollout.integrationId, rollout.snapshot.content.inventoryConfigProfileId, forUpdate = false)
    hash = config.filter(n => n.isActive && n.externalId == rollout.snapshot.content.externalConfigProfileId.toString).flatMap(_.summary match {
      case p: RemnawaveConfigProfileSummary => p.configSha256
      case _ => None
    })
  } yield evidence.orElse(FleetRolloutPreconditions.consumers(rollout.snapshot, consumers, now, settings.staleAfter))
    .orElse(Option.when(config.exists(n => !n.lastSeenAt.isAfter(now.minusMillis(settings.staleAfter.toMillis)) ||
      n.lastSeenAt.isAfter(now)))(FleetRolloutPreconditions.RefreshRequired))
    .orElse(FleetRolloutPreconditions.impact(rollout.snapshot, consumers, impact, hash))

  /** Why current admission evidence or immutable snapshot pins no longer permit execution. */
  def drift(org: UUID, rollout: RemnawaveFleetRollout, now: Instant): Tx[Option[String]] =
    admissionEvidence(org, rollout, now).flatMap {
      case Some(code) => Option(code).pure[Tx]
      case None => snapshotDrift(org, rollout)
    }

  private def snapshotDrift(org: UUID, rollout: RemnawaveFleetRollout): Tx[Option[String]] = {
    val snapshot = rollout.snapshot
    for {
      integration <- integrations.findById(org, rollout.integrationId)
      fleet <- fleets.fleet(org, rollout.integrationId, rollout.fleetId)
      revision <- fleets.revision(org, rollout.fleetId, snapshot.revisionId)
      activeMembers <- fleets.members(org, rollout.fleetId).map(_.filter(_.active))
      managed <- managedContentAvailable.fold(true.pure[Tx])(_(org, rollout.integrationId, snapshot.content))
      sources <- sourcesAvailable.fold(true.pure[Tx])(_(org, snapshot.members))
      consumers <- if (!snapshot.shared.required) List.empty[FleetRolloutConfigConsumer].pure[Tx]
        else query.configConsumers(org, rollout.integrationId, snapshot.content.externalConfigProfileId.toString)
      config <- inventory.findObject(org, rollout.integrationId, snapshot.content.inventoryConfigProfileId, forUpdate = false)
      memberDrift <- snapshot.members.traverse { m =>
        for {
          membership <- fleets.membership(org, rollout.fleetId, m.membershipId)
          node <- inventory.findObject(org, rollout.integrationId, m.inventoryNodeId, forUpdate = false)
          binding <- bindings.find(org, m.inventoryNodeId)
          evidence <- if (rollout.phase != FleetRolloutPhase.Validate) Option.empty[FleetStoredEvidence].pure[Tx]
            else revision.flatTraverse(r => query.storedEvidence(org, m.membershipId, r))
        } yield membership.exists(v => v.active && v.version == m.membershipVersion &&
          v.resourceId == m.resourceId && v.inventoryNodeId == m.inventoryNodeId) &&
          node.exists(n => n.isActive && n.externalId == m.externalNodeId) && binding.exists(_.resourceId == m.resourceId) &&
          (rollout.phase != FleetRolloutPhase.Validate || evidence.exists(e =>
            e.assignment.map(a => a.profileId -> a.revisionNumber) ==
              m.baseline.assignmentProfileId.zip(m.baseline.assignmentRevisionNumber).headOption &&
            e.desiredStateRecord.map(_.state) == m.baseline.desiredState))
      }
    } yield {
      val needsManagement = snapshot.members.exists(_.actions.contains(FleetActionKind.DesiredState))
      val intact = managed && sources && snapshot.hash == rollout.snapshotHash &&
        config.exists(n => n.isActive && n.externalId == snapshot.content.externalConfigProfileId.toString) &&
        (!snapshot.shared.required || (consumers.map(_.inventoryNodeId).toSet == snapshot.shared.consumers.map(_.inventoryNodeId).toSet &&
          config.exists(n => (n.summary match {
            case p: RemnawaveConfigProfileSummary => p.configSha256
            case _ => None
          }).exists(hash =>
            snapshot.shared.baselineHash.contains(hash) || (rollout.phase != FleetRolloutPhase.Validate &&
              hash == snapshot.shared.revisionHash))))) &&
        activeMembers.map(_.id).toSet == snapshot.members.map(_.membershipId).toSet &&
        integration.exists(i => i.enabled && i.updatedAt == snapshot.integrationPin &&
          (!needsManagement || i.managementMode == IntegrationManagementMode.ManagedSelected)) &&
        fleet.exists(f => !f.archived && f.desiredRevisionId.contains(snapshot.revisionId)) &&
        revision.exists(r => r.contentHash == snapshot.revisionHash && r.content.hash == snapshot.content.hash) &&
        memberDrift.forall(identity)
      Option.unless(intact)("REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED")
    }
  }

  def start(actor: ActorContext, integrationId: UUID, fleetId: UUID, planId: UUID,
    requestId: UUID): IO[RemnawaveFleetRollout] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- remnawave(actor.organizationId, integrationId)
    _ <- fleets.lockFleet(actor.organizationId, fleetId)
    existing <- rollouts.byRequest(actor.organizationId, requestId)
    result <- existing match {
      case Some(value) =>
        MonadThrow[Tx].raiseUnless(value.id == planId && value.fleetId == fleetId && value.integrationId == integrationId)(error("REMNAWAVE_FLEET_ROLLOUT_REQUEST_REUSED"))
          .as(value)
      case None => for {
        plan <- loadedRollout(actor.organizationId, integrationId, fleetId, planId)
        _ <- MonadThrow[Tx].raiseUnless(plan.state == FleetRolloutState.Planned)(error("REMNAWAVE_FLEET_ROLLOUT_PLAN_USED"))
        _ <- MonadThrow[Tx].raiseUnless(plan.expiresAt.isAfter(now))(error("REMNAWAVE_FLEET_ROLLOUT_PLAN_EXPIRED"))
        active <- fleets.rolloutActive(actor.organizationId, fleetId)
        _ <- MonadThrow[Tx].raiseWhen(active)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE"))
        changed <- drift(actor.organizationId, plan, now)
        _ <- changed.traverse_(code => MonadThrow[Tx].raiseError[Unit](error(code)))
        started <- rollouts.start(actor.organizationId, planId, requestId, now)
        _ <- MonadThrow[Tx].raiseUnless(started)(error("REMNAWAVE_FLEET_ROLLOUT_ACTIVE"))
        _ <- audit.record(actor, AuditAction.RemnawaveFleetRolloutRequested, AuditTargetType.Integration, Some(integrationId))
        stored <- loadedRollout(actor.organizationId, integrationId, fleetId, planId)
      } yield stored
    }
  } yield result))

  def detail(org: UUID, integrationId: UUID, fleetId: UUID, id: UUID): IO[RolloutDetail] = runner.run(for {
    _ <- remnawave(org, integrationId)
    rollout <- loadedRollout(org, integrationId, fleetId, id)
    members <- rollouts.members(id)
    actions <- rollouts.actions(id)
  } yield RolloutDetail(rollout, members, actions))

  def history(org: UUID, integrationId: UUID, fleetId: UUID): IO[List[RemnawaveFleetRollout]] = runner.run(for {
    _ <- remnawave(org, integrationId)
    _ <- loadedFleet(org, integrationId, fleetId)
    items <- rollouts.history(org, fleetId, 50)
  } yield items)

  def active(org: UUID, integrationId: UUID, fleetId: UUID): IO[Option[RemnawaveFleetRollout]] = runner.run(for {
    _ <- remnawave(org, integrationId)
    _ <- loadedFleet(org, integrationId, fleetId)
    value <- rollouts.activeOf(org, fleetId)
  } yield value)

  def pause(actor: ActorContext, integrationId: UUID, fleetId: UUID, id: UUID): IO[RemnawaveFleetRollout] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- remnawave(actor.organizationId, integrationId)
      current <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
      result <- current.state match {
        case FleetRolloutState.Paused => current.pure[Tx]
        case FleetRolloutState.Queued | FleetRolloutState.Running =>
          if (current.pauseRequestedAt.nonEmpty) current.pure[Tx] else for {
            _ <- MonadThrow[Tx].raiseWhen(current.rollbackRequestedAt.nonEmpty)(error("REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSABLE"))
            changed <- rollouts.requestPause(actor.organizationId, id, actor.userId, now)
            _ <- MonadThrow[Tx].raiseUnless(changed)(error("REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSABLE"))
            _ <- audit.record(actor, AuditAction.RemnawaveFleetRolloutPaused, AuditTargetType.Integration, Some(integrationId))
            stored <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
          } yield stored
        case state if state.terminal => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_TERMINAL"))
        case _ => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSABLE"))
      }
    } yield result))

  def resume(actor: ActorContext, integrationId: UUID, fleetId: UUID, id: UUID): IO[RemnawaveFleetRollout] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- remnawave(actor.organizationId, integrationId)
      _ <- fleets.lockFleet(actor.organizationId, fleetId)
      current <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
      result <- current.state match {
        case FleetRolloutState.Paused => for {
          changed <- drift(actor.organizationId, current, now)
          _ <- changed.traverse_(code => MonadThrow[Tx].raiseError[Unit](error(code)))
          moved <- rollouts.resume(actor.organizationId, id, now)
          _ <- MonadThrow[Tx].raiseUnless(moved)(error("REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSED"))
          _ <- audit.record(actor, AuditAction.RemnawaveFleetRolloutResumed, AuditTargetType.Integration, Some(integrationId))
          stored <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
        } yield stored
        case FleetRolloutState.Queued | FleetRolloutState.Running | FleetRolloutState.RollingBack => current.pure[Tx]
        case state if state.terminal => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_TERMINAL"))
        case _ => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSED"))
      }
    } yield result))

  def rollback(actor: ActorContext, integrationId: UUID, fleetId: UUID, id: UUID,
    scope: FleetRollbackScope): IO[RemnawaveFleetRollout] = IO.realTimeInstant.flatMap(now => runner.run(for {
    _ <- remnawave(actor.organizationId, integrationId)
    _ <- fleets.lockFleet(actor.organizationId, fleetId)
    current <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
    result <- current.state match {
      case FleetRolloutState.RollingBack => current.pure[Tx]
      case FleetRolloutState.Queued | FleetRolloutState.Running | FleetRolloutState.Paused =>
        if (current.rollbackRequestedAt.nonEmpty) current.pure[Tx] else for {
          moved <- rollouts.requestRollback(actor.organizationId, id, actor.userId, scope, now)
          _ <- MonadThrow[Tx].raiseUnless(moved)(error("REMNAWAVE_FLEET_ROLLOUT_NOT_ROLLBACKABLE"))
          _ <- audit.record(actor, AuditAction.RemnawaveFleetRolloutRollbackRequested, AuditTargetType.Integration,
            Some(integrationId))
          stored <- loadedRollout(actor.organizationId, integrationId, fleetId, id)
        } yield stored
      case state if state.terminal => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_TERMINAL"))
      case _ => MonadThrow[Tx].raiseError[RemnawaveFleetRollout](error("REMNAWAVE_FLEET_ROLLOUT_NOT_ROLLBACKABLE"))
    }
  } yield result))

  /** Recorded by the worker when a rollout reaches a terminal state. */
  def recordOutcome(rollout: RemnawaveFleetRollout): IO[Unit] = {
    val actor = ActorContext(rollout.createdBy, rollout.organizationId)
    val action = if (rollout.state == FleetRolloutState.Succeeded) AuditAction.RemnawaveFleetRolloutCompleted
    else AuditAction.RemnawaveFleetRolloutFailed
    runner.run(audit.record(actor, action, AuditTargetType.Integration, Some(rollout.integrationId)))
  }

  def cleanup(now: Instant, limit: Int): IO[Int] = runner.run(rollouts.purgeExpired(now, limit))
}

object RemnawaveFleetRollouts {
  def messageFor(code: String): String = code match {
    case "INTEGRATION_NOT_FOUND" => "Integration was not found"
    case "REMNAWAVE_FLEET_NOT_FOUND" => "Fleet was not found"
    case "REMNAWAVE_FLEET_REVISION_NOT_FOUND" => "Fleet revision was not found"
    case "REMNAWAVE_FLEET_ROLLOUT_NOT_FOUND" => "Rollout was not found"
    case "REMNAWAVE_FLEET_ROLLOUT_ACTIVE" => "A rollout is already in progress for this fleet"
    case "REMNAWAVE_FLEET_ROLLOUT_PLAN_CHANGED" => "The fleet or its nodes changed since the preview; preview again"
    case "REMNAWAVE_FLEET_ROLLOUT_REFRESH_REQUIRED" => "Fresh fleet evidence is required; refresh the fleet and try again"
    case "REMNAWAVE_FLEET_ROLLOUT_PLAN_EXPIRED" => "The preview expired; preview again"
    case "REMNAWAVE_FLEET_ROLLOUT_PLAN_USED" => "This preview was already started"
    case "REMNAWAVE_FLEET_ROLLOUT_REQUEST_REUSED" => "This request id belongs to another rollout"
    case "REMNAWAVE_FLEET_ROLLOUT_TERMINAL" => "The rollout already finished"
    case "REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSABLE" => "The rollout cannot be paused now"
    case "REMNAWAVE_FLEET_ROLLOUT_NOT_PAUSED" => "The rollout is not paused"
    case "REMNAWAVE_FLEET_ROLLOUT_NOT_ROLLBACKABLE" => "The rollout cannot be rolled back now"
    case _ => "The rollout request could not be completed"
  }
}
