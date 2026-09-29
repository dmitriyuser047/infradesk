package ru.bitec.app.ops
package application.integration

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port._
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.integration._
import org.typelevel.log4cats.Logger

import java.util.UUID
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** What a scheduled run amounted to, as the scheduler needs to know it for backoff. */
sealed trait ScheduledIntegrationSync
object ScheduledIntegrationSync {
  final case class Finished(session: IntegrationSyncSession) extends ScheduledIntegrationSync
  case object AlreadyRunning extends ScheduledIntegrationSync
  /** The integration was deleted or disabled after the claim. */
  case object Skipped extends ScheduledIntegrationSync
}

/** The session the first transaction created, with what the observation needs. */
final case class PreparedIntegrationSync(integration: Integration, secret: IntegrationSecret,
  session: IntegrationSyncSession)

/** Database steps of one synchronization. Each method is one transaction; nothing here performs I/O
  * outside the database, and the provider is never contacted while a transaction is open.
  */
final class IntegrationSyncTransactions[Tx[_]: MonadThrow](
  integrations: IntegrationRepository[Tx], secrets: IntegrationSecretRepository[Tx],
  sessions: IntegrationSyncSessionRepository[Tx], inventory: IntegrationInventoryRepository[Tx],
  ids: IdGenerator[Tx], time: TimeProvider[Tx], audit: AuditRecorder[Tx]
) {
  import IntegrationSync._

  /** TX1: the integration and its credential are loaded, abandoned sessions are retired and a
    * RUNNING session is claimed. Returns None when another synchronization holds the slot; the
    * recovery of stale sessions commits either way.
    */
  def prepare(organizationId: UUID, integrationId: UUID, trigger: IntegrationSyncTrigger,
    actor: Option[ActorContext], recoverAfter: FiniteDuration,
    requireEnabled: Boolean): Tx[Option[(Option[PreparedIntegrationSync], List[IntegrationSyncSession])]] =
    integrations.findById(organizationId, integrationId).flatMap {
      case None if requireEnabled => none[(Option[PreparedIntegrationSync], List[IntegrationSyncSession])].pure[Tx]
      case None => IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found").raiseError
      case Some(integration) if requireEnabled && !integration.enabled =>
        none[(Option[PreparedIntegrationSync], List[IntegrationSyncSession])].pure[Tx]
      case Some(integration) => for {
        secret <- secrets.find(organizationId, integration.secretId).flatMap(
          _.liftTo[Tx](IntegrationError("INTEGRATION_CREDENTIAL_MISSING", "Integration credential is missing")))
        id <- ids.nextId
        now <- time.now
        session = IntegrationSyncSession(id, organizationId, integrationId, trigger, actor.map(_.userId), now,
          now.plusMillis(recoverAfter.toMillis), None, IntegrationSyncStatus.Running, None, None, None)
        claim <- sessions.recoverStaleAndTryCreate(session, now, StaleCode, StaleMessage)
        _ <- actor.filter(_ => claim.created).traverse_(value =>
          audit.record(value, AuditAction.IntegrationSyncRequested, AuditTargetType.Integration, Some(integrationId)))
      } yield Some((Option.when(claim.created)(PreparedIntegrationSync(integration, secret, session)), claim.recovered))
    }

  /** TX2: the whole snapshot and the completion of the session, or nothing. When the session was
    * retired meanwhile (it outlived its deadline), the snapshot is rolled back with it.
    */
  def persist(prepared: PreparedIntegrationSync, observation: IntegrationObservation): Tx[IntegrationSyncSession] = {
    val session = prepared.session
    for {
      current <- integrations.findByIdForUpdate(session.organizationId, session.integrationId)
      _ <- Either.cond(current.exists(value => value.baseUrl == prepared.integration.baseUrl &&
        value.secretId == prepared.integration.secretId), (), ConfigurationChanged).liftTo[Tx]
      now <- time.now
      deactivated <- inventory.applySnapshot(session.organizationId, session.integrationId, session.id, observation, now)
      counts = IntegrationSyncCounts(observation.count(IntegrationObjectType.Node),
        observation.count(IntegrationObjectType.Host), observation.count(IntegrationObjectType.ConfigProfile), deactivated)
      completed <- sessions.complete(session.organizationId, session.id, now, counts)
      _ <- if (completed) ().pure[Tx] else (StaleSessionRetired: Throwable).raiseError[Tx, Unit]
    } yield session.copy(finishedAt = Some(now), status = IntegrationSyncStatus.Completed, counts = Some(counts))
  }

  /** Fails a still-RUNNING session. When it was already finished elsewhere (retired as stale while
    * the provider was being read), that durable outcome stands and is what is returned: a later
    * error of this attempt never overrides it.
    */
  def fail(session: IntegrationSyncSession, code: String): Tx[IntegrationSyncSession] = for {
    now <- time.now
    failed <- sessions.fail(session.organizationId, session.id, now, code, messageFor(code))
    result <- if (failed) session.copy(finishedAt = Some(now), status = IntegrationSyncStatus.Failed,
      errorCode = Some(code), errorMessage = Some(messageFor(code))).pure[Tx] else durable(session)
  } yield result

  /** The session as the database holds it. */
  def durable(session: IntegrationSyncSession): Tx[IntegrationSyncSession] =
    sessions.find(session.organizationId, session.id).flatMap(_.liftTo[Tx](
      new IllegalStateException("Synchronization session disappeared")))
}

