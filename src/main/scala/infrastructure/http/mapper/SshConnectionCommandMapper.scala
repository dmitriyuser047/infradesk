package ru.bitec.app.ops
package infrastructure.http.mapper

import application.connection.{ConnectionManagementError, CreateSshConnectionCommand, SshScheduleCommand, TestSshConnectionCommand, UpdateSshConnectionCommand}
import domain.connection.{ConnectionScope, SshConnectionSettings}
import infrastructure.http.dto.{ConnectionScopeRequest, PasswordCredentialsRequest, SaveSshConnectionRequest, SshSettingsRequest, TestSshConnectionRequest}

object SshConnectionCommandMapper {
  def create(request: SaveSshConnectionRequest): Either[ConnectionManagementError, CreateSshConnectionCommand] =
    for {
      _ <- connectorType(request.connectorType)
      scope <- toScope(request.scope)
      password <- request.credentials.toRight(invalid("SSH password is required")).flatMap(toPassword)
    } yield CreateSshConnectionCommand(request.code, request.name, scope, toSettings(request.ssh), password,
      SshScheduleCommand(request.schedule.enabled, request.schedule.intervalSeconds))

  def update(request: SaveSshConnectionRequest): Either[ConnectionManagementError, UpdateSshConnectionCommand] =
    for {
      _ <- connectorType(request.connectorType)
      scope <- toScope(request.scope)
      password <- request.credentials match {
        case Some(value) => toPassword(value).map(Some(_))
        case None => Right(None)
      }
    } yield UpdateSshConnectionCommand(request.code, request.name, scope, toSettings(request.ssh), password,
      SshScheduleCommand(request.schedule.enabled, request.schedule.intervalSeconds))

  def test(request: TestSshConnectionRequest): Either[ConnectionManagementError, TestSshConnectionCommand] =
    toPassword(request.credentials).map(password =>
      TestSshConnectionCommand(toSettings(SshSettingsRequest(request.host, request.port, request.username)), password))

  private def connectorType(value: String): Either[ConnectionManagementError, Unit] =
    if (value == "SSH") Right(())
    else Left(ConnectionManagementError("UNSUPPORTED_CONNECTOR_TYPE", "Only SSH connections are supported"))

  private def toPassword(value: PasswordCredentialsRequest): Either[ConnectionManagementError, String] =
    if (value.`type` == "PASSWORD") Right(value.password)
    else Left(invalid("Only SSH password credentials are supported"))

  private def toScope(value: ConnectionScopeRequest): Either[ConnectionManagementError, ConnectionScope] =
    value.`type` match {
      case "ORGANIZATION" if value.projectId.isEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Organization)
      case "PROJECT" if value.projectId.nonEmpty && value.environmentId.isEmpty => Right(ConnectionScope.Project(value.projectId.get))
      case "ENVIRONMENT" if value.projectId.nonEmpty && value.environmentId.nonEmpty =>
        Right(ConnectionScope.Environment(value.projectId.get, value.environmentId.get))
      case _ => Left(invalid("Invalid connection scope"))
    }

  private def toSettings(value: SshSettingsRequest): SshConnectionSettings =
    SshConnectionSettings(value.host, value.port.getOrElse(22), value.username, None, 10, 30)

  private def invalid(message: String): ConnectionManagementError =
    ConnectionManagementError("INVALID_REQUEST", message)
}
