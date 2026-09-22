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
  SyncSessionResponse
}

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
      overview.lastSync.map { session =>
        SyncSessionResponse(
          session.id,
          session.status.code,
          session.startedAt,
          session.finishedAt
        )
      },
      connection.createdAt,
      connection.updatedAt
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
