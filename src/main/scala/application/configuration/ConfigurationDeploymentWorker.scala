package ru.bitec.app.ops
package application.configuration

import application.port._
import cats.effect.std.{Semaphore, Supervisor}
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._
import domain.configuration._
import domain.connection.Connection
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/** Applies deployment snapshots to remote servers, one lease per deployment.
  *
  * Durable state moves only through fenced, short transactions; SSH work never holds a database
  * transaction. The persisted phase says how far a deployment got, so a worker that takes over an
  * expired lease inspects the remote target and continues or rolls back instead of starting again.
  * Correctness never depends on anything held in this process: two instances may run this worker.
  */
final class ConfigurationDeploymentWorker[Tx[_]](
  deployments: ConfigurationDeploymentRepository[Tx],
  assignments: ConfigurationAssignmentRepository[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  sources: ConfigurationDeploymentSourceQuery[Tx],
  remote: RemoteConfigurationTransport[IO],
  runner: TransactionRunner[IO, Tx],
  owner: UUID,
  settings: ConfigurationDeploymentSettings,
  logger: Logger[IO],
  clock: IO[Instant] = IO.realTimeInstant,
  /** Restricts the worker to one organization; production workers serve every tenant. */
  scope: Option[UUID] = None
) {
  import ConfigurationDeploymentPhase._
  import ConfigurationDeploymentWorker._

  /** Claims and runs deployments until cancelled. On cancellation no new work is claimed, running
    * deployments stop at their next safe phase boundary and give their leases back, and the
    * supervisor waits for them, so no SSH session outlives the worker.
    */
  def run: IO[Nothing] =
    (Supervisor[IO](await = true), Resource.eval(Ref[IO].of(false)), Resource.eval(Semaphore[IO](settings.maxConcurrency.toLong)))
      .tupled.use { case (supervisor, stopping, permits) =>
        val iteration = for {
          free <- permits.available
          _ <- if (free > 0) claim(free.toInt).flatMap(_.traverse_ { case (deployment, token) =>
            permits.acquire *> supervisor.supervise(process(deployment, token, stopping.get).guarantee(permits.release))
          }) else IO.unit
          _ <- cleanupBackups
        } yield ()
        (iteration.handleErrorWith(error => logger.error(error)("configuration.deployment.worker.failed")) *>
          IO.sleep(settings.pollInterval)).foreverM.onCancel(stopping.set(true))
      }

  /** One claim round that waits for everything it claimed; deterministic for tests. */
  def tick: IO[Unit] = tick(IO.pure(false))

  def tick(stopping: IO[Boolean]): IO[Unit] =
    claim(settings.maxConcurrency).flatMap(_.parTraverse_ { case (deployment, token) =>
      process(deployment, token, stopping)
    }) *> cleanupBackups

  private def claim(limit: Int): IO[List[(ConfigurationDeployment, UUID)]] = for {
    token <- IO(UUID.randomUUID())
    now <- clock
    claimed <- runner.run(deployments.claim(owner, token, now, now.plusMillis(settings.leaseDuration.toMillis),
      limit, settings.perOrganizationLimit, scope))
  } yield claimed.map(_ -> token)

  /** Removes rollout backups whose rollout has ended. A failure leaves the applied file alone. */
  def cleanupBackups: IO[Unit] = for {
    now <- clock
    claimed <- runner.run(deployments.claimBackupCleanup(now, now.plusMillis(settings.leaseDuration.toMillis), 10, scope))
    _ <- claimed.traverse_ { item =>
      val backup = ConfigurationDeployment.artifactPaths(item.targetPath, item.deploymentId).backup
      val removal = runner.run(sources.resolve(item.organizationId, item.resourceId, item.connectionId)).flatMap {
        case ConfigurationDeploymentSource.Ready(connection) if connection.updatedAt == item.connectionUpdatedAt =>
          remote.withSession(connection)(_.remove(backup)).as(true)
        case _ => IO.pure(false)
      }.handleErrorWith { error =>
        logger.warn(s"configuration.deployment.backup_cleanup.failed organizationId=${item.organizationId} " +
          s"deploymentId=${item.deploymentId} code=${codeOf(error)}").as(false)
      }
      removal.flatMap(removed => clock.flatMap(at => runner.run(deployments.finishBackupCleanup(
        item.organizationId, item.deploymentId, removed, settings.backupCleanupAttempts,
        at.plusMillis(settings.transientBackoff.toMillis), at))))
    }
  } yield ()

  private def process(initial: ConfigurationDeployment, token: UUID, stopping: IO[Boolean]): IO[Unit] = {
    val context = s"organizationId=${initial.organizationId} deploymentId=${initial.id} " +
      s"assignmentId=${initial.assignmentId} resourceId=${initial.resourceId} profileId=${initial.profileId} " +
      s"profileRevisionNumber=${initial.profileRevisionNumber} connectionId=${initial.connectionId}" +
      initial.rolloutId.fold("")(id => s" rolloutId=$id")
    val heartbeat = (IO.sleep(settings.heartbeat) *> renew(initial, token).attempt.void).foreverM
    val execution = for {
      phase <- Ref[IO].of(initial.phase)
      attempts <- Ref[IO].of(initial.transientAttempts)
      _ <- logger.info(s"configuration.deployment.claimed $context phase=${initial.phase.code}")
      _ <- new Execution(initial, token, context, phase, attempts, stopping).run
    } yield ()
    heartbeat.background.use(_ => execution).handleErrorWith {
      case LostLease => logger.warn(s"configuration.deployment.lease_lost $context")
      case Stopped => logger.info(s"configuration.deployment.released $context")
      case error => logger.error(error)(s"configuration.deployment.unexpected $context")
    }
  }

  /** One worker's run of one deployment, from its persisted phase to a terminal state or a handover. */
  private final class Execution(
    deployment: ConfigurationDeployment,
    token: UUID,
    context: String,
    current: Ref[IO, ConfigurationDeploymentPhase],
    attempts: Ref[IO, Int],
    stopping: IO[Boolean]
  ) {
    private val paths = ConfigurationDeployment.artifactPaths(deployment.targetPath, deployment.id)
    private val limit = settings.maxRemoteFileBytes

    def run: IO[Unit] = {
      val body = for {
        connection <- connectionOrFail
        desired <- if (deployment.phase.order <= Replace.order) desiredBytes.map(Some(_)) else IO.pure(None)
        _ <- remote.withSession(connection)(session => new Steps(session, desired).run)
      } yield ()
      body.handleErrorWith(error => current.get.flatMap(phase => onError(phase, error)))
    }

    /** Execution against one open session. */
    private final class Steps(session: RemoteConfigurationSession[IO], desired: Option[Array[Byte]]) {

      def run: IO[Unit] = current.get.flatMap(from)

      private def from(phase: ConfigurationDeploymentPhase): IO[Unit] = phase match {
        case Precheck => precheck *> from(Upload)
        case Upload => upload *> from(Validate)
        case Validate => validate *> from(Replace)
        case Replace => replace *> from(Activate)
        case Activate => activate *> from(Verify)
        case Verify => verify *> from(Cleanup)
        case Cleanup => cleanup
        case Rollback => rollback
      }

      private def precheck: IO[Unit] = boundary(Precheck) *> (for {
        target <- readTarget
        _ <- requireExpected(target)
        _ <- IO.fromEither(creationFor(target))
        atomic <- session.supportsAtomicReplace
        _ <- IO.raiseUnless(atomic)(RemoteConfigurationFailure.AtomicReplaceUnsupported)
        _ <- advance(Precheck, Upload)
      } yield ())

      private def upload: IO[Unit] = boundary(Upload) *> (for {
        target <- readTarget
        _ <- requireExpected(target)
        creation <- IO.fromEither(creationFor(target))
        _ <- ensureArtifact(paths.temporary, desiredContent, creation)
        _ <- advance(Upload, Validate)
      } yield ())

      private def validate: IO[Unit] = boundary(Validate) *> (for {
        target <- readTarget
        _ <- requireExpected(target)
        _ <- deployment.policy.validator.traverse_ { validator =>
          guard *> session.execute(validator.executable, validator.argv(paths.temporary, deployment.targetPath),
            settings.validatorTimeout).attempt.flatMap {
            case Right(0) => IO.unit
            case Right(_) | Left(RemoteConfigurationFailure.CommandTimeout) => IO.raiseError(Failure(ValidationFailed))
            case Left(other) => IO.raiseError(other)
          }
        }
        // The backup holds the exact previous bytes and metadata; it is what a rollback restores.
        _ <- if (target.exists) IO.fromEither(creationFor(target)).flatMap(ensureArtifact(paths.backup, target.bytes, _))
          else IO.unit
        _ <- advance(Validate, Replace)
      } yield ())

      /** When this run itself entered REPLACE, nothing can have renamed the candidate yet, so every
        * failure before the rename leaves the target untouched. After a takeover that is unknown,
        * and only the rollback, which inspects the target, may decide.
        */
      private def replace: IO[Unit] = boundary(Replace) *> readTarget.adaptError {
        case error if fresh && error != LostLease => BeforeReplace(error)
      }.flatMap { target =>
        if (matchesExpected(target)) {
          val beforeRename = for {
            _ <- assignmentUnchanged
            creation <- IO.fromEither(creationFor(target))
            candidate <- session.read(paths.temporary, limit)
            _ <- IO.raiseUnless(candidate.sha256.contains(deployment.desiredSha256) &&
              candidate.metadata.exists(matches(_, creation)))(Failure(UploadFailed))
            _ <- if (!target.exists) IO.unit else session.read(paths.backup, limit).flatMap { backup =>
              IO.raiseUnless(backup.sha256 == target.sha256)(Failure(ReplaceFailed))
            }
            _ <- guard
          } yield ()
          // Failures up to here left the target untouched; the rename itself is the point of no return.
          beforeRename.adaptError { case error if error != LostLease => BeforeReplace(error) } *>
            session.atomicReplace(paths.temporary, deployment.targetPath)
        } else if (target.sha256.contains(deployment.desiredSha256)) IO.unit // replaced before a takeover
        else IO.raiseError(if (fresh) BeforeReplace(Failure(RemoteChanged)) else Failure(RemoteChanged))
      } *> advance(Replace, Activate)

      private def fresh: Boolean = deployment.phase.order < Replace.order

      private def activate: IO[Unit] = boundary(Activate) *>
        activation(ActivationFailed) *> advance(Activate, Verify)

      private def verify: IO[Unit] = boundary(Verify) *> (for {
        target <- readTarget
        _ <- IO.raiseUnless(target.sha256.contains(deployment.desiredSha256))(Failure(RemoteChanged))
        _ <- health(HealthCheckFailed)
        _ <- advance(Verify, Cleanup)
      } yield ())

      private def cleanup: IO[Unit] = for {
        _ <- guard
        _ <- removeQuietly(paths.temporary)
        // A rollout keeps the backup until it ends, so it can still roll every applied node back.
        retained = deployment.rolloutId.isDefined && deployment.expectedRemoteState != ExpectedRemoteState.Missing
        _ <- if (retained) IO.unit else removeQuietly(paths.backup)
        _ <- finish(ConfigurationDeploymentState.Succeeded, None, backupRetained = retained)
        _ <- logger.info(s"configuration.deployment.succeeded $context")
      } yield ()

      /** Puts the previous file back, but only over this deployment's own bytes: a file that someone
        * changed after the replace is never overwritten.
        */
      private def rollback: IO[Unit] = (for {
        _ <- guard
        target <- readTarget
        _ <- if (matchesExpected(target)) IO.unit
          else if (target.sha256.contains(deployment.desiredSha256)) restore
          else IO.raiseError(Failure(RollbackFailed))
        restored <- readTarget
        _ <- IO.raiseUnless(matchesExpected(restored))(Failure(RollbackFailed))
        origin <- IO(deployment.rollbackFromPhase)
        // The service is told again only if it may have been told about the new file.
        _ <- if (origin.forall(mayHaveActivated)) activation(RollbackFailed) *> health(RollbackFailed) else IO.unit
        _ <- removeQuietly(paths.temporary)
        _ <- removeQuietly(paths.backup)
      } yield ()).flatMap { _ =>
        finish(ConfigurationDeploymentState.RolledBack, None, backupRetained = false) *>
          logger.warn(s"configuration.deployment.rolled_back $context")
      }

      private def restore: IO[Unit] = deployment.expectedRemoteState match {
        case ExpectedRemoteState.Missing => guard *> session.remove(deployment.targetPath)
        case ExpectedRemoteState.Sha256(hash) => for {
          backup <- session.read(paths.backup, limit)
          _ <- IO.raiseUnless(backup.sha256.contains(hash))(Failure(RollbackFailed))
          _ <- guard
          _ <- session.atomicReplace(paths.backup, deployment.targetPath)
        } yield ()
      }

      private def activation(failure: String): IO[Unit] = unit match {
        case None => IO.unit
        case Some((verb, name)) =>
          guard *> session.execute(Systemctl, List(verb, name), settings.activationTimeout).attempt.flatMap {
            case Right(0) => IO.unit
            case Right(_) | Left(RemoteConfigurationFailure.CommandTimeout) => IO.raiseError(Failure(failure))
            case Left(other) => IO.raiseError(other)
          }
      }

      /** Polls `systemctl is-active` until it reports active or the health timeout passes. */
      private def health(failure: String): IO[Unit] = unit match {
        case None => IO.unit
        case Some((_, name)) =>
          def poll(deadline: Instant): IO[Unit] = guard *>
            session.execute(Systemctl, List("is-active", "--quiet", name), settings.healthTimeout).attempt.flatMap {
              case Right(0) => IO.unit
              case Left(other) if other != RemoteConfigurationFailure.CommandTimeout => IO.raiseError(other)
              case _ => clock.flatMap { now =>
                if (now.isBefore(deadline)) IO.sleep(settings.healthPollInterval) *> poll(deadline)
                else IO.raiseError(Failure(failure))
              }
            }
          clock.flatMap(now => poll(now.plusMillis(settings.healthTimeout.toMillis)))
      }

      private def unit: Option[(String, String)] = (deployment.policy.activation, deployment.policy.unitName) match {
        case (ConfigurationActivation.SystemdReload, Some(name)) => Some("reload" -> name)
        case (ConfigurationActivation.SystemdRestart, Some(name)) => Some("restart" -> name)
        case _ => None
      }

      /** Leaves an artifact that already has exactly the right bytes and metadata; replaces anything
        * else at that deployment-owned name (for example a partial write before a takeover).
        */
      private def ensureArtifact(path: String, bytes: Array[Byte], creation: RemoteFileCreation): IO[Unit] =
        guard *> session.read(path, limit).flatMap { present =>
          val sha = ConfigurationDeployment.sha256Bytes(bytes)
          if (present.sha256.contains(sha) && present.metadata.exists(matches(_, creation))) IO.unit
          else (if (present.exists) session.remove(path) else IO.unit) *> guard *> session.create(path, bytes, creation)
        }

      private def removeQuietly(path: String): IO[Unit] =
        session.remove(path).handleErrorWith(error =>
          logger.warn(s"configuration.deployment.artifact_cleanup.failed $context code=${codeOf(error)}"))

      private def readTarget: IO[RemoteConfigurationFile] = guard *> session.read(deployment.targetPath, limit)

      private def desiredContent: Array[Byte] =
        desired.getOrElse(throw new IllegalStateException("Desired content is not rendered in this phase"))

      /** Leaves nothing of this deployment on the server before it ends without a replace. */
      def removeArtifacts: IO[Unit] = removeQuietly(paths.temporary) *> removeQuietly(paths.backup)
    }

    private def matchesExpected(file: RemoteConfigurationFile): Boolean = deployment.expectedRemoteState match {
      case ExpectedRemoteState.Missing => !file.exists
      case ExpectedRemoteState.Sha256(hash) => file.sha256.contains(hash)
    }

    private def requireExpected(file: RemoteConfigurationFile): IO[Unit] =
      IO.raiseUnless(matchesExpected(file))(Failure(RemoteChanged))

    /** A new file takes the target's exact mode and owner; setuid, setgid and sticky are refused. */
    private def creationFor(target: RemoteConfigurationFile): Either[Throwable, RemoteFileCreation] =
      target.metadata match {
        case Some(metadata) if metadata.permissions > ConfigurationExecutionPolicy.MaxFileMode =>
          Left(RemoteConfigurationFailure.MetadataMismatch)
        case Some(metadata) => Right(RemoteFileCreation(metadata.permissions, Some(metadata.uid -> metadata.gid)))
        case None if target.exists => Left(RemoteConfigurationFailure.MetadataMismatch)
        case None => Right(RemoteFileCreation(deployment.policy.newFileMode, None))
      }

    private def matches(metadata: RemoteFileMetadata, creation: RemoteFileCreation): Boolean =
      metadata.permissions == creation.permissions &&
        creation.owner.forall { case (uid, gid) => metadata.uid == uid && metadata.gid == gid }

    private def connectionOrFail: IO[Connection] =
      runner.run(sources.resolve(deployment.organizationId, deployment.resourceId, deployment.connectionId)).flatMap {
        case ConfigurationDeploymentSource.Ready(connection) if connection.updatedAt == deployment.connectionUpdatedAt =>
          IO.pure(connection)
        case _ => IO.raiseError(Failure(ConnectionChanged))
      }

    /** Renders the snapshot again and refuses to continue unless it is byte for byte what was approved. */
    private def desiredBytes: IO[Array[Byte]] = for {
      _ <- if (deployment.phase == Precheck) assignmentUnchanged else IO.unit
      revision <- runner.run(profiles.findRevision(deployment.organizationId, deployment.profileId,
        deployment.profileRevisionNumber)).flatMap(IO.fromOption(_)(Failure(AssignmentChanged)))
      rendered <- IO.fromEither(ConfigurationDesiredState.render(revision.revision, deployment.values)
        .leftMap(_ => Failure(DesiredMismatch)))
      bytes = rendered.getBytes(StandardCharsets.UTF_8)
      _ <- IO.raiseUnless(bytes.length <= limit)(Failure(RenderedTooLarge))
      _ <- if (ConfigurationDeployment.sha256Bytes(bytes) == deployment.desiredSha256) IO.unit
        else logger.error(s"configuration.deployment.desired_mismatch $context") *> IO.raiseError(Failure(DesiredMismatch))
    } yield bytes

    /** The assignment must still say exactly what the approved snapshot says. */
    private def assignmentUnchanged: IO[Unit] =
      if (deployment.rolloutId.isDefined && deployment.phase == Rollback) IO.unit
      else runner.run(assignments.find(deployment.organizationId, deployment.assignmentId)).flatMap { current =>
        IO.raiseUnless(current.exists(a => a.active && a.version == deployment.assignmentVersion &&
          a.resourceId == deployment.resourceId && a.profileId == deployment.profileId &&
          a.profileRevisionNumber == deployment.profileRevisionNumber && a.targetPath == deployment.targetPath))(
          Failure(AssignmentChanged))
      }

    /** Between phases: hand the lease back on shutdown, honour a cancel before the replace, and stop
      * a deployment that has run past its deadline.
      */
    private def boundary(phase: ConfigurationDeploymentPhase): IO[Unit] = for {
      _ <- guard
      stop <- stopping
      _ <- if (stop) clock.flatMap(now => runner.run(deployments.release(deployment.organizationId, deployment.id,
        token, now))) *> IO.raiseError(Stopped) else IO.unit
      _ <- if (phase.order <= Replace.order) runner.run(deployments.find(deployment.organizationId, deployment.id))
        .flatMap(row => IO.raiseWhen(row.exists(_.cancelRequested))(CancelRequested))
        else IO.unit
      now <- clock
      started = deployment.startedAt.getOrElse(now)
      _ <- IO.raiseWhen(phase.order <= Verify.order &&
        now.isAfter(started.plusMillis(settings.overallTimeout.toMillis)))(Failure(TimedOut))
    } yield ()

    private def onError(phase: ConfigurationDeploymentPhase, error: Throwable): IO[Unit] = error match {
      case LostLease | Stopped => IO.raiseError(error)
      case BeforeReplace(cause) => onError(Validate, cause)
      case CancelRequested => cleanUpBeforeEnd *> finish(ConfigurationDeploymentState.Cancelled, None, false) *>
        logger.info(s"configuration.deployment.cancelled $context")
      case failure: RemoteConfigurationFailure if failure.transient => transient(phase, failure)
      case other =>
        val code = codeOf(other)
        val connectionChanged = other == Failure(ConnectionChanged)
        val log = other match {
          case _: Failure | _: RemoteConfigurationFailure => IO.unit
          case unexpected => logger.error(unexpected)(s"configuration.deployment.internal_error $context phase=${phase.code}")
        }
        log *> {
          if (phase == Rollback)
            finish(ConfigurationDeploymentState.RollbackFailed, None, false) *>
              logger.error(s"configuration.deployment.rollback_failed $context code=$code")
          else if (phase.order < Replace.order)
            // Nothing exists on the server before UPLOAD, and a changed connection may not be used.
            (if (connectionChanged || phase == Precheck) IO.unit else cleanUpBeforeEnd) *>
              finish(ConfigurationDeploymentState.Failed, Some(code), false) *>
              logger.warn(s"configuration.deployment.failed $context phase=${phase.code} code=$code")
          else if (connectionChanged)
            // The server can no longer be reached under the approved identity: nothing restores it.
            finish(ConfigurationDeploymentState.RollbackFailed, Some(code), false) *>
              logger.error(s"configuration.deployment.rollback_failed $context code=$code")
          else startRollback(phase, code)
        }
    }

    /** From REPLACE on the target may hold the new file: the only way out is a durable rollback. */
    private def startRollback(phase: ConfigurationDeploymentPhase, code: String): IO[Unit] = for {
      now <- clock
      entered <- runner.run(deployments.enterRollback(deployment.organizationId, deployment.id, token, phase, code, now))
      _ <- IO.raiseUnless(entered)(LostLease)
      _ <- logger.warn(s"configuration.deployment.rollback_started $context phase=${phase.code} code=$code")
      reloaded <- runner.run(deployments.find(deployment.organizationId, deployment.id))
        .flatMap(IO.fromOption(_)(LostLease))
      _ <- new Execution(reloaded, token, context, current, attempts, stopping).resumeRollback
    } yield ()

    def resumeRollback: IO[Unit] = current.set(Rollback) *> run

    private def transient(phase: ConfigurationDeploymentPhase, failure: RemoteConfigurationFailure): IO[Unit] =
      attempts.get.flatMap { count =>
        if (count < settings.maxTransientAttempts) for {
          now <- clock
          postponed <- runner.run(deployments.postpone(deployment.organizationId, deployment.id, token, now,
            now.plusMillis(settings.transientBackoff.toMillis * (count + 1))))
          _ <- IO.raiseUnless(postponed)(LostLease)
          _ <- logger.warn(s"configuration.deployment.retry_scheduled $context phase=${phase.code} " +
            s"code=${failure.code} attempt=${count + 1}")
        } yield ()
        else if (phase.order < Replace.order)
          finish(ConfigurationDeploymentState.Failed, Some(failure.code), false) *>
            logger.warn(s"configuration.deployment.failed $context phase=${phase.code} code=${failure.code}")
        else
          // The target may hold the new file and the server cannot be reached to restore it.
          finish(ConfigurationDeploymentState.RollbackFailed, Some(failure.code), false) *>
            logger.error(s"configuration.deployment.rollback_failed $context phase=${phase.code} code=${failure.code}")
      }

    /** Best effort: the artifacts are deployment-owned names next to the target, never the target. */
    private def cleanUpBeforeEnd: IO[Unit] =
      connectionOrFail.flatMap(connection => remote.withSession(connection)(session =>
        new Steps(session, None).removeArtifacts)).handleErrorWith(error =>
        logger.warn(s"configuration.deployment.artifact_cleanup.failed $context code=${codeOf(error)}"))

    private def guard: IO[Unit] = renew(deployment, token)

    private def advance(expected: ConfigurationDeploymentPhase, next: ConfigurationDeploymentPhase): IO[Unit] = for {
      now <- clock
      changed <- runner.run(deployments.advance(deployment.organizationId, deployment.id, token, expected, next, now))
      _ <- IO.raiseUnless(changed)(LostLease)
      _ <- current.set(next) *> attempts.set(0)
    } yield ()

    private def finish(state: ConfigurationDeploymentState, code: Option[String], backupRetained: Boolean): IO[Unit] =
      for {
        now <- clock
        done <- runner.run(deployments.finish(deployment.organizationId, deployment.id, token, state, code,
          backupRetained, now))
        _ <- IO.raiseUnless(done)(LostLease)
      } yield ()
  }

  private def renew(deployment: ConfigurationDeployment, token: UUID): IO[Unit] = for {
    now <- clock
    renewed <- runner.run(deployments.renew(deployment.organizationId, deployment.id, token,
      now, now.plusMillis(settings.leaseDuration.toMillis)))
    _ <- IO.raiseUnless(renewed)(LostLease)
  } yield ()
}

object ConfigurationDeploymentWorker {
  /** Resolved through the remote user's PATH: `/bin` and `/usr/bin` differ between distributions. */
  val Systemctl = "systemctl"

  val AssignmentChanged = "CONFIGURATION_ASSIGNMENT_CHANGED"
  val ConnectionChanged = "CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED"
  val RemoteChanged = "CONFIGURATION_REMOTE_CHANGED"
  val ValidationFailed = "CONFIGURATION_VALIDATION_FAILED"
  val UploadFailed = "CONFIGURATION_UPLOAD_FAILED"
  val ReplaceFailed = "CONFIGURATION_REPLACE_FAILED"
  val ActivationFailed = "CONFIGURATION_ACTIVATION_FAILED"
  val HealthCheckFailed = "CONFIGURATION_HEALTH_CHECK_FAILED"
  val RollbackFailed = "CONFIGURATION_ROLLBACK_FAILED"
  val TimedOut = "CONFIGURATION_DEPLOYMENT_TIMEOUT"
  val RenderedTooLarge = "CONFIGURATION_RENDERED_TOO_LARGE"
  val DesiredMismatch = "CONFIGURATION_DESIRED_STATE_MISMATCH"
  val InternalError = "CONFIGURATION_INTERNAL_ERROR"

  private[configuration] final case class Failure(code: String) extends RuntimeException(code) with NoStackTrace
  /** A failure raised before the rename in REPLACE: the target was not touched. */
  private final case class BeforeReplace(cause: Throwable) extends RuntimeException(cause) with NoStackTrace
  private case object LostLease extends RuntimeException("Deployment lease lost") with NoStackTrace
  private case object Stopped extends RuntimeException("Worker stopping") with NoStackTrace
  private case object CancelRequested extends RuntimeException("Cancel requested") with NoStackTrace

  def codeOf(error: Throwable): String = error match {
    case Failure(code) => code
    case failure: RemoteConfigurationFailure => failure.code
    case BeforeReplace(cause) => codeOf(cause)
    case _ => InternalError
  }
}
