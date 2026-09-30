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

/** One transaction records a durable intent and its audit event. It never contacts Remnawave. */
final class IntegrationActions[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], actions: IntegrationActionRepository[Tx],
  secrets: IntegrationSecretRepository[Tx], providers: IntegrationProviderRegistry[IO],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx],
  desired: IntegrationDesiredStateRepository[Tx]) {

  def request(actor: ActorContext, integrationId: UUID, objectId: UUID, requestId: UUID,
    action: IntegrationActionCode): Tx[IntegrationActionExecution] =
    actions.findByRequest(actor.organizationId, requestId).flatMap {
      case Some(existing) => samePayload(existing, integrationId, objectId, action).pure[Tx]
      case None => create(actor, integrationId, objectId, requestId, action)
    }

  private def samePayload(existing: IntegrationActionExecution, integrationId: UUID, objectId: UUID,
    action: IntegrationActionCode): IntegrationActionExecution = {
    if (existing.integrationId != integrationId || existing.target.inventoryObjectId != objectId ||
      existing.action != action) throw IntegrationError("INTEGRATION_ACTION_REQUEST_ID_CONFLICT",
      "Request ID was already used for another action")
    existing
  }

  private def create(actor: ActorContext, integrationId: UUID, objectId: UUID, requestId: UUID,
    action: IntegrationActionCode): Tx[IntegrationActionExecution] = for {
    integration <- integrations.findByIdForUpdate(actor.organizationId, integrationId).flatMap(_.liftTo[Tx](
      IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))
    _ <- Either.cond(integration.providerType == IntegrationProviderType.Remnawave &&
      providers.find(integration.providerType).exists(_.capabilities.contains(IntegrationCapability.SafeActions)),
      (), IntegrationError("INTEGRATION_ACTION_UNSUPPORTED", "Action is unsupported")).liftTo[Tx]
    obj <- inventory.findObject(actor.organizationId, integrationId, objectId, forUpdate = true)
      .flatMap(_.liftTo[Tx](IntegrationError("INTEGRATION_OBJECT_NOT_FOUND", "Integration object was not found")))
    _ <- Either.cond(obj.objectType == IntegrationObjectType.Node, (),
      IntegrationError("INTEGRATION_ACTION_UNSUPPORTED", "Only node actions are supported")).liftTo[Tx]
    _ <- Either.cond(obj.isActive, (),
      IntegrationError("INTEGRATION_OBJECT_INACTIVE", "Node is inactive")).liftTo[Tx]
    disabled <- obj.summary match {
      case node: RemnawaveNodeSummary => node.isDisabled.pure[Tx]
      case _ => IntegrationError("INTEGRATION_ACTION_UNSUPPORTED", "Node observation is invalid")
        .raiseError[Tx, Boolean]
    }
    _ <- Either.cond(if (action == IntegrationActionCode.NodeEnable) disabled else !disabled, (),
      IntegrationError("INTEGRATION_ACTION_UNSUPPORTED", "Action is unavailable for observed state")).liftTo[Tx]
    // A one-shot request never works against a persistent intent; restart contradicts neither state.
    intent <- desired.find(actor.organizationId, integrationId, objectId)
    _ <- Either.cond(!intent.exists(value => IntegrationDesiredNodeState.contradicts(value.state, action)), (),
      IntegrationError(IntegrationDesiredStates.ActionConflict, "Action conflicts with the node's desired state"))
      .liftTo[Tx]
    _ <- secrets.find(actor.organizationId, integration.secretId).flatMap(_.liftTo[Tx](
      IntegrationError("INTEGRATION_CREDENTIAL_MISSING", "Integration credential is missing"))).void
    unknown <- actions.latestUnknownFinishedAt(actor.organizationId, integrationId, objectId)
    _ <- Either.cond(unknown.forall(obj.lastSeenAt.isAfter), (),
      IntegrationError("INTEGRATION_ACTION_REQUIRES_REFRESH", "Synchronize before another action")).liftTo[Tx]
    id <- ids.nextId
    now <- time.now
    target = IntegrationActionTarget(objectId, obj.objectType, obj.externalId, obj.displayName)
    value = IntegrationActionExecution(id, actor.organizationId, integrationId, target, requestId, action,
      actor.userId, IntegrationActionStatus.Queued, now, None, None, None, None, None, None, None, now)
    result <- actions.insertOrFind(value)
    (stored, created) = result
    _ <- Either.cond(created || (stored.integrationId == integrationId &&
      stored.target.inventoryObjectId == objectId && stored.action == action), (),
      IntegrationError("INTEGRATION_ACTION_REQUEST_ID_CONFLICT", "Request ID was already used"))
      .liftTo[Tx]
    _ <- if (created) audit.record(actor, AuditAction.IntegrationActionRequested,
      AuditTargetType.Integration, Some(integrationId)) else ().pure[Tx]
  } yield stored
}

