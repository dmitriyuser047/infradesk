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
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** Persistent intent for explicitly selected nodes. Every method is one short transaction that
  * only records intent and its audit event: nothing here contacts Remnawave or creates an action.
  */
final class IntegrationDesiredStates[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], desired: IntegrationDesiredStateRepository[Tx],
  providers: IntegrationProviderRegistry[IO], ids: IdGenerator[Tx], time: TimeProvider[Tx],
  audit: AuditRecorder[Tx], desiredStateOperational: Boolean) {
  import IntegrationDesiredStates._

  /** Opting in is explicit and needs automatic observation. Going back to OBSERVE removes every
    * intent of the integration in the same transaction and changes nothing in Remnawave.
    */
  def setMode(actor: ActorContext, integrationId: UUID, mode: IntegrationManagementMode): Tx[Integration] =
    locked(actor.organizationId, integrationId).flatMap { stored =>
      if (stored.managementMode == mode) stored.pure[Tx]
      else for {
        _ <- mode match {
          case IntegrationManagementMode.ManagedSelected => for {
            _ <- Either.cond(desiredStateOperational, (), error(SubsystemDisabled,
              "Desired state is unavailable for this deployment")).liftTo[Tx]
            _ <- supported(stored).liftTo[Tx]
            _ <- Either.cond(stored.enabled, (), error(RequiresSync,
              "Managing nodes requires automatic synchronization")).liftTo[Tx]
          } yield ()
          case IntegrationManagementMode.Observe => for {
            _ <- requireNoActiveAction(actor.organizationId, integrationId, None)
            // Before the mode: the database refuses an OBSERVE integration that still has intents.
            _ <- desired.deleteAll(actor.organizationId, integrationId)
          } yield ()
        }
        now <- time.now
        next = stored.copy(managementMode = mode, updatedAt = now)
        _ <- integrations.save(next)
        _ <- audit.record(actor, AuditAction.IntegrationManagementModeChanged, AuditTargetType.Integration,
          Some(integrationId))
      } yield next
    }

  /** Setting the state a node already has as its intent changes nothing: no version, no update
    * time, no audit event.
    */
  def set(actor: ActorContext, integrationId: UUID, objectId: UUID,
    state: IntegrationDesiredNodeState): Tx[DesiredStateView] = for {
    integration <- locked(actor.organizationId, integrationId)
    _ <- supported(integration).liftTo[Tx]
    _ <- Either.cond(integration.managementMode == IntegrationManagementMode.ManagedSelected, (),
      error(ModeRequired, "The integration does not manage selected nodes")).liftTo[Tx]
    _ <- Either.cond(integration.enabled, (), error(RequiresSync,
      "Managing nodes requires automatic synchronization")).liftTo[Tx]
    node <- lockedNode(actor.organizationId, integrationId, objectId)
    _ <- Either.cond(node.isActive, (), error("INTEGRATION_OBJECT_INACTIVE", "Node is inactive")).liftTo[Tx]
    existing <- desired.find(actor.organizationId, integrationId, objectId)
    _ <- existing match {
      case Some(current) if current.state == state => ().pure[Tx]
      case _ => for {
        _ <- requireNoActiveAction(actor.organizationId, integrationId, Some(objectId))
        id <- ids.nextId
        now <- time.now
        // A changed intent is a new intent: it is decided against the current observation, not
        // held back by what was attempted for the previous one.
        next = existing.fold(IntegrationDesiredState(id, actor.organizationId, integrationId, objectId, state, 1L,
          actor.userId, now, now, None, None))(current => current.copy(state = state, version = current.version + 1,
          setByUserId = actor.userId, updatedAt = now, lastAttemptObservationAt = None, lastActionExecutionId = None))
        _ <- desired.save(next, now)
        _ <- audit.record(actor, AuditAction.IntegrationDesiredStateSet, AuditTargetType.Integration, Some(integrationId))
      } yield ()
    }
    view <- desired.view(actor.organizationId, integrationId, objectId).flatMap(_.liftTo[Tx](
      new IllegalStateException("Desired state disappeared")))
  } yield view

  /** Stops managing a node. It never sends the opposite action; an absent intent is not an error
    * and is not audited.
    */
  def remove(actor: ActorContext, integrationId: UUID, objectId: UUID): Tx[Unit] = for {
    _ <- locked(actor.organizationId, integrationId)
    _ <- lockedNode(actor.organizationId, integrationId, objectId)
    existing <- desired.find(actor.organizationId, integrationId, objectId)
    _ <- existing.traverse_(_ => for {
      _ <- requireNoActiveAction(actor.organizationId, integrationId, Some(objectId))
      _ <- desired.delete(actor.organizationId, integrationId, objectId)
      _ <- audit.record(actor, AuditAction.IntegrationDesiredStateRemoved, AuditTargetType.Integration, Some(integrationId))
    } yield ())
  } yield ()

  // The integration row lock serializes intent changes with actions, edits, deletes and snapshots.
  private def locked(org: UUID, id: UUID): Tx[Integration] = integrations.findByIdForUpdate(org, id)
    .flatMap(_.liftTo[Tx](error("INTEGRATION_NOT_FOUND", "Integration was not found")))

  private def lockedNode(org: UUID, integrationId: UUID, objectId: UUID): Tx[IntegrationInventoryObject] =
    inventory.findObject(org, integrationId, objectId, forUpdate = true).flatMap(_.liftTo[Tx](
      error("INTEGRATION_OBJECT_NOT_FOUND", "Integration object was not found"))).flatMap(found =>
      Either.cond(found.objectType == IntegrationObjectType.Node, found,
        error(Unsupported, "Only nodes can have a desired state")).liftTo[Tx])

  private def supported(integration: Integration): Either[IntegrationError, Unit] =
    Either.cond(integration.providerType == IntegrationProviderType.Remnawave &&
      providers.find(integration.providerType).exists(_.capabilities.contains(IntegrationCapability.DesiredState)),
      (), error(Unsupported, "Desired state is unsupported for this integration"))

  // No cancellation: an intent cannot change under an action that is queued or in flight.
  private def requireNoActiveAction(org: UUID, integrationId: UUID, objectId: Option[UUID]): Tx[Unit] =
    desired.activeActionExists(org, integrationId, objectId).flatMap(active => Either.cond(!active, (),
      error("INTEGRATION_ACTION_ALREADY_RUNNING", "An action is already active")).liftTo[Tx])

  private def error(code: String, message: String) = IntegrationError(code, message)
}

