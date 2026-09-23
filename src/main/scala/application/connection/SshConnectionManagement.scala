package ru.bitec.app.ops
package application.connection

import application.port.{ConnectionRepository, ConnectionScheduleRepository, ConnectionSecretCryptography, ConnectionSecretRepository, EnvironmentRepository, ProjectRepository, SshConnectionProbe, SshPasswordResolver, SshProbeError, TransactionRunner}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.connection.{Connection, ConnectionSchedule, ConnectionScope, SecretRef, SshConnectionSettings}

import java.time.Instant
import java.util.UUID

final case class ConnectionManagementError(code: String, override val getMessage: String)
  extends RuntimeException(getMessage)

final class SshConnectionManagement[Tx[_]: MonadThrow](
  connections: ConnectionRepository[Tx],
  schedules: ConnectionScheduleRepository[Tx],
  secrets: ConnectionSecretRepository[Tx],
  projects: ProjectRepository[Tx],
  environments: EnvironmentRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  probe: SshConnectionProbe[IO],
  credentials: SshPasswordResolver[IO],
  cipher: ConnectionSecretCryptography
) {
  def test(request: TestSshConnectionCommand): IO[String] =
    for {
      config <- IO.fromEither(validateSsh(request.ssh))
      password <- IO.fromEither(validatePassword(request.password))
      fingerprint <- probeConnection(config, password)
    } yield fingerprint

  def create(orgId: UUID, request: CreateSshConnectionCommand): IO[ConnectionOverview] =
    for {
      _ <- IO.fromEither(validateRequest(request.code, request.name, request.schedule))
      ssh <- IO.fromEither(validateSsh(request.ssh))
      password <- IO.fromEither(validatePassword(request.password))
      _ <- runner.run(validateScope(orgId, request.scope))
      fingerprint <- probeConnection(ssh, password)
      now <- IO(Instant.now())
      id <- IO(UUID.randomUUID())
      secretId <- IO(UUID.randomUUID())
      secret <- IO(cipher.encrypt(secretId, orgId, password))
      connection = Connection(id, orgId, request.scope, "SSH", request.code.trim, request.name.trim,
        SshConnectionSettings.toConnectionConfig(ssh.copy(hostKeyFingerprint = Some(fingerprint))),
        Some(s"db:$secretId"), true, now, now)
      schedule = ConnectionSchedule(orgId, id, request.schedule.enabled, request.schedule.intervalSeconds,
        now.plusSeconds(request.schedule.intervalSeconds), 0L)
      _ <- runner.run(for {
        existing <- connections.findByOrganization(orgId)
        _ <- if (existing.exists(_.code.equalsIgnoreCase(connection.code)))
          MonadThrow[Tx].raiseError[Unit](ConnectionManagementError("CONNECTION_CODE_ALREADY_EXISTS", "Connection code already exists"))
        else MonadThrow[Tx].unit
        _ <- secrets.save(secret)
        _ <- connections.save(connection)
        _ <- schedules.save(schedule)
      } yield ())
    } yield ConnectionOverview(connection, Some(schedule), None)

  def update(orgId: UUID, id: UUID, request: UpdateSshConnectionCommand): IO[ConnectionOverview] =
    for {
      _ <- IO.fromEither(validateRequest(request.code, request.name, request.schedule))
      submittedSsh <- IO.fromEither(validateSsh(request.ssh))
      newPassword <- request.password.traverse(value => IO.fromEither(validatePassword(value)))
      old <- runner.run(for {
        found <- connections.findById(orgId, id)
        _ <- validateScope(orgId, request.scope)
        schedule <- schedules.findByConnection(orgId, id)
      } yield (found, schedule))
      connection <- IO.fromOption(old._1)(ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
      _ <- if (connection.connectorType != "SSH" || !connection.isActive)
        IO.raiseError[Unit](ConnectionManagementError("INVALID_REQUEST", "Connection is not editable")) else IO.unit
      oldSsh <- IO.fromEither(SshConnectionSettings.from(connection.config))
      endpointChanged = oldSsh.host != submittedSsh.host || oldSsh.port != submittedSsh.port
      requiresProbe = endpointChanged || oldSsh.username != submittedSsh.username || newPassword.nonEmpty
      effectiveSsh = submittedSsh.copy(hostKeyFingerprint = if (endpointChanged) None else oldSsh.hostKeyFingerprint)
      fingerprint <- if (requiresProbe)
        (newPassword match {
          case Some(password) => IO.pure(password)
          case None => credentials.resolvePassword(connection)
        }).flatMap(password => probeConnection(effectiveSsh, password))
      else IO.pure(oldSsh.hostKeyFingerprint.getOrElse(""))
      now <- IO(Instant.now())
      newSecretId <- if (newPassword.nonEmpty) IO(Some(UUID.randomUUID())) else IO.pure(None)
      newSecret <- IO(newSecretId.map(secretId => cipher.encrypt(secretId, orgId, newPassword.get)))
      next = connection.copy(scope = request.scope, code = request.code.trim, name = request.name.trim,
        config = SshConnectionSettings.toConnectionConfig(effectiveSsh.copy(
          hostKeyFingerprint = if (fingerprint.nonEmpty) Some(fingerprint) else None)),
        secretRef = newSecretId.map(secretId => s"db:$secretId").orElse(connection.secretRef), updatedAt = now)
      scheduleChanged = !old._2.exists(previous =>
        previous.enabled == request.schedule.enabled && previous.intervalSeconds == request.schedule.intervalSeconds)
      schedule = ConnectionSchedule(orgId, id, request.schedule.enabled, request.schedule.intervalSeconds,
        if (request.schedule.enabled && scheduleChanged) now.plusSeconds(request.schedule.intervalSeconds)
        else old._2.map(_.nextRunAt).getOrElse(now.plusSeconds(request.schedule.intervalSeconds)),
        old._2.map(_.consecutiveFailures).getOrElse(0L))
      _ <- runner.run(for {
        current <- connections.findById(orgId, id)
        _ <- if (current.isEmpty) MonadThrow[Tx].raiseError[Unit](ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
          else MonadThrow[Tx].unit
        existing <- connections.findByOrganization(orgId)
        _ <- if (existing.exists(c => c.id != id && c.code.equalsIgnoreCase(next.code)))
          MonadThrow[Tx].raiseError[Unit](ConnectionManagementError("CONNECTION_CODE_ALREADY_EXISTS", "Connection code already exists"))
        else MonadThrow[Tx].unit
        _ <- newSecret.fold(MonadThrow[Tx].unit)(secrets.save)
        _ <- connections.save(next)
        _ <- if (scheduleChanged) schedules.save(schedule) else MonadThrow[Tx].unit
        _ <- if (newSecret.nonEmpty) connection.secretRef.flatMap(raw => SecretRef.parse(raw).toOption) match {
          case Some(SecretRef.Database(oldSecretId)) => secrets.delete(orgId, oldSecretId)
          case _ => MonadThrow[Tx].unit
        } else MonadThrow[Tx].unit
      } yield ())
    } yield ConnectionOverview(next, Some(schedule), None)

  def deactivate(orgId: UUID, id: UUID): IO[Unit] =
    runner.run(for {
      found <- connections.findById(orgId, id)
      connection <- found.liftTo[Tx](ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
      now <- MonadThrow[Tx].pure(Instant.now())
      _ <- if (connection.isActive) connections.save(connection.copy(isActive = false, updatedAt = now))
        else MonadThrow[Tx].unit
      schedule <- schedules.findByConnection(orgId, id)
      _ <- schedule.fold(MonadThrow[Tx].unit)(s => schedules.save(s.copy(enabled = false)))
    } yield ())

  private def validateScope(orgId: UUID, scope: ConnectionScope): Tx[Unit] = scope match {
    case ConnectionScope.Organization => MonadThrow[Tx].unit
    case ConnectionScope.Project(projectId) =>
      projects.findActiveById(orgId, projectId).flatMap {
        case Some(_) => MonadThrow[Tx].unit
        case None => MonadThrow[Tx].raiseError(ConnectionManagementError("PROJECT_NOT_FOUND", "Project was not found"))
      }
    case ConnectionScope.Environment(projectId, environmentId) =>
      validateScope(orgId, ConnectionScope.Project(projectId)) >>
        environments.findActiveByProject(orgId, projectId).flatMap { values =>
          if (values.exists(_.id == environmentId)) MonadThrow[Tx].unit
          else MonadThrow[Tx].raiseError(ConnectionManagementError("ENVIRONMENT_NOT_FOUND", "Environment was not found"))
        }
  }

  private def validateRequest(code: String, name: String, schedule: SshScheduleCommand):
    Either[ConnectionManagementError, Unit] =
    for {
      _ <- check(code.trim.nonEmpty && code.trim.length <= 64, "INVALID_REQUEST", "Invalid connection code")
      _ <- check(name.trim.nonEmpty && name.trim.length <= 255, "INVALID_REQUEST", "Invalid connection name")
      _ <- check(schedule.intervalSeconds >= 300, "INVALID_REQUEST", "SSH sync interval must be at least 300 seconds")
    } yield ()

  private def validateSsh(value: SshConnectionSettings): Either[ConnectionManagementError, SshConnectionSettings] =
    for {
      _ <- check(value.host.trim.nonEmpty,
        "INVALID_REQUEST", "Invalid SSH host")
      _ <- check(value.username.trim.nonEmpty && value.username.trim.length <= 255, "INVALID_REQUEST", "Invalid SSH username")
      _ <- check(value.port > 0 && value.port <= 65535, "INVALID_REQUEST", "Invalid SSH port")
    } yield value.copy(host = value.host.trim, username = value.username.trim)

  private def validatePassword(password: String): Either[ConnectionManagementError, String] =
    check(password.nonEmpty, "INVALID_REQUEST", "SSH password is required").map(_ => password)

  private def check(valid: Boolean, code: String, message: String): Either[ConnectionManagementError, Unit] =
    if (valid) Right(()) else Left(ConnectionManagementError(code, message))

  private def probeConnection(config: SshConnectionSettings, password: String): IO[String] =
    probe.probe(config, password).handleErrorWith {
      case SshProbeError.HostKeyMismatch =>
        IO.raiseError(ConnectionManagementError("SSH_HOST_KEY_MISMATCH", "SSH host key has changed"))
      case _ => IO.raiseError(ConnectionManagementError("SSH_CONNECTION_FAILED", "Unable to connect using these SSH settings"))
    }
}
