package ru.bitec.app.ops
package application.configuration

import application.port._
import cats.effect.{IO, Resource}
import cats.syntax.all._
import domain.configuration._
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** One lease owns one deployment. Every durable transition is fenced; SSH work never holds a DB transaction. */
final class ConfigurationDeploymentWorker[Tx[_]](
  deployments: ConfigurationDeploymentRepository[Tx],
  assignments: ConfigurationAssignmentRepository[Tx],
  assignmentQuery: ConfigurationAssignmentQuery[Tx],
  profiles: ConfigurationProfileQuery[Tx],
  sources: ConfigurationDeploymentSourceQuery[Tx],
  remote: RemoteConfigurationTransport[IO],
  runner: TransactionRunner[IO, Tx],
  owner: UUID,
  leaseDuration: FiniteDuration = 2.minutes
) {
  import ConfigurationDeploymentPhase._
  import ConfigurationDeploymentState._

  private case object LostLease extends RuntimeException("Deployment lease lost")
  private final case class DeploymentFailure(code: String) extends RuntimeException(code)
  private val maxRemoteBytes = ConfigurationTemplateRenderer.MaxRenderedLength

  def tick: IO[Unit] = for {
    token <- IO(UUID.randomUUID())
    now <- IO(Instant.now())
    claimed <- runner.run(deployments.claim(owner, token, now, now.plusMillis(leaseDuration.toMillis), 1))
    _ <- claimed.traverse_(process)
  } yield ()

  def run: IO[Nothing] = (tick.handleErrorWith(_ => IO.unit) *> IO.sleep(2.seconds)).foreverM

  private def process(initial: ConfigurationDeployment): IO[Unit] = {
    val token = initial.leaseToken.getOrElse(throw new IllegalStateException("Claim without token"))
    val heartbeat = (IO.sleep(30.seconds) *> renew(initial, token)).foreverM
    Resource.make(heartbeat.start)(_.cancel).use { _ =>
      execute(initial, token).handleErrorWith {
        case LostLease => IO.unit
        case failure: DeploymentFailure => finish(initial, token,
          if (mayHaveReplaced(initial.phase)) RollbackFailed else Failed, Some(failure.code))
        case _: integration.ssh.RemoteFileTooLarge =>
          finish(initial, token, if (mayHaveReplaced(initial.phase)) RollbackFailed else Failed,
            Some("CONFIGURATION_REMOTE_FILE_TOO_LARGE"))
        case _: integration.ssh.SshTransportFailure.HostKeyMismatch =>
          finish(initial, token, if (mayHaveReplaced(initial.phase)) RollbackFailed else Failed,
            Some("CONFIGURATION_HOST_KEY_MISMATCH"))
        case _ => finish(initial, token, if (mayHaveReplaced(initial.phase)) RollbackFailed else Failed,
          Some("CONFIGURATION_SSH_UNAVAILABLE"))
      }
    }
  }

  private def execute(initial: ConfigurationDeployment, token: UUID): IO[Unit] =
    if (initial.phase == Rollback) for {
      _ <- guard(initial, token)
      source <- runner.run(sources.resolve(initial.organizationId, initial.resourceId, initial.connectionId))
      connection <- source match {
        case ConfigurationDeploymentSource.Ready(value) if value.updatedAt == initial.connectionUpdatedAt => IO.pure(value)
        case _ => IO.raiseError[domain.connection.Connection](DeploymentFailure("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED"))
      }
      _ <- remote.withSession(connection)(session => phases(initial, token, session, ""))
    } yield ()
    else deploy(initial, token)

  private def deploy(initial: ConfigurationDeployment, token: UUID): IO[Unit] = for {
    _ <- guard(initial, token)
    current <- runner.run(assignments.find(initial.organizationId, initial.assignmentId))
    _ <- IO.raiseUnless(current.exists(a => a.active && a.version == initial.assignmentVersion &&
      a.resourceId == initial.resourceId && a.profileId == initial.profileId &&
      a.profileRevisionNumber == initial.profileRevisionNumber && a.targetPath == initial.targetPath))(
      DeploymentFailure("CONFIGURATION_ASSIGNMENT_CHANGED"))
    source <- runner.run(sources.resolve(initial.organizationId, initial.resourceId, initial.connectionId))
    connection <- source match {
      case ConfigurationDeploymentSource.Ready(value) if value.updatedAt == initial.connectionUpdatedAt => IO.pure(value)
      case _ => IO.raiseError[domain.connection.Connection](DeploymentFailure("CONFIGURATION_DEPLOYMENT_CONNECTION_CHANGED"))
    }
    revision <- runner.run(profiles.findRevision(initial.organizationId, initial.profileId,
      initial.profileRevisionNumber)).flatMap(IO.fromOption(_)(DeploymentFailure("CONFIGURATION_ASSIGNMENT_CHANGED")))
    desired <- IO.fromEither(ConfigurationDesiredState.render(revision.revision, initial.values)
      .leftMap(_ => DeploymentFailure("CONFIGURATION_DESIRED_HASH_MISMATCH")))
    _ <- IO.raiseUnless(ConfigurationDeployment.sha256(desired) == initial.desiredSha256)(
      DeploymentFailure("CONFIGURATION_DESIRED_HASH_MISMATCH"))
    _ <- remote.withSession(connection) { session => phases(initial, token, session, desired) }
  } yield ()

  private def phases(initial: ConfigurationDeployment, token: UUID,
                     remote: RemoteConfigurationSession[IO], desired: String): IO[Unit] = {
    val (temp, backup) = ConfigurationDeployment.artifactPaths(initial.targetPath, initial.id)
    val bytes = desired.getBytes(java.nio.charset.StandardCharsets.UTF_8)

    def readTarget: IO[RemoteConfigurationFile] =
      guard(initial, token) *> remote.read(initial.targetPath, maxRemoteBytes)

    def expected(file: RemoteConfigurationFile): Boolean = initial.expectedRemoteState match {
      case ExpectedRemoteState.Missing => !file.exists
      case ExpectedRemoteState.Sha256(hash) => file.exists && ConfigurationDeployment.sha256Bytes(file.bytes) == hash
    }

    def ensureArtifact(path: String, content: Array[Byte], mode: Int,
                       uid: Option[Int], gid: Option[Int]): IO[Unit] =
      guard(initial, token) *> remote.read(path, maxRemoteBytes).flatMap { present =>
        if (present.exists) IO.raiseUnless(ConfigurationDeployment.sha256Bytes(present.bytes) ==
          ConfigurationDeployment.sha256Bytes(content))(DeploymentFailure("CONFIGURATION_REMOTE_CHANGED"))
        else remote.upload(path, content, mode, uid, gid)
      }

    def activate: IO[Unit] = initial.policy.activation match {
      case ConfigurationActivation.None => IO.unit
      case action =>
        val verb = if (action == ConfigurationActivation.SystemdReload) "reload" else "restart"
        val unit = initial.policy.unitName.getOrElse(throw DeploymentFailure("CONFIGURATION_ACTIVATION_FAILED"))
        guard(initial, token) *> remote.execute("/usr/bin/systemctl", List(verb, unit), 60).flatMap { status =>
          IO.raiseUnless(status == 0)(DeploymentFailure("CONFIGURATION_ACTIVATION_FAILED"))
        }
    }

    def verifyService: IO[Unit] = initial.policy.activation match {
          case ConfigurationActivation.None => IO.unit
          case _ =>
            val unit = initial.policy.unitName.getOrElse(throw DeploymentFailure("CONFIGURATION_HEALTH_CHECK_FAILED"))
            guard(initial, token) *> remote.execute("/usr/bin/systemctl", List("is-active", unit), 30)
              .flatMap(status => IO.raiseUnless(status == 0)(DeploymentFailure("CONFIGURATION_HEALTH_CHECK_FAILED")))
    }

    def verify: IO[Unit] = readTarget.flatMap { target =>
      IO.raiseUnless(target.exists && ConfigurationDeployment.sha256Bytes(target.bytes) == initial.desiredSha256)(
        DeploymentFailure("CONFIGURATION_REMOTE_CHANGED")) *> verifyService
    }

    def rollback: IO[Unit] = for {
      target <- readTarget
      _ <- if (expected(target)) IO.unit
        else {
          IO.raiseUnless(target.exists && ConfigurationDeployment.sha256Bytes(target.bytes) == initial.desiredSha256)(
            DeploymentFailure("CONFIGURATION_ROLLBACK_FAILED")) *>
            (initial.expectedRemoteState match {
              case ExpectedRemoteState.Missing => guard(initial, token) *> remote.remove(initial.targetPath)
              case ExpectedRemoteState.Sha256(hash) =>
                remote.read(backup, maxRemoteBytes).flatMap { previous =>
                  IO.raiseUnless(previous.exists && ConfigurationDeployment.sha256Bytes(previous.bytes) == hash)(
                    DeploymentFailure("CONFIGURATION_ROLLBACK_FAILED")) *>
                    guard(initial, token) *> remote.atomicReplace(backup, initial.targetPath)
                }
            })
        }
      restored <- readTarget
      _ <- IO.raiseUnless(expected(restored))(DeploymentFailure("CONFIGURATION_ROLLBACK_FAILED"))
      _ <- activate *> verifyService
    } yield ()

    def failAfterReplace(error: Throwable): IO[Unit] =
      rollback.attempt.flatMap {
        case Right(_) => finish(initial, token, RolledBack, Some(failureCode(error)))
        case Left(LostLease) => IO.raiseError(LostLease)
        case Left(_) => finish(initial, token, RollbackFailed, Some("CONFIGURATION_ROLLBACK_FAILED"))
      }

    def recover(error: Throwable): IO[Unit] = error match {
      case LostLease => IO.raiseError(LostLease)
      case _ => runner.run(deployments.find(initial.organizationId, initial.id)).flatMap {
        case Some(current) if mayHaveReplaced(current.phase) =>
          readTarget.attempt.flatMap {
            case Right(target) if target.exists &&
              ConfigurationDeployment.sha256Bytes(target.bytes) == initial.desiredSha256 => failAfterReplace(error)
            case Right(target) if expected(target) =>
              if (current.phase == Rollback) finish(initial, token, RolledBack, None)
              else finish(initial, token, Failed, Some(failureCode(error)))
            case Left(LostLease) => IO.raiseError(LostLease)
            case _ => finish(initial, token, RollbackFailed, Some("CONFIGURATION_ROLLBACK_FAILED"))
          }
        case Some(_) =>
          remote.remove(temp).attempt.void *> remote.remove(backup).attempt.void *>
            finish(initial, token, Failed, Some(failureCode(error)))
        case None => IO.raiseError(LostLease)
      }
    }

    def cancellationRequested: IO[Boolean] =
      runner.run(deployments.find(initial.organizationId, initial.id)).map(_.exists(_.cancelRequested))

    def cancelBeforeReplace: IO[Unit] =
      guard(initial, token) *> remote.remove(temp).attempt.void *>
        remote.remove(backup).attempt.void *> finish(initial, token, Cancelled, None)

    def step(phase: ConfigurationDeploymentPhase): IO[Unit] = phase match {
      case Precheck =>
        readTarget.flatMap { file =>
          IO.raiseUnless(expected(file))(DeploymentFailure("CONFIGURATION_REMOTE_CHANGED")) *>
            advance(initial, token, Precheck, Upload) *> continue(Upload)
        }
      case Upload =>
        readTarget.flatMap { file =>
          IO.raiseUnless(expected(file))(DeploymentFailure("CONFIGURATION_REMOTE_CHANGED")) *>
            ensureArtifact(temp, bytes, file.mode.getOrElse(initial.policy.newFileMode), file.uid, file.gid) *>
            advance(initial, token, Upload, Validate) *> continue(Validate)
        }
      case Validate =>
        readTarget.flatMap { file =>
          IO.raiseUnless(expected(file))(DeploymentFailure("CONFIGURATION_REMOTE_CHANGED")) *>
            initial.policy.validator.traverse_ { validator =>
              val args = validator.args.map(_.replace("{candidate}", temp).replace("{target}", initial.targetPath))
              guard(initial, token) *> remote.execute(validator.executable, args, 60).flatMap { status =>
                IO.raiseUnless(status == 0)(DeploymentFailure("CONFIGURATION_VALIDATION_FAILED"))
              }
            } *>
            (if (file.exists) ensureArtifact(backup, file.bytes, file.mode.getOrElse(initial.policy.newFileMode),
              file.uid, file.gid) else IO.unit) *>
            advance(initial, token, Validate, Replace) *> continue(Replace)
        }
      case Replace =>
        readTarget.flatMap { file =>
          if (file.exists && ConfigurationDeployment.sha256Bytes(file.bytes) == initial.desiredSha256)
            advance(initial, token, Replace, Activate) *> continue(Activate)
          else IO.raiseUnless(expected(file))(DeploymentFailure("CONFIGURATION_REMOTE_CHANGED")) *>
            cancellationRequested.flatMap {
              case true => cancelBeforeReplace
              case false => remote.read(temp, maxRemoteBytes).flatMap { candidate =>
                IO.raiseUnless(candidate.exists && ConfigurationDeployment.sha256Bytes(candidate.bytes) == initial.desiredSha256)(
                  DeploymentFailure("CONFIGURATION_UPLOAD_FAILED")) *>
                  guard(initial, token) *> remote.atomicReplace(temp, initial.targetPath) *>
                  advance(initial, token, Replace, Activate) *> continue(Activate)
              }
            }
        }
      case Activate => activate *> advance(initial, token, Activate, Verify) *> continue(Verify)
      case Verify => verify *> advance(initial, token, Verify, Cleanup) *> continue(Cleanup)
      case Cleanup =>
        guard(initial, token) *>
          remote.remove(temp).attempt.void *>
          (if (initial.rolloutId.isEmpty) remote.remove(backup).attempt.void else IO.unit) *>
          finish(initial, token, Succeeded, None)
      case Rollback => rollback.attempt.flatMap {
        case Right(_) =>
          remote.remove(temp).attempt.void *> remote.remove(backup).attempt.void *>
            finish(initial, token, RolledBack, None)
        case Left(LostLease) => IO.raiseError(LostLease)
        case Left(_) => finish(initial, token, RollbackFailed, Some("CONFIGURATION_ROLLBACK_FAILED"))
      }
    }

    def continue(phase: ConfigurationDeploymentPhase): IO[Unit] = phase match {
      case Precheck | Upload | Validate => cancellationRequested.flatMap {
        case true => cancelBeforeReplace
        case false => step(phase)
      }
      case _ => step(phase)
    }

    continue(initial.phase).handleErrorWith(recover)
  }

  private def mayHaveReplaced(phase: ConfigurationDeploymentPhase): Boolean = phase match {
    case Replace | Activate | Verify | Cleanup | Rollback => true
    case _ => false
  }

  private def guard(deployment: ConfigurationDeployment, token: UUID): IO[Unit] =
    renew(deployment, token)

  private def renew(deployment: ConfigurationDeployment, token: UUID): IO[Unit] = for {
    now <- IO(Instant.now())
    renewed <- runner.run(deployments.renew(deployment.organizationId, deployment.id, token,
      now, now.plusMillis(leaseDuration.toMillis)))
    _ <- IO.raiseUnless(renewed)(LostLease)
  } yield ()

  private def advance(deployment: ConfigurationDeployment, token: UUID,
                      expected: ConfigurationDeploymentPhase, next: ConfigurationDeploymentPhase): IO[Unit] = for {
    now <- IO(Instant.now())
    changed <- runner.run(deployments.advance(deployment.organizationId, deployment.id, token, expected, next, now))
    _ <- IO.raiseUnless(changed)(LostLease)
  } yield ()

  private def finish(deployment: ConfigurationDeployment, token: UUID,
                     state: ConfigurationDeploymentState, code: Option[String]): IO[Unit] = for {
    now <- IO(Instant.now())
    _ <- runner.run(deployments.finish(deployment.organizationId, deployment.id, token, state, code, now))
  } yield ()

  private def failureCode(error: Throwable): String = error match {
    case DeploymentFailure(code) => code
    case _: integration.ssh.RemoteFileTooLarge => "CONFIGURATION_REMOTE_FILE_TOO_LARGE"
    case _: integration.ssh.SshTransportFailure.HostKeyMismatch => "CONFIGURATION_HOST_KEY_MISMATCH"
    case _ => "CONFIGURATION_SSH_UNAVAILABLE"
  }
}