final class IntegrationActionWorker[Tx[_]](actions: IntegrationActionRepository[Tx],
  integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  cipher: IntegrationCryptography, providers: IntegrationProviderRegistry[IO],
  runner: TransactionRunner[IO, Tx], time: TimeProvider[IO], logger: Logger[IO],
  poll: FiniteDuration, batch: Int, concurrency: Int, requestTimeout: FiniteDuration,
  owner: UUID) {
  private val deadline = requestTimeout + scala.concurrent.duration.DurationInt(60).seconds
  require(poll.toMillis > 0 && batch > 0 && concurrency > 0 && deadline > requestTimeout)

  def run: IO[Nothing] =
    (tick.handleErrorWith(e => log(s"integration.action.tick.failed errorType=${e.getClass.getSimpleName}")) *>
      IO.sleep(poll)).foreverM

  def tick: IO[Unit] = {
    def wave(remaining: Int): IO[Unit] = if (remaining <= 0) IO.unit else
      time.now.flatMap { now =>
        // Claim only work that can start now. A queued row must not spend its attempt deadline
        // waiting behind the rest of a large batch.
        runner.run(actions.recoverAndClaim(owner, UUID.randomUUID(), now,
          now.plusMillis(deadline.toMillis), math.min(remaining, concurrency)))
      }.flatMap { case (recovered, claimed) =>
        log(s"integration.action.stale.recovered count=$recovered") *>
          claimed.traverse_(value => log(s"integration.action.claimed organizationId=${value.organizationId} " +
            s"integrationId=${value.integrationId} inventoryObjectId=${value.target.inventoryObjectId} " +
            s"executionId=${value.id} actionCode=${value.action.code}")) *>
          claimed.parTraverseN(concurrency)(execute).void *>
          (if (claimed.isEmpty) IO.unit else wave(remaining - claimed.size))
      }
    wave(batch)
  }

  private def execute(value: IntegrationActionExecution): IO[Unit] = {
    val context = s"organizationId=${value.organizationId} integrationId=${value.integrationId} " +
      s"inventoryObjectId=${value.target.inventoryObjectId} executionId=${value.id} actionCode=${value.action.code}"
    val remote = runner.run(integrations.findById(value.organizationId, value.integrationId)).flatMap {
      case None => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_NOT_FOUND"))
      case Some(integration) => runner.run(secrets.find(value.organizationId, integration.secretId)).flatMap {
        case None => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CREDENTIAL_MISSING"))
        case Some(secret) => IO.delay(cipher.decrypt(secret)).attempt.flatMap {
          case Left(_) => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CREDENTIAL_INVALID"))
          case Right(credential) => providers.find(integration.providerType) match {
            case None => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_ACTION_UNSUPPORTED"))
            case Some(provider) => provider.executeAction(IntegrationRuntimeContext(integration.id,
              integration.organizationId, integration.baseUrl, credential), value.target.externalId, value.action)
          }
        }
      }
    }.timeoutTo(requestTimeout + scala.concurrent.duration.DurationInt(30).seconds,
      IO.pure(IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN")))
      .handleError(_ => IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
    log(s"integration.action.started $context") *> remote.flatMap { result =>
      val (status, code, message) = result match {
        case IntegrationActionRemoteOutcome.Succeeded => ("SUCCEEDED", None, None)
        case IntegrationActionRemoteOutcome.DefinitelyFailed(c) => ("FAILED", Some(c), Some("Remote action was rejected"))
        case IntegrationActionRemoteOutcome.OutcomeUnknown(_) =>
          ("UNKNOWN", Some("INTEGRATION_ACTION_RESULT_UNKNOWN"), Some("Remote result is unknown"))
      }
      time.now.flatMap(at => runner.run(actions.complete(value, value.claimToken.get, at, status, code, message)))
        .flatMap(ok => log(s"integration.action.${if (ok) status.toLowerCase else "completion.conflict"} $context" +
          (if (ok) "" else " errorCode=INTEGRATION_ACTION_COMPLETION_CONFLICT")))
    }.handleErrorWith(e => log(s"integration.action.failed $context errorType=${e.getClass.getSimpleName}"))
  }

  private def log(message: String): IO[Unit] = logger.info(message).handleErrorWith(_ => IO.unit)
}
