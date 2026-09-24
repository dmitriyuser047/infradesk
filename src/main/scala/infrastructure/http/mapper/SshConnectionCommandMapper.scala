package ru.bitec.app.ops
package infrastructure.http.mapper

import application.connection.{ConnectionManagementError, CreateSshConnectionCommand, ProbeSshHostCommand, SshScheduleCommand, TestSshConnectionCommand, UpdateSshConnectionCommand}
import cats.syntax.all._
import domain.connection.{ConnectionScope, SshAuthenticationType, SshConnectionSettings, SshCredential}
import infrastructure.http.dto.{ConnectionScopeRequest, ProbeSshHostRequest, SaveSshConnectionRequest, SshCredentialsRequest, SshSettingsRequest, TestSshConnectionRequest}

object SshConnectionCommandMapper {
  def create(request: SaveSshConnectionRequest): Either[ConnectionManagementError, CreateSshConnectionCommand] =
    for {
      _ <- connectorType(request.connectorType)
      scope <- toScope(request.scope)
      settings <- toSettings(request.ssh)
      credential <- request.credentials.toRight(invalid("SSH credentials are required"))
        .flatMap(toCredential)
    } yield CreateSshConnectionCommand(request.code, request.name, scope, settings,
      credential, SshScheduleCommand(request.schedule.enabled, request.schedule.intervalSeconds))

  def update(request: SaveSshConnectionRequest): Either[ConnectionManagementError, UpdateSshConnectionCommand] =
    for {
      _ <- connectorType(request.connectorType)
      scope <- toScope(request.scope)
      settings <- toSettings(request.ssh)
      // Absent credentials mean "keep the stored one"; they never mean "clear it".
      credential <- request.credentials.traverse(toCredential)
    } yield UpdateSshConnectionCommand(request.code, request.name, scope, settings,
      credential, SshScheduleCommand(request.schedule.enabled, request.schedule.intervalSeconds))

  def test(request: TestSshConnectionRequest): Either[ConnectionManagementError, TestSshConnectionCommand] =
    for {
      credential <- toCredential(request.credentials)
      settings <- toSettings(SshSettingsRequest(request.host, request.port, request.username,
        credential.authenticationType.code, request.hostKeyFingerprint))
    } yield TestSshConnectionCommand(settings, credential)

  def probe(request: ProbeSshHostRequest): Either[ConnectionManagementError, ProbeSshHostCommand] =
    toSettings(SshSettingsRequest(request.host, request.port, request.username,
      SshAuthenticationType.Password.code, None)).map(ProbeSshHostCommand.apply)

  private def connectorType(value: String): Either[ConnectionManagementError, Unit] =
    if (value == "SSH") Right(())
    else Left(ConnectionManagementError("UNSUPPORTED_CONNECTOR_TYPE", "Only SSH connections are supported"))

  /** One place where a submitted credential becomes a typed one; an unknown discriminator is a
    * validation failure rather than a match error.
    */
  private def toCredential(value: SshCredentialsRequest): Either[ConnectionManagementError, SshCredential] =
    SshAuthenticationType.fromCode(value.`type`)
      .leftMap(_ => invalid("Unsupported SSH authentication type"))
      .flatMap {
        case SshAuthenticationType.Password =>
          value.password.filter(_.nonEmpty)
            .toRight(invalid("SSH password is required"))
            .map(SshCredential.Password.apply)
        case SshAuthenticationType.PrivateKey =>
          value.privateKey.filter(_.trim.nonEmpty)
            .toRight(invalid("SSH private key is required"))
            .map(pem => SshCredential.PrivateKey(pem,
              value.passphrase.filter(_.nonEmpty)))
      }

  private def toScope(value: ConnectionScopeRequest): Either[ConnectionManagementError, ConnectionScope] =
    value.`type` match {
      case "ORGANIZATION" if value.projectId.isEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Organization)
      case "PROJECT" if value.projectId.nonEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Project(value.projectId.get))
      case "ENVIRONMENT" if value.projectId.nonEmpty && value.environmentId.nonEmpty =>
        Right(ConnectionScope.Environment(value.projectId.get, value.environmentId.get))
      case _ => Left(invalid("Invalid connection scope"))
    }

  private def toSettings(value: SshSettingsRequest): Either[ConnectionManagementError, SshConnectionSettings] =
    SshAuthenticationType.fromCode(value.authenticationType)
      .leftMap(_ => invalid("Unsupported SSH authentication type"))
      .map(authenticationType => SshConnectionSettings(value.host, value.port.getOrElse(22), value.username,
        value.hostKeyFingerprint.map(_.trim).filter(_.nonEmpty), 10, 30, authenticationType))

  private def invalid(message: String): ConnectionManagementError =
    ConnectionManagementError("INVALID_REQUEST", message)
}
