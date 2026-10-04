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
import domain.configuration.ConfigurationProfileKind
import domain.integration._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.FiniteDuration

final class IntegrationConfigRollouts[Tx[_]: MonadThrow](integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], profiles: ConfigurationProfileQuery[Tx],
  configs: IntegrationConfigProfileRepository[Tx], deployments: IntegrationConfigDeploymentRepository[Tx],
  rollouts: IntegrationConfigRolloutRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx],
  syncState: IntegrationSyncStateRepository[Tx], runner: TransactionRunner[IO, Tx],
  operational: Boolean) {

  private val missing = IntegrationError("INTEGRATION_CONFIG_ROLLOUT_NOT_FOUND", "Rollout was not found")
  private def fail[A](code: String, message: String): Tx[A] = IntegrationError(code, message).raiseError[Tx, A]

  def preview(org: UUID, integrationId: UUID, objectId: UUID,
    revisionNumber: Int): IO[IntegrationConfigRolloutPreview] = runner.run(for {
    _ <- MonadThrow[Tx].raiseUnless(operational)(IntegrationError(
      "INTEGRATION_CONFIG_ROLLOUT_DISABLED", "Guarded rollout is unavailable for this deployment"))
    value <- rollouts.preview(org, integrationId, objectId, revisionNumber).flatMap(_.liftTo[Tx](
      IntegrationError("INTEGRATION_CONFIG_REMOTE_DRIFT", "Remote configuration does not match a local revision")))
    _ <- MonadThrow[Tx].raiseUnless(value.baselineSha256 != value.targetSha256)(IntegrationError(
      "INTEGRATION_CONFIG_ALREADY_APPLIED", "Target revision is already applied"))
  } yield value)

  def start(actor: ActorContext, integrationId: UUID, objectId: UUID, revisionNumber: Int,
    requestId: UUID, automaticRollback: Boolean): IO[IntegrationConfigRollout] = runner.run(
    rollouts.findByRequest(actor.organizationId, requestId).flatMap {
      case Some(existing) if existing.integrationId == integrationId &&
          existing.inventoryObjectId == objectId && existing.targetRevisionNumber == revisionNumber &&
          existing.automaticRollback == automaticRollback =>
        MonadThrow[Tx].pure[IntegrationConfigRollout](existing)
      case Some(_) => fail[IntegrationConfigRollout]("INTEGRATION_CONFIG_ROLLOUT_REQUEST_ID_CONFLICT", "Request ID was already used")
      case None => for {
        _ <- MonadThrow[Tx].raiseUnless(operational)(IntegrationError(
          "INTEGRATION_CONFIG_ROLLOUT_DISABLED", "Guarded rollout is unavailable for this deployment"))
        integration <- integrations.findByIdForUpdate(actor.organizationId, integrationId).flatMap(_.liftTo[Tx](
          IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))
        _ <- MonadThrow[Tx].raiseUnless(integration.enabled)(IntegrationError(
          "INTEGRATION_CONFIG_ROLLOUT_REQUIRES_SYNC", "Guarded rollout requires automatic synchronization"))
        obj <- inventory.findObject(actor.organizationId, integrationId, objectId, forUpdate = true)
          .flatMap(_.liftTo[Tx](IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Config profile was not found")))
        _ <- MonadThrow[Tx].raiseUnless(obj.isActive && obj.objectType == IntegrationObjectType.ConfigProfile)(
          IntegrationError("INTEGRATION_CONFIG_PROFILE_INACTIVE", "Config profile is inactive"))
        binding <- configs.binding(actor.organizationId, integrationId, objectId).flatMap(_.liftTo[Tx](
          IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Config profile was not found")))
        recentDeployments <- deployments.recentForBinding(actor.organizationId, binding.id, 1)
        _ <- MonadThrow[Tx].raiseWhen(recentDeployments.headOption.exists(d =>
          d.status == IntegrationConfigDeploymentStatus.Queued || d.status == IntegrationConfigDeploymentStatus.Running))(
          IntegrationError("INTEGRATION_CONFIG_DEPLOYMENT_ALREADY_RUNNING",
            "A configuration deployment is already active"))
        profile <- profiles.find(actor.organizationId, binding.configurationProfileId).flatMap(_.liftTo[Tx](
          IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Config profile was not found")))
        _ <- MonadThrow[Tx].raiseUnless(profile.kind == ConfigurationProfileKind.RemnawaveConfig && !profile.archived)(
          IntegrationError("INTEGRATION_CONFIG_PROFILE_UNAVAILABLE", "Managed profile is unavailable"))
        revision <- profiles.findRevision(actor.organizationId, profile.id, revisionNumber).flatMap(_.liftTo[Tx](
          IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Revision was not found")))
        targetHash <- configs.revisionHash(actor.organizationId, profile.id, revisionNumber).flatMap(_.liftTo[Tx](
          IntegrationError("INTEGRATION_CONFIG_PROFILE_NOT_FOUND", "Revision was not found")))
        id <- ids.nextId
        now <- time.now
        value = IntegrationConfigRollout(id, actor.organizationId, integrationId, objectId, binding.id,
          profile.id, None, None, None, revision.revision.id, revisionNumber, targetHash, requestId,
          actor.userId, automaticRollback, IntegrationConfigRolloutStatus.Preparing,
          createdAt = now, updatedAt = now)
        stored <- rollouts.insertOrFind(value)
        (result, created) = stored
        _ <- MonadThrow[Tx].raiseUnless(created || (result.integrationId == integrationId &&
          result.inventoryObjectId == objectId && result.targetRevisionNumber == revisionNumber &&
          result.automaticRollback == automaticRollback))(IntegrationError(
          "INTEGRATION_CONFIG_ROLLOUT_REQUEST_ID_CONFLICT", "Request ID was already used"))
        _ <- if (created) audit.record(actor, AuditAction.IntegrationConfigRolloutRequested,
          AuditTargetType.Integration, Some(integrationId)) *> syncState.scheduleAt(actor.organizationId, integrationId, now)
          else ().pure[Tx]
      } yield result
    })

  def get(org: UUID, id: UUID): IO[(IntegrationConfigRollout, List[IntegrationConfigRolloutNodeHealth])] =
    runner.run(rollouts.find(org, id).flatMap(_.liftTo[Tx](missing))).flatMap { rollout =>
      runner.run(rollouts.nodeHealth(org, id)).map(rollout -> _)
    }

  def byRequest(org: UUID, requestId: UUID): IO[Option[IntegrationConfigRollout]] =
    runner.run(rollouts.findByRequest(org, requestId))

  def history(org: UUID, integrationId: UUID, objectId: UUID, limit: Int) =
    runner.run(rollouts.recent(org, integrationId, objectId, limit))

  def cancel(actor: ActorContext, integrationId: UUID, objectId: UUID,
    id: UUID): IO[IntegrationConfigRollout] = runner.run(for {
    before <- rollouts.find(actor.organizationId, id).flatMap(_.filter(value =>
      value.integrationId == integrationId && value.inventoryObjectId == objectId).liftTo[Tx](missing))
    now <- time.now
    result <- if (before.status == IntegrationConfigRolloutStatus.Cancelled) before.some.pure[Tx]
      else rollouts.cancel(actor.organizationId, id, now)
    value <- result.liftTo[Tx](missing)
    _ <- if (before.status != IntegrationConfigRolloutStatus.Cancelled &&
      value.status == IntegrationConfigRolloutStatus.Cancelled)
      audit.record(actor, AuditAction.IntegrationConfigRolloutCancelled,
        AuditTargetType.Integration, Some(value.integrationId)) else ().pure[Tx]
  } yield value)
}

/** DB-only orchestration. Remote writes remain exclusively in IntegrationConfigDeploymentWorker. */
final class IntegrationConfigRolloutWorker[Tx[_]: MonadThrow](rollouts: IntegrationConfigRolloutRepository[Tx],
  integrations: IntegrationRepository[Tx], syncState: IntegrationSyncStateRepository[Tx],
  runner: TransactionRunner[IO, Tx], time: TimeProvider[IO], logger: Logger[IO],
  poll: FiniteDuration, batch: Int, concurrency: Int, claimLease: FiniteDuration,
  verifyTimeout: FiniteDuration, owner: UUID) {

  def run: IO[Nothing] = (tick.handleErrorWith(e => log(
    s"integration.config.rollout.tick.failed errorType=${e.getClass.getSimpleName}")) *> IO.sleep(poll)).foreverM

  def tick: IO[Unit] = time.now.flatMap { at =>
    runner.run(rollouts.recoverAndClaim(owner, UUID.randomUUID(), at,
      at.plusMillis(claimLease.toMillis), batch)).flatMap(_.parTraverseN(concurrency)(advance).void)
  }

  private def advance(value: IntegrationConfigRollout): IO[Unit] = {
    val token = value.claimToken.get
    val atIO = time.now
    value.status match {
      case IntegrationConfigRolloutStatus.Preparing => atIO.flatMap { at =>
        runner.run(rollouts.prepare(value, token, UUID.randomUUID(), UUID.randomUUID(), at)).flatMap {
          case true => logEvent("deployment_created", value)
          case false => runner.run(rollouts.release(value, token, at)).void
        }
      }
      case IntegrationConfigRolloutStatus.Applying => child(value, token, rollback = false)
      case IntegrationConfigRolloutStatus.RollbackApplying => child(value, token, rollback = true)
      case IntegrationConfigRolloutStatus.Verifying => verify(value, token, rollback = false)
      case IntegrationConfigRolloutStatus.RollbackVerifying => verify(value, token, rollback = true)
      case _ => IO.unit
    }
  }

  private def child(value: IntegrationConfigRollout, token: UUID, rollback: Boolean): IO[Unit] = {
    val id = if (rollback) value.rollbackDeploymentId else value.targetDeploymentId
    id.fold(IO.unit) { deploymentId => runner.run(rollouts.childDeployment(value.organizationId, deploymentId)).flatMap {
      case Some(d) if d.status == IntegrationConfigDeploymentStatus.Failed => time.now.flatMap(at =>
        runner.run(rollouts.finish(value, token, at, "FAILED", None,
          Some(if (rollback) "INTEGRATION_CONFIG_ROLLBACK_FAILED" else d.errorCode.getOrElse("INTEGRATION_CONFIG_ROLLOUT_FAILED")),
          Some(if (rollback) "Target configuration may still be active; manual attention is required"
            else "Target deployment failed"))).void)
      case Some(d) if d.status == IntegrationConfigDeploymentStatus.Succeeded ||
          d.status == IntegrationConfigDeploymentStatus.Unknown => time.now.flatMap { at =>
        runner.run(rollouts.beginVerification(value, token, at,
          at.plusMillis(verifyTimeout.toMillis), rollback)).flatMap { moved =>
          if (moved) nudge(value, at) *> logEvent("verifying", value) else IO.unit
        }
      }
      case _ => time.now.flatMap(at => runner.run(rollouts.release(value, token, at)).void)
    }}
  }

  private def verify(value: IntegrationConfigRollout, token: UUID, rollback: Boolean): IO[Unit] = {
    val deploymentId = if (rollback) value.rollbackDeploymentId else value.targetDeploymentId
    deploymentId.fold(IO.unit)(id => runner.run(rollouts.childDeployment(value.organizationId, id)).flatMap {
      case None => release(value, token)
      case Some(deployment) =>
        val after = deployment.finishedAt.getOrElse(deployment.createdAt)
        runner.run(rollouts.inspect(value, after)).flatMap {
          case None => time.now.flatMap { at =>
            if (value.verificationDeadlineAt.exists(!at.isBefore(_))) finish(value, token, at, "UNKNOWN", None,
              "INTEGRATION_CONFIG_ROLLOUT_VERIFICATION_TIMEOUT", "No fresh successful observation")
            else runner.run(rollouts.release(value, token, at)).void
          }
          case Some(view) if rollback => verifyRollback(value, token, deployment, view)
          case Some(view) => verifyTarget(value, token, deployment, view)
        }
    })
  }

  private def verifyTarget(value: IntegrationConfigRollout, token: UUID,
    deployment: IntegrationConfigDeployment, view: IntegrationConfigRolloutInspection): IO[Unit] = time.now.flatMap { at =>
    if (view.observedSha256.contains(value.targetSha256) && view.regressionNodes == 0 && !view.planDrift)
      finish(value, token, at, "SUCCEEDED", Some(view.sessionId), null, null)
    else if (view.observedSha256.contains(value.targetSha256)) {
      if (value.verificationDeadlineAt.exists(at.isBefore)) runner.run(rollouts.release(value, token, at)).void
      else if (value.automaticRollback) runner.run(rollouts.createRollback(value, token,
        UUID.randomUUID(), UUID.randomUUID(), view.sessionId, at)).void *> logEvent("rollback_created", value)
      else finish(value, token, at, "FAILED", Some(view.sessionId),
        if (view.planDrift) "INTEGRATION_CONFIG_ROLLOUT_PLAN_DRIFT" else "INTEGRATION_CONFIG_ROLLOUT_HEALTH_REGRESSION",
        "Rollout health check failed; automatic rollback was disabled")
    } else if (deployment.status == IntegrationConfigDeploymentStatus.Unknown &&
      view.observedSha256 == value.baselineSha256)
      finish(value, token, at, "FAILED", Some(view.sessionId),
        "INTEGRATION_CONFIG_ROLLOUT_NOT_APPLIED", "Target configuration was not applied")
    else finish(value, token, at, "UNKNOWN", Some(view.sessionId),
      "INTEGRATION_CONFIG_ROLLOUT_REMOTE_CHANGED", "Remote configuration changed unexpectedly")
  }

  private def verifyRollback(value: IntegrationConfigRollout, token: UUID,
    deployment: IntegrationConfigDeployment, view: IntegrationConfigRolloutInspection): IO[Unit] = time.now.flatMap { at =>
    if (view.observedSha256 == value.baselineSha256 && view.regressionNodes == 0 && !view.planDrift)
      finish(value, token, at, "ROLLED_BACK", Some(view.sessionId), null, null)
    else if (value.verificationDeadlineAt.exists(at.isBefore)) runner.run(rollouts.release(value, token, at)).void
    else if (view.observedSha256.contains(value.targetSha256) &&
      deployment.status == IntegrationConfigDeploymentStatus.Unknown)
      finish(value, token, at, "FAILED", Some(view.sessionId), "INTEGRATION_CONFIG_ROLLBACK_NOT_APPLIED",
        "Target configuration may still be active; manual attention is required")
    else finish(value, token, at, "UNKNOWN", Some(view.sessionId),
      "INTEGRATION_CONFIG_ROLLOUT_REMOTE_CHANGED", "Remote configuration changed unexpectedly")
  }

  private def finish(value: IntegrationConfigRollout, token: UUID, at: Instant, status: String,
    session: Option[UUID], code: String, message: String): IO[Unit] =
    runner.run(rollouts.finish(value, token, at, status, session, Option(code), Option(message))).void *>
      logEvent(status.toLowerCase, value)

  private def release(value: IntegrationConfigRollout, token: UUID) =
    time.now.flatMap(at => runner.run(rollouts.release(value, token, at)).void)

  private def nudge(value: IntegrationConfigRollout, at: Instant): IO[Unit] = runner.run(for {
    integration <- integrations.findById(value.organizationId, value.integrationId)
    _ <- integration.filter(_.enabled).traverse_(i => syncState.scheduleAt(i.organizationId, i.id, at))
  } yield ())

  private def logEvent(event: String, value: IntegrationConfigRollout) = log(
    s"integration.config.rollout.$event organizationId=${value.organizationId} integrationId=${value.integrationId} " +
      s"inventoryObjectId=${value.inventoryObjectId} rolloutId=${value.id} targetRevision=${value.targetRevisionNumber}")
  private def log(message: String) = logger.info(message).handleErrorWith(_ => IO.unit)
}