/** One read-only observation of an integration: TX1 claims a RUNNING session, the provider is read
  * outside any transaction, TX2 applies the snapshot atomically or the session fails and the stored
  * inventory stays exactly as it was.
  */
final class IntegrationSync[Tx[_]](transactions: IntegrationSyncTransactions[Tx],
  runner: TransactionRunner[IO, Tx], cipher: IntegrationCryptography, providers: IntegrationProviderRegistry[IO],
  logger: Logger[IO], attemptTimeout: FiniteDuration, maxObjects: Int) {
  import IntegrationSync._

  // A session outlives its attempt only when the process died; the grace covers the final transaction.
  private val recoverAfter = attemptTimeout + RecoveryGrace

  /** Works whether or not the integration is enabled; only automatic runs require it. */
  def manual(actor: ActorContext, integrationId: UUID): IO[IntegrationSyncSession] =
    runner.run(transactions.prepare(actor.organizationId, integrationId, IntegrationSyncTrigger.Manual,
      Some(actor), recoverAfter, requireEnabled = false)).flatMap {
      case Some((Some(prepared), recovered)) => logRecovered(recovered) *> run(prepared)
      case Some((None, recovered)) => logRecovered(recovered) *>
        IO.raiseError(IntegrationError(AlreadyRunningCode, "Integration synchronization is already running"))
      case None => IO.raiseError(IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found"))
    }

  def scheduled(organizationId: UUID, integrationId: UUID): IO[ScheduledIntegrationSync] =
    runner.run(transactions.prepare(organizationId, integrationId, IntegrationSyncTrigger.Scheduled, None,
      recoverAfter, requireEnabled = true)).flatMap {
      case Some((Some(prepared), recovered)) =>
        logRecovered(recovered) *> run(prepared).map(ScheduledIntegrationSync.Finished(_))
      case Some((None, recovered)) => logRecovered(recovered).as(ScheduledIntegrationSync.AlreadyRunning)
      case None => IO.pure(ScheduledIntegrationSync.Skipped)
    }

  private def run(prepared: PreparedIntegrationSync): IO[IntegrationSyncSession] = {
    val session = prepared.session
    val context = s"organizationId=${session.organizationId} integrationId=${session.integrationId} " +
      s"syncSessionId=${session.id} trigger=${session.trigger.code}"
    IO.monotonic.flatMap { started =>
      def elapsed = IO.monotonic.map(finished => (finished - started).toMillis)
      info(s"integration.sync.started $context providerType=${prepared.integration.providerType.code}") *>
        observe(prepared).attempt.flatMap {
          case Left(error) =>
            val code = codeOf(error)
            failSession(session, code, context, elapsed)
          case Right(observation) =>
            elapsed.flatMap(ms => info(s"integration.sync.remote.completed $context nodes=${observation.count(IntegrationObjectType.Node)} " +
              s"hosts=${observation.count(IntegrationObjectType.Host)} configProfiles=${observation.count(IntegrationObjectType.ConfigProfile)} durationMs=$ms")) *>
              runner.run(transactions.persist(prepared, observation)).attempt.flatMap {
                case Right(completed) =>
                  val counts = completed.counts.getOrElse(IntegrationSyncCounts(0, 0, 0, 0))
                  info(s"integration.sync.snapshot.persisted $context deactivated=${counts.deactivated}") *>
                    elapsed.flatMap(ms => info(s"integration.sync.completed $context status=COMPLETED durationMs=$ms")).as(completed)
                case Left(StaleSessionRetired) =>
                  // Another worker already finished this session; the snapshot was rolled back and
                  // the durable outcome is the answer.
                  runner.run(transactions.durable(session)).flatTap(stored => elapsed.flatMap(ms =>
                    error(s"integration.sync.failed $context errorCode=${stored.errorCode.getOrElse("NONE")} " +
                      s"retired=true durationMs=$ms")))
                case Left(ConfigurationChanged) =>
                  failSession(session, ConfigurationChangedCode, context, elapsed)
                case Left(other) =>
                  failSession(session, PersistFailedCode, context, elapsed, Some(other.getClass.getSimpleName))
              }
        }
    }
  }

  private def observe(prepared: PreparedIntegrationSync): IO[IntegrationObservation] = {
    val integration = prepared.integration
    IO.fromEither(providers.find(integration.providerType).toRight(
      IntegrationError("INTEGRATION_PROVIDER_UNSUPPORTED", "Integration provider is unavailable"))).flatMap { provider =>
      IO.delay(cipher.decrypt(prepared.secret)).handleErrorWith(_ => IO.raiseError(
        IntegrationError("INTEGRATION_CREDENTIAL_INVALID", "Integration credential is invalid")))
        .flatMap(credential => provider.observe(IntegrationRuntimeContext(integration.id,
          integration.organizationId, integration.baseUrl, credential)))
    }.timeout(attemptTimeout).flatMap(validated)
  }

  /** A snapshot that names one object twice, or more than the limit, is not a snapshot to trust. */
  private def validated(observation: IntegrationObservation): IO[IntegrationObservation] = {
    val identities = observation.objects.map(value => (value.objectType, value.externalId))
    if (observation.objects.size > maxObjects || identities.distinct.size != identities.size)
      IO.raiseError(IntegrationError("INTEGRATION_INVALID_RESPONSE", "INTEGRATION_INVALID_RESPONSE"))
    else IO.pure(observation)
  }

  private def failSession(session: IntegrationSyncSession, code: String, context: String, elapsed: IO[Long],
    errorType: Option[String] = None): IO[IntegrationSyncSession] =
    runner.run(transactions.fail(session, code)).flatMap { failed =>
      // The logged code is the stored one, which differs from `code` when the session was retired first.
      elapsed.flatMap(ms => error(s"integration.sync.failed $context errorCode=${failed.errorCode.getOrElse(code)}" +
        (if (failed.errorCode.contains(code)) "" else s" attemptErrorCode=$code retired=true") +
        errorType.fold("")(value => s" errorType=$value") + s" durationMs=$ms")).as(failed)
    }

  private def logRecovered(recovered: List[IntegrationSyncSession]): IO[Unit] = recovered.traverse_(value =>
    logger.warn(s"integration.sync.stale.recovered organizationId=${value.organizationId} " +
      s"integrationId=${value.integrationId} syncSessionId=${value.id}").handleErrorWith(_ => IO.unit))

  private def info(message: String): IO[Unit] = logger.info(message).handleErrorWith(_ => IO.unit)
  private def error(message: String): IO[Unit] = logger.error(message).handleErrorWith(_ => IO.unit)
}

object IntegrationSync {
  val AlreadyRunningCode = "INTEGRATION_SYNC_ALREADY_RUNNING"
  val StaleCode = "INTEGRATION_SYNC_STALE"
  val StaleMessage = "Synchronization was abandoned and recovered"
  val PersistFailedCode = "INTEGRATION_SYNC_FAILED"
  val ConfigurationChangedCode = "INTEGRATION_CONFIGURATION_CHANGED"
  val RecoveryGrace: FiniteDuration = scala.concurrent.duration.DurationInt(60).seconds

  private[integration] case object StaleSessionRetired extends RuntimeException("Synchronization session was retired")
  private[integration] case object ConfigurationChanged extends RuntimeException("Integration configuration changed")

  /** Only error codes reach a session: never a provider's text, a URL or a credential. */
  def codeOf(error: Throwable): String = error match {
    case value: IntegrationError if value.code.startsWith("INTEGRATION_") => value.code
    case _: TimeoutException => "INTEGRATION_TIMEOUT"
    case _ => PersistFailedCode
  }

  def messageFor(code: String): String = code match {
    case "INTEGRATION_AUTH_FAILED" => "Remote API rejected the credentials"
    case "INTEGRATION_FORBIDDEN" => "Remote API denied access"
    case "INTEGRATION_ENDPOINT_NOT_FOUND" => "Remote API endpoint was not found"
    case "INTEGRATION_RATE_LIMITED" => "Remote API rate limit was reached"
    case "INTEGRATION_REMOTE_UNAVAILABLE" => "Remote API is unavailable"
    case "INTEGRATION_INVALID_RESPONSE" => "Remote API returned an invalid response"
    case "INTEGRATION_TIMEOUT" => "Remote API did not respond in time"
    case "INTEGRATION_TLS_ERROR" => "TLS connection to the remote API failed"
    case "INTEGRATION_UNREACHABLE" => "Remote API is unreachable"
    case "INTEGRATION_DESTINATION_NOT_ALLOWED" => "Remote API address is not allowed"
    case "INTEGRATION_CREDENTIAL_INVALID" => "Integration credential is invalid"
    case "INTEGRATION_PROVIDER_UNSUPPORTED" => "Integration provider is unavailable"
    case ConfigurationChangedCode => "Integration configuration changed during synchronization"
    case StaleCode => StaleMessage
    case _ => "Synchronization failed"
  }
}
