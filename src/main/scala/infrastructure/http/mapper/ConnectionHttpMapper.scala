package ru.bitec.app.ops
package infrastructure.http.mapper

import application.connection.ConnectionOverview
import domain.connection.ConnectionScope
import infrastructure.http.dto.{
  ConnectionResponse,
  ConnectionScheduleResponse,
  ConnectionScopeResponse,
  EnvironmentConnectionScopeResponse,
  OrganizationConnectionScopeResponse,
  ProjectConnectionScopeResponse,
  SyncSessionResponse,
  SshConnectionResponse
}
import integration.ssh.SshConnectionConfig

object ConnectionHttpMapper {
  def toResponse(overview: ConnectionOverview): ConnectionResponse = {
    val connection = overview.connection

    ConnectionResponse(
      connection.id,
      connection.connectorType,
      connection.code,
      connection.name,
      toScopeResponse(connection.scope),
      connection.isActive,
      overview.schedule.map { schedule =>
        ConnectionScheduleResponse(
          schedule.enabled,
          schedule.intervalSeconds,
          schedule.nextRunAt
        )
      },
      overview.lastSync.map(SyncSessionHttpMapper.toResponse),
      connection.createdAt,
      connection.updatedAt,
      if (connection.connectorType == "SSH") SshConnectionConfig.from(connection.config).toOption.map { ssh =>
        SshConnectionResponse(ssh.host, ssh.port, ssh.username, ssh.hostKeyFingerprint,
          connection.secretRef.nonEmpty, ssh.authenticationType.code, ssh.hostKeyFingerprint.isDefined)
      } else None
    )
  }

  private def toScopeResponse(scope: ConnectionScope): ConnectionScopeResponse =
    scope match {
      case ConnectionScope.Organization =>
        OrganizationConnectionScopeResponse(scope.code)
      case ConnectionScope.Project(projectId) =>
        ProjectConnectionScopeResponse(scope.code, projectId)
      case ConnectionScope.Environment(projectId, environmentId) =>
        EnvironmentConnectionScopeResponse(scope.code, projectId, environmentId)
    }
}
