package ru.bitec.app.ops
package persistence.postgres

import application.port.{ResourceOperationTarget, ResourceOperationTargetProjection, ResourceOperationTargetQuery}
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import io.circe.parser
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresResourceOperationTargetQuery extends ResourceOperationTargetQuery[ConnectionIO] {
  override def find(organizationId: UUID, resourceId: UUID): ConnectionIO[Option[ResourceOperationTargetProjection]] =
    sql"""select r.id, rt.code, r.is_active,
      c.id, c.organization_id, c.scope_type, c.project_id, c.environment_id, c.connector_type,
      c.code, c.name, c.config::text, c.secret_ref, c.is_active, c.created_at, c.updated_at,
      er.external_type, er.external_id
      from resource r
      join resource_type rt on rt.id = r.resource_type_id
      left join external_ref er on er.organization_id = r.organization_id and er.resource_id = r.id
        and er.external_type = 'CONTAINER'
      left join connection c on c.id = er.connection_id and c.organization_id = r.organization_id
        and c.connector_type = 'SSH' and c.is_active = true
      where r.organization_id = $organizationId and r.id = $resourceId"""
      .query[Row].to[List].map {
        case Nil => None
        case rows =>
          val head = rows.head
          Some(ResourceOperationTargetProjection(head.resourceId, head.resourceTypeCode,
            head.resourceActive, rows.flatMap(_.target)))
      }

  private final case class Row(resourceId: UUID, resourceTypeCode: String, resourceActive: Boolean,
    connectionId: Option[UUID], connectionOrganizationId: Option[UUID], scopeType: Option[String],
    projectId: Option[UUID], environmentId: Option[UUID], connectorType: Option[String],
    connectionCode: Option[String], connectionName: Option[String], configJson: Option[String],
    secretRef: Option[String], connectionActive: Option[Boolean], connectionCreatedAt: Option[Instant],
    connectionUpdatedAt: Option[Instant], externalType: Option[String], externalId: Option[String]) {

    def target: Option[ResourceOperationTarget] = for {
      id <- connectionId
      org <- connectionOrganizationId
      scopeCode <- scopeType
      connector <- connectorType
      code <- connectionCode
      name <- connectionName
      json <- configJson
      active <- connectionActive
      created <- connectionCreatedAt
      updated <- connectionUpdatedAt
      extType <- externalType
      extId <- externalId
    } yield ResourceOperationTarget(Connection(id, org, scope(scopeCode), connector, code, name,
      ConnectionConfig(parser.decode[Map[String, String]](json).fold(throw _, identity)), secretRef,
      active, created, updated), extType, extId)

    private def scope(value: String): ConnectionScope = value match {
      case ConnectionScope.OrganizationCode => ConnectionScope.Organization
      case ConnectionScope.ProjectCode => ConnectionScope.Project(projectId.getOrElse(invalidScope(value)))
      case ConnectionScope.EnvironmentCode => ConnectionScope.Environment(
        projectId.getOrElse(invalidScope(value)), environmentId.getOrElse(invalidScope(value)))
      case other => invalidScope(other)
    }
    private def invalidScope(value: String): Nothing =
      throw new IllegalStateException(s"Operation target has invalid connection scope '$value'")
  }
}
