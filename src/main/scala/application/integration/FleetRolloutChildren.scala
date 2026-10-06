package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.port._
import application.provisioning.{ProvisioningError, ProvisioningRuns, ServerProfileError, ServerProfiles}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import domain.provisioning.ProvisioningRunState
import java.time.Instant
import java.util.UUID

sealed trait ChildOutcome
object ChildOutcome {
  case object Running extends ChildOutcome
  case object Succeeded extends ChildOutcome
  final case class Failed(code: String) extends ChildOutcome
  final case class Unknown(code: String) extends ChildOutcome
}

/** The only operations a rollout may use to change things. Each one is an existing, typed Stage24/25
  * operation; there is no shell, no script and no new transport here.
  */
trait FleetRolloutChildren {
  /** A child transaction is admitted only under this exact, live parent claim. */
  def owned(rolloutId: UUID, token: UUID): FleetRolloutChildren = this
  def assignment(org: UUID, resourceId: UUID): IO[Option[(UUID, Int)]]
  def sshSource(org: UUID, resourceId: UUID): IO[Option[(UUID, Instant)]]
  def sshSources(org: UUID, resourceIds: List[UUID]): IO[Map[UUID, (UUID, Instant)]] =
    resourceIds.distinct.traverse(id => sshSource(org, id).map(_.map(id -> _))).map(_.flatten.toMap)
  def assign(actor: ActorContext, resourceId: UUID, profileId: UUID, revisionNumber: Int): IO[Unit]
  def unassign(actor: ActorContext, resourceId: UUID): IO[Unit]
  /** Left is the first blocking problem; Right is the id of the PLANNED run. Mutates nothing remote. */
  def previewApply(actor: ActorContext, resourceId: UUID): IO[Either[String, UUID]]
  def startApply(actor: ActorContext, planId: UUID, requestId: UUID): IO[UUID]
  def applyOutcome(org: UUID, runId: UUID): IO[ChildOutcome]
  def managedCidrs(org: UUID, integrationId: UUID, resourceId: UUID, inventoryNodeId: UUID,
    nodePort: Int): IO[Either[String, List[String]]]
  def reconcileFirewall(org: UUID, integrationId: UUID, resourceId: UUID, inventoryNodeId: UUID, nodePort: Int,
    cidrs: List[String]): IO[ChildOutcome]
  def desiredRecord(org: UUID, integrationId: UUID, inventoryNodeId: UUID): IO[Option[IntegrationDesiredNodeState]]
  def desiredView(org: UUID, integrationId: UUID, inventoryNodeId: UUID): IO[Option[DesiredStateView]]
  def setDesired(actor: ActorContext, integrationId: UUID, inventoryNodeId: UUID,
    state: IntegrationDesiredNodeState): IO[Unit]
  def removeDesired(actor: ActorContext, integrationId: UUID, inventoryNodeId: UUID): IO[Unit]
  def sharedImpact(org: UUID, integrationId: UUID, objectId: UUID,
    revisionNumber: Int): IO[Option[IntegrationConfigRolloutPreview]]
  def startConfig(actor: ActorContext, integrationId: UUID, objectId: UUID, revisionNumber: Int,
    requestId: UUID): IO[UUID]
  def configOutcome(org: UUID, id: UUID): IO[ChildOutcome]
  def configByRequest(org: UUID, requestId: UUID): IO[Option[UUID]]
  def verifyRestored(actor: ActorContext, integrationId: UUID, fleetId: UUID, plan: FleetRolloutMemberPlan,
    kinds: Set[FleetActionKind], previousCidrs: Option[List[String]], nodePort: Int, after: Instant): IO[ChildOutcome]
  /** Asks the existing scheduler for a fresh synchronization and marks the fleet due for assessment. */
  def refresh(org: UUID, integrationId: UUID, fleetId: UUID, at: Instant): IO[Unit]
}

object FleetRolloutChildren {
  /** A stable, secret-free code for whatever a child raised. */
  def codeOf(error: Throwable): String = error match {
    case IntegrationError(code, _) => code
    case ServerProfileError(code, _) => code
    case e: ProvisioningError => e.code
    case _ => "REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED"
  }
}

