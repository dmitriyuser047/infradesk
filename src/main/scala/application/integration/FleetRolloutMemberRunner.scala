package ru.bitec.app.ops
package application.integration

import application.auth.ActorContext
import application.port._
import cats.data.EitherT
import cats.effect.IO
import cats.syntax.all._
import domain.integration._
import io.circe.Json
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The worker lost its lease: nothing further may be written or started under this claim. */
case object FleetRolloutLeaseLost extends RuntimeException("REMNAWAVE_FLEET_ROLLOUT_LEASE_LOST") {
  override def fillInStackTrace(): Throwable = this
}

sealed trait MemberResult
object MemberResult {
  case object Done extends MemberResult
  case object Waiting extends MemberResult
  case object Stopped extends MemberResult
  final case class Failed(code: String) extends MemberResult
  final case class Unknown(code: String) extends MemberResult
  /** Compensation that cannot be completed automatically, with the reason. */
  final case class Incomplete(code: String) extends MemberResult
}

final case class FleetMemberRunnerSettings(actionTimeout: FiniteDuration = 90.minutes,
  pollInterval: FiniteDuration = 3.seconds)

/** Runs one member's typed child operations. The intent is written before each mutation, and after a
  * crash the child's own durable state is read first, so a mutation is never repeated blindly.
  */