object IntegrationDesiredStates {
  val RequiresSync = "INTEGRATION_MANAGEMENT_REQUIRES_SYNC"
  val ManagementActive = "INTEGRATION_MANAGEMENT_ACTIVE"
  val SubsystemDisabled = "INTEGRATION_DESIRED_STATE_DISABLED"
  val ModeRequired = "INTEGRATION_MANAGEMENT_MODE_REQUIRED"
  val Unsupported = "INTEGRATION_DESIRED_STATE_UNSUPPORTED"
  val ActionConflict = "INTEGRATION_ACTION_CONFLICTS_WITH_DESIRED_STATE"
}

final case class IntegrationDesiredStateSettings(pollInterval: FiniteDuration, batchSize: Int, maxConcurrency: Int,
  claimLease: FiniteDuration, idleInterval: FiniteDuration, maxWavesPerLane: Int = 40) {
  require(pollInterval.toMillis > 0 && batchSize > 0 && maxConcurrency > 0 && claimLease.toMillis > 0 &&
    idleInterval.toMillis > 0 && maxWavesPerLane > 0, "Invalid desired state settings")
}

/** Turns drift into durable intent, and nothing more.
  *
  * It reads desired state and observed inventory from the database and, where they differ, creates
  * a QUEUED `IntegrationActionExecution`. It has no provider, no HTTP client and no credential: it
  * cannot talk to Remnawave. The action worker performs the write, a later synchronization observes
  * the result, and only that new observation can lead to another action.
  *
  * A wave is three statements whatever its size: claim with facts, create actions, release.
  */