final class LiveFleetRolloutChildren[Tx[_]: MonadThrow](
  serverProfiles: ServerProfiles[IO, Tx], provisioning: ProvisioningRuns[IO, Tx],
  configRollouts: IntegrationConfigRollouts[Tx], desiredStates: IntegrationDesiredStates[Tx],
  desired: IntegrationDesiredStateRepository[Tx], assignments: ServerProfileRepository[Tx],
  targets: ProvisioningTargetQuery[Tx], fleetQuery: RemnawaveFleetQuery[Tx], fleetRepo: RemnawaveFleetRepository[Tx],
  syncState: IntegrationSyncStateRepository[Tx], remote: RemnawaveNodeRemote[IO],
  runner: TransactionRunner[IO, Tx],
  ownedFactory: Option[(UUID, UUID) => FleetRolloutChildren] = None) extends FleetRolloutChildren {

  override def owned(id: UUID, token: UUID): FleetRolloutChildren =
    ownedFactory.fold[FleetRolloutChildren](this)(_(id, token))

  def assignment(org: UUID, resourceId: UUID): IO[Option[(UUID, Int)]] =
    runner.run(assignments.assignment(org, resourceId)).map(_.map(a => a.profileId -> a.revisionNumber))

  def sshSource(org: UUID, resourceId: UUID): IO[Option[(UUID, Instant)]] =
    runner.run(targets.eligible(org, resourceId)).map(_.toOption.map(t => t.connectionId -> t.connectionUpdatedAt))

  override def sshSources(org: UUID, resourceIds: List[UUID]): IO[Map[UUID, (UUID, Instant)]] =
    runner.run(targets.eligibleBatch(org, resourceIds.distinct)).map(_.flatMap { case (id, result) =>
      result.toOption.map(t => id -> (t.connectionId -> t.connectionUpdatedAt))
    })

  def assign(actor: ActorContext, resourceId: UUID, profileId: UUID, revisionNumber: Int): IO[Unit] =
    serverProfiles.assign(actor, resourceId, profileId, revisionNumber).void

  def unassign(actor: ActorContext, resourceId: UUID): IO[Unit] = serverProfiles.unassign(actor, resourceId).void

  def previewApply(actor: ActorContext, resourceId: UUID): IO[Either[String, UUID]] =
    serverProfiles.preview(actor, resourceId).map { plan =>
      plan.blockingProblems.headOption.fold[Either[String, UUID]](Right(plan.run.id))(Left(_))
    }

  def startApply(actor: ActorContext, planId: UUID, requestId: UUID): IO[UUID] =
    provisioning.start(actor, planId, requestId).map(_.id)

  def applyOutcome(org: UUID, runId: UUID): IO[ChildOutcome] = provisioning.detail(org, runId).map { case (run, _) =>
    run.state match {
      case ProvisioningRunState.Succeeded => ChildOutcome.Succeeded
      case ProvisioningRunState.Failed => ChildOutcome.Failed(run.failureCode.getOrElse("SERVER_PROFILE_APPLY_FAILED"))
      case ProvisioningRunState.Unknown => ChildOutcome.Unknown(run.failureCode.getOrElse("SERVER_PROFILE_APPLY_UNKNOWN"))
      case _ => ChildOutcome.Running
    }
  }

  private def spec(provenance: FleetLocalProvenance, resourceId: UUID, nodePort: Int, cidrs: List[String]) =
    RemnawaveNodeRemoteSpec(provenance.onboardingId, resourceId, provenance.externalNodeId, nodePort,
      provenance.imageReference, cidrs)

  private def context(org: UUID, integrationId: UUID, resourceId: UUID, node: UUID) = runner.run(for {
    provenance <- fleetQuery.provenance(org, integrationId, resourceId, node)
    target <- targets.eligible(org, resourceId)
  } yield (provenance, target))

  def managedCidrs(org: UUID, integrationId: UUID, resourceId: UUID, node: UUID,
    nodePort: Int): IO[Either[String, List[String]]] = context(org, integrationId, resourceId, node).flatMap {
    case (Some(provenance), Right(target)) =>
      remote.managedPanelCidrs(target.connection, spec(provenance, resourceId, nodePort, Nil)).attempt
        .map(_.leftMap(FleetRolloutChildren.codeOf))
    case (None, _) => IO.pure(Left("REMNAWAVE_FLEET_ROLLOUT_NO_MANAGED_INSTALLATION"))
    case (_, Left(_)) => IO.pure(Left("REMNAWAVE_FLEET_ROLLOUT_NO_TRUSTED_SSH"))
  }

  def reconcileFirewall(org: UUID, integrationId: UUID, resourceId: UUID, node: UUID, nodePort: Int,
    cidrs: List[String]): IO[ChildOutcome] = context(org, integrationId, resourceId, node).flatMap {
    case (Some(provenance), Right(target)) =>
      remote.configureFirewall(target.connection, spec(provenance, resourceId, nodePort, cidrs)).map { result =>
        result.failureCode match {
          case None => ChildOutcome.Succeeded
          case Some(code) if result.uncertain => ChildOutcome.Unknown(code)
          case Some(code) => ChildOutcome.Failed(code)
        }
      }.handleError(error => ChildOutcome.Unknown(FleetRolloutChildren.codeOf(error)))
    case (None, _) => IO.pure(ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLOUT_NO_MANAGED_INSTALLATION"))
    case (_, Left(_)) => IO.pure(ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLOUT_NO_TRUSTED_SSH"))
  }

  def desiredRecord(org: UUID, integrationId: UUID, node: UUID): IO[Option[IntegrationDesiredNodeState]] =
    runner.run(desired.find(org, integrationId, node)).map(_.map(_.state))

  def desiredView(org: UUID, integrationId: UUID, node: UUID): IO[Option[DesiredStateView]] =
    runner.run(desired.view(org, integrationId, node))

  def setDesired(actor: ActorContext, integrationId: UUID, node: UUID, state: IntegrationDesiredNodeState): IO[Unit] =
    IO.realTimeInstant.flatMap(now => runner.run(for {
      _ <- desiredStates.set(actor, integrationId, node, state)
      current <- desired.find(actor.organizationId, integrationId, node)
      // Retain the normal intent/version, including when set was a no-op; the scoped runner and
      // trigger attach this exact parent and make the existing reconciler due.
      _ <- current.traverse_(value => desired.save(value, now))
    } yield ()))

  def removeDesired(actor: ActorContext, integrationId: UUID, node: UUID): IO[Unit] =
    runner.run(desiredStates.remove(actor, integrationId, node))

  def sharedImpact(org: UUID, integrationId: UUID, objectId: UUID,
    revisionNumber: Int): IO[Option[IntegrationConfigRolloutPreview]] =
    configRollouts.preview(org, integrationId, objectId, revisionNumber).map(Option(_)).recover {
      case IntegrationError("INTEGRATION_CONFIG_ALREADY_APPLIED", _) => None
    }

  def startConfig(actor: ActorContext, integrationId: UUID, objectId: UUID, revisionNumber: Int,
    requestId: UUID): IO[UUID] =
    configRollouts.start(actor, integrationId, objectId, revisionNumber, requestId, automaticRollback = true).map(_.id)

  def configOutcome(org: UUID, id: UUID): IO[ChildOutcome] = configRollouts.get(org, id).map { case (rollout, _) =>
    rollout.status match {
      case IntegrationConfigRolloutStatus.Succeeded => ChildOutcome.Succeeded
      case IntegrationConfigRolloutStatus.Unknown =>
        ChildOutcome.Unknown(rollout.errorCode.getOrElse("INTEGRATION_CONFIG_ROLLOUT_UNKNOWN"))
      case IntegrationConfigRolloutStatus.RolledBack => ChildOutcome.Failed("INTEGRATION_CONFIG_ROLLOUT_ROLLED_BACK")
      case IntegrationConfigRolloutStatus.Failed =>
        ChildOutcome.Failed(rollout.errorCode.getOrElse("INTEGRATION_CONFIG_ROLLOUT_FAILED"))
      case IntegrationConfigRolloutStatus.Cancelled => ChildOutcome.Failed("INTEGRATION_CONFIG_ROLLOUT_CANCELLED")
      case _ => ChildOutcome.Running
    }
  }

  def configByRequest(org: UUID, requestId: UUID): IO[Option[UUID]] = configRollouts.byRequest(org, requestId).map(_.map(_.id))

  def verifyRestored(actor: ActorContext, integrationId: UUID, fleetId: UUID, plan: FleetRolloutMemberPlan,
    kinds: Set[FleetActionKind], previousCidrs: Option[List[String]], nodePort: Int, after: Instant): IO[ChildOutcome] = {
    val org = actor.organizationId
    runner.run(fleetQuery.memberRows(org, fleetId)).flatMap { rows =>
      rows.find(_.membership.id == plan.membershipId) match {
        case None => IO.pure(ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLBACK_MEMBER_CHANGED"))
        case Some(row) if !row.assessment.exists(a => a.computedAt.isAfter(after) &&
          a.inventoryObservedAt.exists(_.isAfter(after)) &&
          (!row.localManaged || a.localObservedAt.exists(_.isAfter(after)))) => IO.pure(ChildOutcome.Running)
        case Some(row) => for {
          assigned <- assignment(org, plan.resourceId)
          profile <- if (!kinds(FleetActionKind.ServerProfileApply)) IO.pure(true)
            else serverProfiles.preview(actor, plan.resourceId).map(p => p.blockingProblems.isEmpty && p.assessment.compliant)
          firewall <- if (!kinds(FleetActionKind.NetworkFirewall)) IO.pure(true)
            else managedCidrs(org, integrationId, plan.resourceId, plan.inventoryNodeId,
              nodePort).map(_.exists(current => previousCidrs.exists(_.toSet == current.toSet)))
          desiredNow <- desiredRecord(org, integrationId, plan.inventoryNodeId)
          view <- if (kinds(FleetActionKind.DesiredState)) desiredView(org, integrationId, plan.inventoryNodeId)
            else IO.pure(Option.empty[DesiredStateView])
          expectedAssignment = plan.baseline.assignmentProfileId.zip(plan.baseline.assignmentRevisionNumber).headOption
          assignmentOk = !kinds(FleetActionKind.ServerProfileAssign) || assigned == expectedAssignment
          expectedDisabled = plan.baseline.desiredState.fold(plan.baseline.observedDisabled)(_ == IntegrationDesiredNodeState.Disabled)
          desiredOk = !kinds(FleetActionKind.DesiredState) || (row.disabled == expectedDisabled &&
            plan.baseline.desiredState.forall(s => desiredNow.contains(s)))
          healthOk = plan.baseline.health != FleetHealth.Healthy.code || expectedDisabled ||
            (row.connected && row.assessment.exists(a => a.healthReasons.filterNot(reason =>
              reason == FleetHealthReason.NodeDisabledUnexpectedly || reason == FleetHealthReason.NodeEnabledUnexpectedly).isEmpty))
        } yield if (view.exists(_.status == IntegrationDesiredStateStatus.RemediationFailed))
          ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLBACK_DESIRED_RESULT_UNKNOWN")
          else if (!desiredOk && view.exists(v => v.status == IntegrationDesiredStateStatus.Applying ||
            v.status == IntegrationDesiredStateStatus.Drifted || v.status == IntegrationDesiredStateStatus.WaitingRefresh)) ChildOutcome.Running
          else if (assignmentOk && profile && firewall && desiredOk && healthOk) ChildOutcome.Succeeded
          else ChildOutcome.Failed("REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_FAILED")
      }
    }
  }

  def refresh(org: UUID, integrationId: UUID, fleetId: UUID, at: Instant): IO[Unit] = runner.run(for {
    _ <- syncState.scheduleAt(org, integrationId, at)
    _ <- fleetRepo.markDue(org, Some(fleetId), integrationId, at)
  } yield ())
}
