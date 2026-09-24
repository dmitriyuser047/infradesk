package ru.bitec.app.ops
package persistence.postgres

import application.port.ConnectionRepository
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import io.circe.parser
import io.circe.syntax._

import java.time.Instant
import java.util.UUID

final class PostgresConnectionRepository extends ConnectionRepository[ConnectionIO] {

  private final case class ConnectionRow(
                                          id: UUID,
                                          organizationId: UUID,
                                          scopeType: String,
                                          projectId: Option[UUID],
                                          environmentId: Option[UUID],
                                          connectorType: String,
                                          code: String,
                                          name: String,
                                          configJson: String,
                                          secretRef: Option[String],
                                          isActive: Boolean,
                                          createdAt: Instant,
                                          updatedAt: Instant
                                        ) {

    def toDomain: Connection =
      Connection(
        id = id,
        organizationId = organizationId,
        scope = toScope,
        connectorType = connectorType,
        code = code,
        name = name,
        config = toConfig,
        secretRef = secretRef,
        isActive = isActive,
        createdAt = createdAt,
        updatedAt = updatedAt
      )

    private def toConfig: ConnectionConfig =
      parser.decode[Map[String, String]](configJson) match {
        case Right(values) =>
          ConnectionConfig(values)

        case Left(error) =>
          throw new IllegalStateException(
            s"Connection $id has invalid config: ${error.getMessage}",
            error
          )
      }

    private def toScope: ConnectionScope =
      scopeType match {
        case ConnectionScope.OrganizationCode =>
          ConnectionScope.Organization

        case ConnectionScope.ProjectCode =>
          ConnectionScope.Project(requiredProjectId)

        case ConnectionScope.EnvironmentCode =>
          ConnectionScope.Environment(requiredProjectId, requiredEnvironmentId)

        case value =>
          throw new IllegalStateException(
            s"Connection $id has unsupported scope type: $value"
          )
      }

    private def requiredProjectId: UUID =
      projectId.getOrElse {
        throw new IllegalStateException(
          s"Connection $id has $scopeType scope without project_id"
        )
      }

    private def requiredEnvironmentId: UUID =
      environmentId.getOrElse {
        throw new IllegalStateException(
          s"Connection $id has $scopeType scope without environment_id"
        )
      }
  }

  override def findById(
                         organizationId: UUID,
                         id: UUID
                       ): ConnectionIO[Option[Connection]] =
    sql"""
      select
        id,
        organization_id,
        scope_type,
        project_id,
        environment_id,
        connector_type,
        code,
        name,
        config::text,
        secret_ref,
        is_active,
        created_at,
        updated_at
      from connection
      where organization_id = $organizationId
        and id = $id
    """
      .query[ConnectionRow]
      .option
      .map(_.map(_.toDomain))

  override def findByOrganization(
    organizationId: UUID
  ): ConnectionIO[List[Connection]] =
    sql"""
      select
        id,
        organization_id,
        scope_type,
        project_id,
        environment_id,
        connector_type,
        code,
        name,
        config::text,
        secret_ref,
        is_active,
        created_at,
        updated_at
      from connection
      where organization_id = $organizationId
      order by name asc, id asc
    """
      .query[ConnectionRow]
      .to[List]
      .map(_.map(_.toDomain))

  override def save(connection: Connection): ConnectionIO[Unit] = {
    val (projectId, environmentId) =
      connection.scope match {
        case ConnectionScope.Organization =>
          (Option.empty[UUID], Option.empty[UUID])

        case ConnectionScope.Project(projectId) =>
          (Some(projectId), Option.empty[UUID])

        case ConnectionScope.Environment(projectId, environmentId) =>
          (Some(projectId), Some(environmentId))
      }

    val configJson = connection.config.values.asJson.noSpaces

    sql"""
      insert into connection (
        id,
        organization_id,
        scope_type,
        project_id,
        environment_id,
        connector_type,
        code,
        name,
        config,
        secret_ref,
        is_active,
        created_at,
        updated_at
      )
      values (
        ${connection.id},
        ${connection.organizationId},
        ${connection.scope.code},
        $projectId,
        $environmentId,
        ${connection.connectorType},
        ${connection.code},
        ${connection.name},
        cast($configJson as jsonb),
        ${connection.secretRef},
        ${connection.isActive},
        ${connection.createdAt},
        ${connection.updatedAt}
      )
      on conflict (id)
      do update set
        scope_type = excluded.scope_type,
        project_id = excluded.project_id,
        environment_id = excluded.environment_id,
        connector_type = excluded.connector_type,
        code = excluded.code,
        name = excluded.name,
        config = excluded.config,
        secret_ref = excluded.secret_ref,
        is_active = excluded.is_active,
        updated_at = excluded.updated_at
      where connection.organization_id = excluded.organization_id
    """
      .update
      .run
      .flatMap {
        case 1 =>
          ().pure[ConnectionIO]

        case rows =>
          new IllegalStateException(
            s"Expected to save 1 connection row, affected: $rows"
          ).raiseError[ConnectionIO, Unit]
      }
  }

  override def saveIfUnmodified(
    connection: Connection,
    expectedUpdatedAt: Instant
  ): ConnectionIO[Boolean] = {
    val (projectId, environmentId) = scopeIds(connection.scope)
    val configJson = connection.config.values.asJson.noSpaces

    sql"""
      update connection
      set scope_type = ${connection.scope.code},
          project_id = $projectId,
          environment_id = $environmentId,
          connector_type = ${connection.connectorType},
          code = ${connection.code},
          name = ${connection.name},
          config = cast($configJson as jsonb),
          secret_ref = ${connection.secretRef},
          is_active = ${connection.isActive},
          updated_at = ${connection.updatedAt}
      where organization_id = ${connection.organizationId}
        and id = ${connection.id}
        and updated_at = $expectedUpdatedAt
    """.update.run.map(_ == 1)
  }

  private def scopeIds(scope: ConnectionScope): (Option[UUID], Option[UUID]) = scope match {
    case ConnectionScope.Organization =>
      (Option.empty[UUID], Option.empty[UUID])
    case ConnectionScope.Project(projectId) =>
      (Some(projectId), Option.empty[UUID])
    case ConnectionScope.Environment(projectId, environmentId) =>
      (Some(projectId), Some(environmentId))
  }
}