final class FleetRolloutMemberRunner[Tx[_]](rollouts: RemnawaveFleetRolloutRepository[Tx],
  children: FleetRolloutChildren, runner: TransactionRunner[IO, Tx], settings: FleetMemberRunnerSettings) {

  private type R[A] = EitherT[IO, MemberResult, A]

  private val Order: List[FleetActionKind] = List(FleetActionKind.ServerProfileAssign,
    FleetActionKind.ServerProfileApply, FleetActionKind.NetworkFirewall, FleetActionKind.DesiredState)

  private def sequenceOf(kind: FleetActionKind) = (Order.indexOf(kind) + 1) * 10

  private def idOf(rolloutId: UUID, memberId: UUID, rollback: Boolean, kind: FleetActionKind, salt: String) =
    UUID.nameUUIDFromBytes(s"$rolloutId/$memberId/$rollback/${kind.code}/$salt".getBytes("UTF-8"))

  private def persist(action: RemnawaveFleetRolloutAction, token: UUID): IO[RemnawaveFleetRolloutAction] =
    for {
      now <- IO.realTimeInstant
      ok <- runner.run(rollouts.saveAction(action, token, now))
      _ <- IO.raiseUnless(ok)(FleetRolloutLeaseLost)
    } yield action

  private def blank(rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember, rollback: Boolean,
    kind: FleetActionKind, now: Instant) =
    RemnawaveFleetRolloutAction(idOf(rollout.id, member.id, rollback, kind, "action"), rollout.organizationId,
      rollout.id, Some(member.id), rollback, kind, sequenceOf(kind), FleetActionState.Pending,
      idOf(rollout.id, member.id, rollback, kind, "child"), None, None, None, None, None, None, None, None, 1L, now)

  private def previousCidrs(action: RemnawaveFleetRolloutAction): Option[List[String]] =
    action.intent.flatMap(_.hcursor.get[List[String]]("previousCidrs").toOption)

  private def lift[A](io: IO[A]): R[A] = EitherT.liftF(io)

  private def stop[A](result: MemberResult): R[A] = EitherT.leftT[IO, A](result)

  private def poll(read: IO[ChildOutcome]): IO[ChildOutcome] = {
    def loop(left: FiniteDuration): IO[ChildOutcome] = read.flatMap {
      case ChildOutcome.Running if left > Duration.Zero =>
        IO.sleep(settings.pollInterval) *> loop(left - settings.pollInterval)
      case ChildOutcome.Running => IO.pure(ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLOUT_ACTION_TIMEOUT"))
      case other => IO.pure(other)
    }
    loop(settings.actionTimeout)
  }

  private def settle(action: RemnawaveFleetRolloutAction, outcome: ChildOutcome, token: UUID): R[Unit] = for {
    now <- lift(IO.realTimeInstant)
    done = action.copy(finishedAt = Some(now))
    result <- outcome match {
      case ChildOutcome.Succeeded => lift(persist(done.copy(state = FleetActionState.Succeeded), token)).void
      case ChildOutcome.Failed(code) => lift(persist(done.copy(state = FleetActionState.Failed, failureCode = Some(code)),
        token)) *> stop[Unit](MemberResult.Failed(code))
      case ChildOutcome.Unknown(code) => lift(persist(done.copy(state = FleetActionState.Unknown,
        failureCode = Some(code)), token)) *> stop[Unit](MemberResult.Unknown(code))
      case ChildOutcome.Running => stop[Unit](MemberResult.Unknown("REMNAWAVE_FLEET_ROLLOUT_ACTION_TIMEOUT"))
    }
  } yield result

  private def failed[A](action: RemnawaveFleetRolloutAction, error: Throwable, token: UUID): R[A] = {
    val code = FleetRolloutChildren.codeOf(error)
    lift(IO.realTimeInstant.flatMap(now => persist(action.copy(state = FleetActionState.Failed,
      failureCode = Some(code), finishedAt = Some(now)), token))) *> stop[A](MemberResult.Failed(code))
  }

  private def begin(action: RemnawaveFleetRolloutAction, token: UUID): R[RemnawaveFleetRolloutAction] =
    lift(IO.realTimeInstant.flatMap(now => persist(action.copy(state = FleetActionState.Running,
      startedAt = action.startedAt.orElse(Some(now))), token)))

  private def profileApply(actor: ActorContext, rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember,
    rollback: Boolean, existing: Option[RemnawaveFleetRolloutAction], token: UUID): R[Unit] = for {
    now <- lift(IO.realTimeInstant)
    base = existing.getOrElse(blank(rollout, member, rollback, FleetActionKind.ServerProfileApply, now))
    planned <- base.serverProfilePlanId match {
      case Some(_) => begin(base, token)
      case None => for {
        started <- begin(base, token)
        preview <- lift(children.previewApply(actor, member.resourceId).attempt)
        plan <- preview match {
          case Right(Right(id)) => lift(persist(started.copy(serverProfilePlanId = Some(id)), token))
          case Right(Left(problem)) => failed[RemnawaveFleetRolloutAction](started,
            IntegrationError(problem, problem), token)
          case Left(e) => failed[RemnawaveFleetRolloutAction](started, e, token)
        }
      } yield plan
    }
    withRun <- planned.serverProfileRunId match {
      case Some(_) => EitherT.rightT[IO, MemberResult](planned)
      case None => lift(children.startApply(actor, planned.serverProfilePlanId.get, planned.childRequestId).attempt)
        .flatMap {
          case Right(id) => lift(persist(planned.copy(serverProfileRunId = Some(id)), token))
          case Left(e) => failed[RemnawaveFleetRolloutAction](planned, e, token)
        }
    }
    outcome <- lift(poll(children.applyOutcome(rollout.organizationId, withRun.serverProfileRunId.get)))
    _ <- settle(withRun, outcome, token)
  } yield ()

  private def one(actor: ActorContext, rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember,
    plan: FleetRolloutMemberPlan, rollback: Boolean, kind: FleetActionKind, existing: Option[RemnawaveFleetRolloutAction],
    forward: Option[RemnawaveFleetRolloutAction], token: UUID): R[Unit] = {
    val snapshot = rollout.snapshot
    val content = snapshot.content
    val org = rollout.organizationId
    val integrationId = rollout.integrationId
    for {
      now <- lift(IO.realTimeInstant)
      base = existing.getOrElse(blank(rollout, member, rollback, kind, now))
      _ <- kind match {
        case FleetActionKind.ServerProfileApply => profileApply(actor, rollout, member, rollback, existing, token)
        case FleetActionKind.ServerProfileAssign =>
          val target = if (rollback) plan.baseline.assignmentProfileId.zip(plan.baseline.assignmentRevisionNumber).headOption
          else Some(content.serverProfileId -> content.serverProfileRevisionNumber)
          for {
            started <- begin(base, token)
            current <- lift(children.assignment(org, member.resourceId))
            _ <- if (current == target) EitherT.rightT[IO, MemberResult](())
            else if (rollback && !current.contains(content.serverProfileId -> content.serverProfileRevisionNumber))
              stop[Unit](MemberResult.Incomplete("REMNAWAVE_FLEET_ROLLOUT_ASSIGNMENT_CHANGED"))
            else target match {
              case Some((profile, number)) => lift(children.assign(actor, member.resourceId, profile, number).attempt)
                .flatMap(_.fold(e => failed[Unit](started, e, token), _ => EitherT.rightT[IO, MemberResult](())))
              case None => lift(children.unassign(actor, member.resourceId).attempt)
                .flatMap(_.fold(e => failed[Unit](started, e, token), _ => EitherT.rightT[IO, MemberResult](())))
            }
            _ <- settle(started, ChildOutcome.Succeeded, token)
          } yield ()
        case FleetActionKind.NetworkFirewall => for {
          _ <- if (existing.exists(_.state == FleetActionState.Running))
            settle(base, ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLOUT_FIREWALL_RESULT_UNKNOWN"), token)
          else EitherT.rightT[IO, MemberResult](())
          started <- begin(base, token)
          withIntent <- if (rollback) EitherT.rightT[IO, MemberResult](started)
          else if (started.intent.nonEmpty) EitherT.rightT[IO, MemberResult](started)
          else lift(children.managedCidrs(org, integrationId, member.resourceId, member.inventoryNodeId,
            content.nodePort)).flatMap {
            case Right(cidrs) => lift(persist(started.copy(intent = Some(Json.obj("previousCidrs" ->
              Json.arr(cidrs.map(Json.fromString): _*)))), token))
            case Left(code) => lift(persist(started.copy(state = FleetActionState.Failed, failureCode = Some(code)),
              token)) *> stop[RemnawaveFleetRolloutAction](MemberResult.Failed(code))
          }
          cidrs = if (rollback) forward.flatMap(previousCidrs) else Some(content.panelCidrs)
          outcome <- cidrs match {
            case Some(list) => lift(children.reconcileFirewall(org, integrationId, member.resourceId,
              member.inventoryNodeId, content.nodePort, list))
            case None => stop[ChildOutcome](MemberResult.Incomplete("REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_FIREWALL"))
          }
          _ <- settle(withIntent, outcome, token)
        } yield ()
        case FleetActionKind.DesiredState => for {
          started <- begin(base, token)
          result <- lift((if (rollback) children.setDesired(actor, integrationId, member.inventoryNodeId,
            plan.baseline.desiredState.getOrElse(if (plan.baseline.observedDisabled)
              IntegrationDesiredNodeState.Disabled else IntegrationDesiredNodeState.Enabled))
          else children.setDesired(actor, integrationId, member.inventoryNodeId, content.desiredNodeState)).attempt)
          _ <- result.fold(e => failed[Unit](started, e, token), _ => settle(started, ChildOutcome.Succeeded, token))
        } yield ()
        case _ => EitherT.rightT[IO, MemberResult](())
      }
    } yield ()
  }

  private def run(actor: ActorContext, rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember,
    plan: FleetRolloutMemberPlan, rollback: Boolean, kinds: List[FleetActionKind], token: UUID): IO[MemberResult] = {
    val program: R[Unit] = for {
      all <- lift(runner.run(rollouts.actions(rollout.id)))
      mine = all.filter(a => a.memberId.contains(member.id))
      _ <- kinds.traverse_ { kind =>
        val existing = mine.find(a => a.rollback == rollback && a.kind == kind)
        val forward = mine.find(a => !a.rollback && a.kind == kind)
        if (existing.exists(_.state == FleetActionState.Succeeded)) EitherT.rightT[IO, MemberResult](())
        else if (existing.exists(_.state == FleetActionState.Unknown))
          stop[Unit](MemberResult.Unknown(existing.flatMap(_.failureCode).getOrElse("REMNAWAVE_FLEET_ROLLOUT_ACTION_UNKNOWN")))
        else if (existing.exists(_.state == FleetActionState.Failed))
          stop[Unit](MemberResult.Failed(existing.flatMap(_.failureCode).getOrElse("REMNAWAVE_FLEET_ROLLOUT_CHILD_FAILED")))
        else for {
          control <- lift(runner.run(rollouts.rolloutById(rollout.id)))
          _ <- if (!rollback && !existing.exists(_.state == FleetActionState.Running) &&
            control.exists(r => r.pauseRequestedAt.nonEmpty || r.rollbackRequestedAt.nonEmpty))
            stop[Unit](MemberResult.Stopped) else EitherT.rightT[IO, MemberResult](())
          _ <- one(actor, rollout, member, plan, rollback, kind, existing, forward, token)
        } yield ()
      }
    } yield ()
    program.value.map(_.fold(identity, _ => MemberResult.Done))
  }

  def forward(rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember, plan: FleetRolloutMemberPlan,
    token: UUID): IO[MemberResult] = new FleetRolloutMemberRunner(rollouts, children.owned(rollout.id, token), runner, settings)
    .run(ActorContext(rollout.createdBy, rollout.organizationId), rollout, member, plan,
    rollback = false, Order.filter(plan.actions.contains), token)

  /** Compensation runs the kinds that actually succeeded going forward, in reverse. */
  def compensate(rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember, plan: FleetRolloutMemberPlan,
    token: UUID): IO[MemberResult] = for {
    all <- runner.run(rollouts.actions(rollout.id))
    done = all.filter(a => a.memberId.contains(member.id) && !a.rollback && a.state == FleetActionState.Succeeded)
      .map(_.kind).toSet
    hasUncertain = all.exists(a => a.memberId.contains(member.id) && !a.rollback &&
      (a.state == FleetActionState.Unknown || a.state == FleetActionState.Running))
    kinds = Order.reverse.filter(done.contains).filter(_ != FleetActionKind.ServerProfileApply)
    profileApplied = done.contains(FleetActionKind.ServerProfileAssign) && done.contains(FleetActionKind.ServerProfileApply)
    stuck = done.contains(FleetActionKind.ServerProfileApply) && !done.contains(FleetActionKind.ServerProfileAssign)
    withProfile = if (profileApplied && plan.baseline.assignmentProfileId.nonEmpty)
      kinds.filterNot(_ == FleetActionKind.ServerProfileAssign) ++
        List(FleetActionKind.ServerProfileAssign, FleetActionKind.ServerProfileApply) else kinds
    result <-
      if (hasUncertain) IO.pure[MemberResult](MemberResult.Unknown("REMNAWAVE_FLEET_ROLLOUT_FORWARD_UNCERTAIN"))
      else new FleetRolloutMemberRunner(rollouts, children.owned(rollout.id, token), runner, settings)
        .run(ActorContext(rollout.createdBy, rollout.organizationId), rollout, member, plan, rollback = true,
        withProfile, token).map {
        case MemberResult.Done if stuck || (profileApplied && plan.baseline.assignmentProfileId.isEmpty) =>
          MemberResult.Incomplete("REMNAWAVE_FLEET_ROLLOUT_NO_PREVIOUS_PROFILE")
        case other => other
      }
    verified <- result match {
      case MemberResult.Done if done.nonEmpty => verifyCompensation(rollout, member, plan, done,
        all.find(a => a.memberId.contains(member.id) && !a.rollback && a.kind == FleetActionKind.NetworkFirewall)
          .flatMap(previousCidrs), token)
      case other => IO.pure(other)
    }
  } yield verified

  private def verifyCompensation(rollout: RemnawaveFleetRollout, member: RemnawaveFleetRolloutMember,
    plan: FleetRolloutMemberPlan, kinds: Set[FleetActionKind], cidrs: Option[List[String]], token: UUID): IO[MemberResult] = for {
    all <- runner.run(rollouts.actions(rollout.id))
    now <- IO.realTimeInstant
    prior = all.find(a => a.memberId.contains(member.id) && a.rollback && a.kind == FleetActionKind.Verify)
    action = prior.getOrElse(blank(rollout, member, true, FleetActionKind.Verify, now)
      .copy(sequence = 50, state = FleetActionState.Running, startedAt = Some(now)))
    child = children.owned(rollout.id, token)
    _ <- if (prior.isEmpty) persist(action, token) *> child.refresh(rollout.organizationId, rollout.integrationId,
      rollout.fleetId, now) else IO.unit
    outcome <- if (action.state == FleetActionState.Succeeded) IO.pure(ChildOutcome.Succeeded)
      else if (action.state == FleetActionState.Failed) IO.pure(ChildOutcome.Failed(
        action.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_FAILED")))
      else if (action.state == FleetActionState.Unknown) IO.pure(ChildOutcome.Unknown(
        action.failureCode.getOrElse("REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_UNKNOWN")))
      else child.verifyRestored(ActorContext(rollout.createdBy, rollout.organizationId), rollout.integrationId,
        rollout.fleetId, plan, kinds, cidrs, rollout.snapshot.content.nodePort, action.startedAt.get).attempt.map {
        case Right(ChildOutcome.Running) if now.isAfter(action.startedAt.get.plusMillis(settings.actionTimeout.toMillis)) =>
          ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_TIMEOUT")
        case Right(value) => value
        case Left(FleetRolloutLeaseLost) => throw FleetRolloutLeaseLost
        case Left(_) => ChildOutcome.Unknown("REMNAWAVE_FLEET_ROLLBACK_VERIFICATION_UNKNOWN")
      }
    _ <- if (outcome == ChildOutcome.Succeeded && kinds(FleetActionKind.DesiredState) && plan.baseline.desiredState.isEmpty)
      child.removeDesired(ActorContext(rollout.createdBy, rollout.organizationId), rollout.integrationId, plan.inventoryNodeId)
      else IO.unit
    result <- outcome match {
      case ChildOutcome.Running => IO.pure[MemberResult](MemberResult.Waiting)
      case other => settle(action, other, token).value.map(_.fold(identity, _ => MemberResult.Done))
    }
  } yield result
}
