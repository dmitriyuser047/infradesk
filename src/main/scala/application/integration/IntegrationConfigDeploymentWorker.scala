package ru.bitec.app.ops
package application.integration

import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.effect.syntax.all._
import cats.syntax.all._
import domain.configuration.CanonicalJson
import domain.integration._
import io.circe.parser.parse
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** The sole owner of Remnawave config PATCH. Claims and completions are fenced in PostgreSQL. */
final class IntegrationConfigDeploymentWorker[Tx[_]: MonadThrow](deployments: IntegrationConfigDeploymentRepository[Tx],
  configs: IntegrationConfigProfileRepository[Tx], integrations: IntegrationRepository[Tx],
  inventory: IntegrationInventoryRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  cipher: IntegrationCryptography, providers: IntegrationProviderRegistry[IO],
  syncState: IntegrationSyncStateRepository[Tx], runner: TransactionRunner[IO, Tx],
  time: TimeProvider[IO], logger: Logger[IO], poll: FiniteDuration, batch: Int,
  concurrency: Int, requestTimeout: FiniteDuration, owner: UUID) {

  require(poll.toMillis > 0 && batch > 0 && concurrency > 0)
  private val deadline = requestTimeout + scala.concurrent.duration.DurationInt(60).seconds

  def run: IO[Nothing] =
    (tick.handleErrorWith(e => log(s"integration.config.deploy.tick.failed errorType=${e.getClass.getSimpleName}")) *>
      IO.sleep(poll)).foreverM

  def tick: IO[Unit] = {
    def wave(remaining: Int): IO[Unit] = if (remaining <= 0) IO.unit else
      time.now.flatMap { at => runner.run(deployments.recoverAndClaim(owner, UUID.randomUUID(), at,
        at.plusMillis(deadline.toMillis), math.min(remaining, concurrency))) }
        .flatMap { case (recovered, claimed) =>
          recovered.distinct.traverse_ { case (org, integrationId) => nudge(org, integrationId) } *>
            (if (recovered.nonEmpty) log(s"integration.config.deploy.stale.recovered count=${recovered.size}")
              else IO.unit) *>
            claimed.parTraverseN(concurrency)(execute).void *>
            (if (claimed.isEmpty) IO.unit else wave(remaining - claimed.size))
        }
    wave(batch)
  }

  private def execute(value: IntegrationConfigDeployment): IO[Unit] = {
    val context = s"organizationId=${value.organizationId} integrationId=${value.integrationId} " +
      s"inventoryObjectId=${value.inventoryObjectId} deploymentId=${value.id} revisionNumber=${value.revisionNumber}"
    val outcome = runner.run(for {
      integration <- integrations.findById(value.organizationId, value.integrationId)
      obj <- inventory.findObject(value.organizationId, value.integrationId, value.inventoryObjectId,
        forUpdate = false)
      secret <- integration.traverse(i => secrets.find(value.organizationId, i.secretId))
      revision <- configs.secureRevision(value.organizationId, value.configurationProfileId, value.revisionNumber)
    } yield (integration, obj, secret.flatten, revision)).flatMap {
      case (Some(i), Some(obj), Some(s), Some(revision)) if obj.isActive &&
          revision.revisionId == value.configurationRevisionId && revision.contentSha256 == value.desiredSha256 =>
        (for {
          credential <- IO.delay(cipher.decrypt(s))
          transport <- providers.find(i.providerType).flatMap(_.configProfiles)
            .liftTo[IO](IntegrationError("INTEGRATION_CONFIG_PROFILE_UNSUPPORTED", "Provider unsupported"))
          runtime = IntegrationRuntimeContext(i.id, i.organizationId, i.baseUrl, credential)
          _ <- log(s"integration.config.deploy.preflight $context expectedRemoteSha256=${value.expectedRemoteSha256}")
          remote <- transport.fetchConfigProfile(runtime, obj.externalId)
          result <- if (remote.externalId != obj.externalId ||
            CanonicalJson.sha256(remote.config) != value.expectedRemoteSha256)
            IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CONFIG_REMOTE_CHANGED"))
          else parse(revision.canonicalJson).toOption.filter(_.isObject) match {
            case None => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CONFIG_INVALID"))
            case Some(config) => transport.updateConfigProfile(runtime, obj.externalId,
              config, value.desiredSha256).handleError(_ =>
                IntegrationActionRemoteOutcome.OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN"))
          }
        } yield result).handleError {
          case e: IntegrationError => IntegrationActionRemoteOutcome.DefinitelyFailed(e.code)
          case _ => IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CONFIG_PREFLIGHT_FAILED")
        }
      case _ => IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_CONFIG_PROFILE_UNAVAILABLE"))
    }.timeoutTo(deadline, IO.pure(IntegrationActionRemoteOutcome.OutcomeUnknown(
      "INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN")))

    log(s"integration.config.deploy.claimed $context") *> outcome.flatMap { result =>
      val (status, code, message) = result match {
        case IntegrationActionRemoteOutcome.Succeeded => ("SUCCEEDED", None, None)
        case IntegrationActionRemoteOutcome.DefinitelyFailed(reason) =>
          ("FAILED", Some(reason), Some("Configuration deployment failed"))
        case IntegrationActionRemoteOutcome.OutcomeUnknown(_) =>
          ("UNKNOWN", Some("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN"), Some("Remote result is unknown"))
      }
      time.now.flatMap(at => runner.run(for {
        completed <- deployments.complete(value, value.claimToken.get, at, status, code, message)
        integration <- if (completed && (status == "SUCCEEDED" || status == "UNKNOWN"))
          integrations.findById(value.organizationId, value.integrationId)
        else (None: Option[Integration]).pure[Tx]
        _ <- integration.filter(_.enabled).traverse_(i => syncState.scheduleAt(i.organizationId, i.id, at))
      } yield completed)).flatMap(ok => log(s"integration.config.deploy.${if (ok) status.toLowerCase else "completion.conflict"} " +
        context + code.fold("")(c => s" errorCode=$c")))
    }.handleErrorWith(e => log(s"integration.config.deploy.failed $context errorType=${e.getClass.getSimpleName}"))
  }

  private def nudge(org: UUID, integrationId: UUID): IO[Unit] =
    time.now.flatMap(at => runner.run(for {
      i <- integrations.findById(org, integrationId)
      _ <- i.filter(_.enabled).traverse_(value => syncState.scheduleAt(org, value.id, at))
    } yield ()))

  private def log(message: String): IO[Unit] = logger.info(message).handleErrorWith(_ => IO.unit)
}
