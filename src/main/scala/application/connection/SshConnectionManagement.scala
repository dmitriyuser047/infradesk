package ru.bitec.app.ops
package application.connection

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.port.{ConnectionRepository, ConnectionScheduleRepository, ConnectionSecretCryptography, ConnectionSecretRepository, EnvironmentRepository, ProjectRepository, SshConnectionProbe, SshCredentialResolver, SshProbeError, TransactionRunner}
import cats.MonadThrow
import cats.effect.IO
import cats.syntax.all._
import domain.audit.{AuditAction, AuditTargetType}
import domain.connection.{Connection, ConnectionSchedule, ConnectionScope, SecretRef, SshAuthenticationType, SshConnectionSettings, SshCredential}

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
  credentials: SshCredentialResolver[IO],
  cipher: ConnectionSecretCryptography,
  audit: AuditRecorder[Tx]
) {
  /** Step one of onboarding: learn who the host is, without offering it anything. */
  def probeHost(request: ProbeSshHostCommand): IO[String] =
    IO.fromEither(validateSsh(request.ssh)).flatMap(settings =>
      probe.probeHostKey(settings.copy(hostKeyFingerprint = None)).adaptError {
        case error: SshProbeError => managementError(error)
      }
    )

  /** Step two: authenticate, but only against the identity the caller has confirmed. */
  def test(request: TestSshConnectionCommand): IO[String] =
    for {
      settings <- IO.fromEither(validateSsh(request.ssh))
      _ <- IO.fromEither(validateCredential(request.credential))
      _ <- IO.fromEither(requireTrustedHost(settings))
      fingerprint <- probeConnection(settings, request.credential)
    } yield fingerprint

  def create(actor: ActorContext, request: CreateSshConnectionCommand): IO[ConnectionOverview] = {
    val orgId = actor.organizationId
    for {
      _ <- IO.fromEither(validateRequest(request.code, request.name, request.schedule))
      ssh <- IO.fromEither(validateSsh(request.ssh))
      _ <- IO.fromEither(validateCredential(request.credential))
      _ <- IO.fromEither(requireMatchingCredentialType(ssh, request.credential))
      // A connection is created against a confirmed identity: the caller has probed the host and
      // is telling us which fingerprint it accepts.
      _ <- IO.fromEither(requireTrustedHost(ssh))
      _ <- runner.run(validateScope(orgId, request.scope))
      fingerprint <- probeConnection(ssh, request.credential)
      now <- IO(Instant.now())
      id <- IO(UUID.randomUUID())
      secretId <- IO(UUID.randomUUID())
      secret <- IO(cipher.encrypt(secretId, orgId, request.credential))
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
        _ <- audit.record(actor, AuditAction.ConnectionCreated, AuditTargetType.Connection,
          Some(connection.id))
      } yield ())
    } yield ConnectionOverview(connection, Some(schedule), None)
  }

  def update(actor: ActorContext, id: UUID, request: UpdateSshConnectionCommand): IO[ConnectionOverview] = {
    val orgId = actor.organizationId
    for {
      _ <- IO.fromEither(validateRequest(request.code, request.name, request.schedule))
      submittedSsh <- IO.fromEither(validateSsh(request.ssh))
      _ <- request.credential.traverse_(value => IO.fromEither(validateCredential(value)))
      _ <- request.credential.traverse_(value =>
        IO.fromEither(requireMatchingCredentialType(submittedSsh, value)))
      old <- runner.run(for {
        found <- connections.findById(orgId, id)
        _ <- validateScope(orgId, request.scope)
        schedule <- schedules.findByConnection(orgId, id)
      } yield (found, schedule))
      connection <- IO.fromOption(old._1)(ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
      _ <- if (connection.connectorType != "SSH" || !connection.isActive)
        IO.raiseError[Unit](ConnectionManagementError("INVALID_REQUEST", "Connection is not editable")) else IO.unit
      oldSsh <- IO.fromEither(SshConnectionSettings.from(connection.config))
      // Switching authentication method cannot reuse the credential of the previous one.
      authenticationType = submittedSsh.authenticationType
      _ <- IO.fromEither(requireCredentialForTypeChange(oldSsh, authenticationType, request.credential))
      endpointChanged = oldSsh.host != submittedSsh.host || oldSsh.port != submittedSsh.port
      // A trusted fingerprint the caller supplies replaces the stored one: that is how a rotated
      // host key is accepted, and only ever by an explicit decision.
      trusted = submittedSsh.hostKeyFingerprint
        .orElse(if (endpointChanged) None else oldSsh.hostKeyFingerprint)
      effectiveSsh = submittedSsh.copy(hostKeyFingerprint = trusted,
        authenticationType = authenticationType)
      _ <- IO.fromEither(requireTrustedHost(effectiveSsh))
      requiresProbe = endpointChanged || oldSsh.username != submittedSsh.username ||
        request.credential.nonEmpty || trusted != oldSsh.hostKeyFingerprint
      credential <- request.credential match {
        case Some(value) => IO.pure(Some(value))
        case None if requiresProbe => credentials.resolveCredential(connection).map(Some(_))
        case None => IO.pure(None)
      }
      fingerprint <- if (requiresProbe)
        credential.fold[IO[String]](
          IO.raiseError(ConnectionManagementError("INVALID_REQUEST", "SSH credential is required"))
        )(probeConnection(effectiveSsh, _))
      else IO.pure(trusted.getOrElse(""))
      now <- IO(Instant.now())
      newSecretId <- if (request.credential.nonEmpty) IO(Some(UUID.randomUUID())) else IO.pure(None)
      newSecret <- IO((newSecretId, request.credential).mapN((secretId, value) =>
        cipher.encrypt(secretId, orgId, value)))
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
        existing <- connections.findByOrganization(orgId)
        _ <- if (existing.exists(c => c.id != id && c.code.equalsIgnoreCase(next.code)))
          MonadThrow[Tx].raiseError[Unit](ConnectionManagementError("CONNECTION_CODE_ALREADY_EXISTS", "Connection code already exists"))
        else MonadThrow[Tx].unit
        _ <- newSecret.fold(MonadThrow[Tx].unit)(secrets.save)
        saved <- connections.saveIfUnmodified(next, connection.updatedAt)
        _ <- if (saved) MonadThrow[Tx].unit else MonadThrow[Tx].raiseError[Unit](
          ConnectionManagementError("CONNECTION_MODIFIED",
            "Connection changed while SSH settings were being verified; retry the update"))
        _ <- if (scheduleChanged) schedules.save(schedule) else MonadThrow[Tx].unit
        _ <- if (newSecret.nonEmpty) connection.secretRef.flatMap(raw => SecretRef.parse(raw).toOption) match {
          case Some(SecretRef.Database(oldSecretId)) => secrets.delete(orgId, oldSecretId)
          case _ => MonadThrow[Tx].unit
        } else MonadThrow[Tx].unit
        _ <- audit.record(actor, AuditAction.ConnectionUpdated, AuditTargetType.Connection, Some(id))
      } yield ())
    } yield ConnectionOverview(next, Some(schedule), None)
  }

  def deactivate(actor: ActorContext, id: UUID): IO[Unit] = {
    val orgId = actor.organizationId
    runner.run(for {
      found <- connections.findById(orgId, id)
      connection <- found.liftTo[Tx](ConnectionManagementError("CONNECTION_NOT_FOUND", "Connection was not found"))
      now <- MonadThrow[Tx].pure(Instant.now())
      _ <- if (connection.isActive) connections.save(connection.copy(isActive = false, updatedAt = now))
        else MonadThrow[Tx].unit
      schedule <- schedules.findByConnection(orgId, id)
      _ <- schedule.fold(MonadThrow[Tx].unit)(s => schedules.save(s.copy(enabled = false)))
      // The journal outlives the connection, which is why the target carries no foreign key.
      _ <- audit.record(actor, AuditAction.ConnectionDeleted, AuditTargetType.Connection, Some(id))
    } yield ())
  }

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

  private def validateCredential(credential: SshCredential): Either[ConnectionManagementError, Unit] =
    credential match {
      case SshCredential.Password(value) =>
        check(value.nonEmpty, "INVALID_REQUEST", "SSH password is required")
      case SshCredential.PrivateKey(pem, passphrase) =>
        for {
          _ <- check(pem.trim.nonEmpty, "INVALID_REQUEST", "SSH private key is required")
          _ <- check(passphrase.forall(_.nonEmpty), "INVALID_REQUEST", "SSH passphrase must not be empty")
        } yield ()
    }

  private def requireMatchingCredentialType(
    settings: SshConnectionSettings,
    credential: SshCredential
  ): Either[ConnectionManagementError, Unit] =
    check(settings.authenticationType == credential.authenticationType, "INVALID_REQUEST",
      "SSH credential does not match the requested authentication type")

  /** No credential is sent to a host whose identity has not been confirmed. */
  private def requireTrustedHost(settings: SshConnectionSettings): Either[ConnectionManagementError, Unit] =
    check(settings.hostTrusted, "SSH_HOST_KEY_NOT_TRUSTED",
      "Confirm the host key fingerprint before connecting")

  private def requireCredentialForTypeChange(
    stored: SshConnectionSettings,
    requested: SshAuthenticationType,
    credential: Option[SshCredential]
  ): Either[ConnectionManagementError, Unit] =
    check(requested == stored.authenticationType || credential.nonEmpty, "INVALID_REQUEST",
      "A new credential is required when the authentication method changes")

  private def managementError(error: SshProbeError): ConnectionManagementError = error match {
    case SshProbeError.HostKeyMismatch =>
      ConnectionManagementError("SSH_HOST_KEY_MISMATCH", "SSH host identity has changed")
    case SshProbeError.HostKeyNotTrusted =>
      ConnectionManagementError("SSH_HOST_KEY_NOT_TRUSTED", "SSH host identity is not trusted")
    case SshProbeError.AuthenticationFailed =>
      ConnectionManagementError("SSH_AUTHENTICATION_FAILED", "SSH authentication failed")
    case SshProbeError.PrivateKeyInvalid =>
      ConnectionManagementError("SSH_PRIVATE_KEY_INVALID", "SSH private key could not be read")
    case SshProbeError.PrivateKeyPassphraseInvalid =>
      ConnectionManagementError("SSH_PRIVATE_KEY_PASSPHRASE_INVALID", "SSH private key passphrase is invalid")
    case SshProbeError.ConnectionFailed =>
      ConnectionManagementError("SSH_CONNECTION_FAILED", "SSH connection failed")
  }

  private def check(valid: Boolean, code: String, message: String): Either[ConnectionManagementError, Unit] =
    if (valid) Right(()) else Left(ConnectionManagementError(code, message))

  private def probeConnection(config: SshConnectionSettings, credential: SshCredential): IO[String] =
    probe.verify(config, credential).handleErrorWith {
      case error: SshProbeError => IO.raiseError(managementError(error))
      case _ => IO.raiseError(
        ConnectionManagementError("SSH_CONNECTION_FAILED", "Unable to connect using these SSH settings"))
    }
}