final class IntegrationDesiredStateWorker[Tx[_]](desired: IntegrationDesiredStateRepository[Tx],
  runner: TransactionRunner[IO, Tx], time: TimeProvider[IO], logger: Logger[IO],
  settings: IntegrationDesiredStateSettings, owner: UUID) {
  import IntegrationDesiredStateWorker._

  def run: IO[Nothing] =
    (tick.handleErrorWith(e => log(s"integration.desired_state.failed errorType=${e.getClass.getSimpleName}")) *>
      IO.sleep(settings.pollInterval)).foreverM

  /** Independent lanes claim disjoint batches (SKIP LOCKED); a failing wave ends only its own lane. */
  def tick: IO[Unit] = List.fill(settings.maxConcurrency)(()).parTraverseN(settings.maxConcurrency)(_ =>
    lane(settings.maxWavesPerLane).handleErrorWith(e =>
      log(s"integration.desired_state.failed errorType=${e.getClass.getSimpleName}"))).void

  private def lane(remaining: Int): IO[Unit] =
    if (remaining <= 0) IO.unit else wave.flatMap(claimed => if (claimed == 0) IO.unit else lane(remaining - 1))

  /** One bounded batch; returns how many intents were claimed. */
  private[integration] def wave: IO[Int] = for {
    token <- IO(UUID.randomUUID())
    claimedAt <- time.now
    candidates <- runner.run(desired.claim(owner, token, claimedAt,
      claimedAt.plusMillis(settings.claimLease.toMillis), settings.batchSize))
    _ <- if (candidates.isEmpty) IO.unit else for {
      _ <- log(s"integration.desired_state.reconcile.claimed count=${candidates.size}")
      decisions = candidates.map(candidate => candidate -> decide(candidate))
      intents = decisions.collect { case (c, Decision.Remediate(action)) =>
        DesiredStateIntent(c.id, c.version, action, c.lastSeenAt) }
      now <- time.now
      created <- if (intents.isEmpty) IO.pure(List.empty[CreatedDesiredAction])
        else runner.run(desired.createActions(token, now, intents))
      _ <- runner.run(desired.release(token, claimedAt, now.plusMillis(settings.idleInterval.toMillis)))
      _ <- decisions.traverse_ { case (c, decision) => report(c, decision, created.find(_.desiredStateId == c.id)) }
    } yield ()
  } yield candidates.size

  private def report(c: DesiredStateCandidate, decision: Decision, created: Option[CreatedDesiredAction]): IO[Unit] = {
    val context = s"organizationId=${c.organizationId} integrationId=${c.integrationId} " +
      s"inventoryObjectId=${c.inventoryObjectId} desiredStateId=${c.id} desiredStateVersion=${c.version} " +
      s"observedState=${if (c.observedDisabled) "DISABLED" else "ENABLED"} desiredState=${c.state.code}"
    (decision, created) match {
      case (Decision.Remediate(_), Some(action)) => log(s"integration.desired_state.drift $context") *>
        log(s"integration.desired_state.action_created $context actionExecutionId=${action.executionId}")
      // The intent, the observation or the claim changed after the decision: nothing was created.
      case (Decision.Remediate(_), None) => log(s"integration.desired_state.fenced $context")
      case (Decision.Compliant, _) => logger.debug(s"integration.desired_state.compliant $context").handleErrorWith(_ => IO.unit)
      case (Decision.Wait, _) => IO.unit
    }
  }

  private def log(message: String): IO[Unit] = logger.info(message).handleErrorWith(_ => IO.unit)
}

object IntegrationDesiredStateWorker {
  sealed trait Decision
  object Decision {
    final case class Remediate(action: IntegrationActionCode) extends Decision
    case object Compliant extends Decision
    /** Drift may exist, but nothing may be done until a newer observation arrives. */
    case object Wait extends Decision
  }

  /** At most one action per observation. A drifted node gets an action only when no action is
    * active on it, the observation is newer than the one the previous attempt was decided on, and
    * newer than every action whose outcome is not yet observed (UNKNOWN included).
    */
  def decide(c: DesiredStateCandidate): Decision = {
    val status = IntegrationDesiredStateStatus.derive(c.state,
      IntegrationDesiredStateStatus.Facts(c.objectActive, c.observedDisabled, c.lastSeenAt, c.lastAction))
    status match {
      case IntegrationDesiredStateStatus.Compliant => Decision.Compliant
      case IntegrationDesiredStateStatus.Drifted =>
        val fresh = c.lastAttemptObservationAt.forall(c.lastSeenAt.isAfter) &&
          c.latestUnknownFinishedAt.forall(c.lastSeenAt.isAfter)
        IntegrationDesiredNodeState.remediation(c.state, c.observedDisabled) match {
          case Some(action) if fresh && !c.activeAction => Decision.Remediate(action)
          case _ => Decision.Wait
        }
      case _ => Decision.Wait
    }
  }
}
