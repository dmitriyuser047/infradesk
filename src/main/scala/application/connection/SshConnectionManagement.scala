package ru.bitec.app.ops
package application.connection

import application.port.{ConnectionRepository, ConnectionScheduleRepository, ConnectionSecretRepository, EnvironmentRepository, ProjectRepository, TransactionRunner}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.connection.{Connection, ConnectionSchedule, ConnectionScope}
import infrastructure.http.dto.{ConnectionScopeRequest, SaveSshConnectionRequest, SshSettingsRequest, TestSshConnectionRequest}
import integration.ssh.{ConnectionSecretCipher, SecretRef, SshAuthentication, SshAuthenticationProvider, SshClient, SshConnectionConfig, SshHostKeyMismatch}

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
  client: SshClient[IO],
  credentials: SshAuthenticationProvider[IO],
  cipher: ConnectionSecretCipher
) {
  private val probeCommand = "printf 'infradesk-ok\\n'"

  def test(request: TestSshConnectionRequest): IO[String] =
    for {
      config <- IO.fromEither(validateSsh(SshSettingsRequest(request.host, request.port, request.username)))
      password <- IO.fromEither(validatePassword(request.credentials.`type`, request.credentials.password))
      fingerprint <- probe(config, password)
    } yield fingerprint

  def create(orgId: UUID, request: SaveSshConnectionRequest): IO[ConnectionOverview] =
    for {
      validated <- IO.fromEither(validateRequest(request, requirePassword = true))
      (scope, ssh, password) = validated
      _ <- runner.run(validateScope(orgId, scope))
      fingerprint <- probe(ssh, password.get)
      now <- IO(Instant.now())
      id <- IO(UUID.randomUUID())
      secretId <- IO(UUID.randomUUID())
      secret <- IO(cipher.encrypt(secretId, orgId, password.get))
      connection = Connection(id, orgId, scope, "SSH", request.code.trim, request.name.trim,
        SshConnectionConfig.toConnectionConfig(ssh.copy(hostKeyFingerprint = Some(fingerprint))),
        Some(s"db:$secretId"), true, now, now)
      schedule = ConnectionSchedule(orgId, id, request.schedule.enabled, request.schedule.intervalSeconds, now)
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

  def update(orgId: UUID, id: UUID, request: SaveSshConnectionRequest): IO[ConnectionOverview] =
    for {
      validated <- IO.fromEither(validateRequest(request, requirePassword = false))
      (scope, submittedSsh, newPassword) = validated
      old <- runner.run(for {
        found <- connections.findById(orgId, id)
        _ <- validateScope(orgId, scope)
        schedule <- schedules.findByConnection(orgId, id)
      } yield (found, schedule))
      connection <- IO.fromOption(old._1)(ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
      _ <- if (connection.connectorType != "SSH" || !connection.isActive)
        IO.raiseError[Unit](ConnectionManagementError("INVALID_REQUEST", "Connection is not editable")) else IO.unit
      oldSsh <- IO.fromEither(SshConnectionConfig.from(connection.config))
      endpointChanged = oldSsh.host != submittedSsh.host || oldSsh.port != submittedSsh.port
      requiresProbe = endpointChanged || oldSsh.username != submittedSsh.username || newPassword.nonEmpty
      effectiveSsh = submittedSsh.copy(hostKeyFingerprint = if (endpointChanged) None else oldSsh.hostKeyFingerprint)
      fingerprint <- if (requiresProbe)
        (newPassword match {
          case Some(password) => IO.pure(SshAuthentication.Password(password): SshAuthentication)
          case None => credentials.resolve(connection)
        }).flatMap(auth => probeWithAuth(effectiveSsh, auth))
      else IO.pure(oldSsh.hostKeyFingerprint.getOrElse(""))
      now <- IO(Instant.now())
      newSecretId <- if (newPassword.nonEmpty) IO(Some(UUID.randomUUID())) else IO.pure(None)
      newSecret <- IO(newSecretId.map(secretId => cipher.encrypt(secretId, orgId, newPassword.get)))
      next = connection.copy(scope = scope, code = request.code.trim, name = request.name.trim,
        config = SshConnectionConfig.toConnectionConfig(effectiveSsh.copy(
          hostKeyFingerprint = if (fingerprint.nonEmpty) Some(fingerprint) else None)),
        secretRef = newSecretId.map(secretId => s"db:$secretId").orElse(connection.secretRef), updatedAt = now)
      schedule = ConnectionSchedule(orgId, id, request.schedule.enabled, request.schedule.intervalSeconds,
        if (request.schedule.enabled && !old._2.exists(_.enabled)) now else old._2.map(_.nextRunAt).getOrElse(now))
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
        _ <- schedules.save(schedule)
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

  private def validateRequest(request: SaveSshConnectionRequest, requirePassword: Boolean):
    Either[ConnectionManagementError, (ConnectionScope, SshConnectionConfig, Option[String])] =
    for {
      _ <- check(request.connectorType == "SSH", "UNSUPPORTED_CONNECTOR_TYPE", "Only SSH connections are supported")
      _ <- check(request.code.trim.nonEmpty && request.code.trim.length <= 64, "INVALID_REQUEST", "Invalid connection code")
      _ <- check(request.name.trim.nonEmpty && request.name.trim.length <= 255, "INVALID_REQUEST", "Invalid connection name")
      _ <- check(request.schedule.intervalSeconds > 0, "INVALID_REQUEST", "Schedule interval must be positive")
      scope <- validateScopeRequest(request.scope)
      ssh <- validateSsh(request.ssh)
      password <- request.credentials match {
        case Some(value) => validatePassword(value.`type`, value.password).map(Some(_))
        case None if requirePassword => Left(ConnectionManagementError("INVALID_REQUEST", "SSH password is required"))
        case None => Right(None)
      }
    } yield (scope, ssh, password)

  private def validateScopeRequest(value: ConnectionScopeRequest): Either[ConnectionManagementError, ConnectionScope] =
    value.`type` match {
      case "ORGANIZATION" if value.projectId.isEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Organization)
      case "PROJECT" if value.projectId.nonEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Project(value.projectId.get))
      case "ENVIRONMENT" if value.projectId.nonEmpty && value.environmentId.nonEmpty =>
        Right(ConnectionScope.Environment(value.projectId.get, value.environmentId.get))
      case _ => Left(ConnectionManagementError("INVALID_REQUEST", "Invalid connection scope"))
    }

  private def validateSsh(value: SshSettingsRequest): Either[ConnectionManagementError, SshConnectionConfig] =
    for {
      _ <- check(value.host.trim.nonEmpty,
        "INVALID_REQUEST", "Invalid SSH host")
      _ <- check(value.username.trim.nonEmpty && value.username.trim.length <= 255, "INVALID_REQUEST", "Invalid SSH username")
      port = value.port.getOrElse(22)
      _ <- check(port > 0 && port <= 65535, "INVALID_REQUEST", "Invalid SSH port")
    } yield SshConnectionConfig(value.host.trim, port, value.username.trim, None, 10, 30)

  private def validatePassword(kind: String, password: String): Either[ConnectionManagementError, String] =
    check(kind == "PASSWORD" && password.nonEmpty, "INVALID_REQUEST", "SSH password is required").map(_ => password)

  private def check(valid: Boolean, code: String, message: String): Either[ConnectionManagementError, Unit] =
    if (valid) Right(()) else Left(ConnectionManagementError(code, message))

  private def probe(config: SshConnectionConfig, password: String): IO[String] =
    probeWithAuth(config, SshAuthentication.Password(password))

  private def probeWithAuth(config: SshConnectionConfig, auth: SshAuthentication): IO[String] =
    client.execute(config, auth, probeCommand).attempt.flatMap {
      case Right(result) if result.exitCode == 0 && result.stdout == "infradesk-ok\n" =>
        IO.pure(result.hostKeyFingerprint)
      case Right(_) => IO.raiseError(ConnectionManagementError("SSH_CONNECTION_FAILED", "Unable to connect using these SSH settings"))
      case Left(_: SshHostKeyMismatch) =>
        IO.raiseError(ConnectionManagementError("SSH_HOST_KEY_MISMATCH", "SSH host key has changed"))
      case Left(_) => IO.raiseError(ConnectionManagementError("SSH_CONNECTION_FAILED", "Unable to connect using these SSH settings"))
    }
}
