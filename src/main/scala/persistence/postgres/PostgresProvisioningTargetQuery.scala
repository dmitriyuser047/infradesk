package ru.bitec.app.ops
package persistence.postgres

import application.port.{ProvisioningTarget, ProvisioningTargetQuery}
import cats.syntax.all._
import domain.connection.Connection
import integration.ssh.SshConnectionConfig
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

final class PostgresProvisioningTargetQuery extends ProvisioningTargetQuery[ConnectionIO] {
  private val connections = new PostgresConnectionRepository
  override def eligible(organizationId: UUID, resourceId: UUID): ConnectionIO[Either[String, ProvisioningTarget]] =
    sql"""select distinct rt.code, coalesce(r.spec ->> 'kind', ''), c.id, c.updated_at
      from resource r join resource_type rt on rt.id = r.resource_type_id
      join external_ref e on e.resource_id = r.id and e.organization_id = r.organization_id
      join connection c on c.id = e.connection_id and c.organization_id = e.organization_id
      where r.organization_id = $organizationId and r.id = $resourceId and r.is_active
        and rt.code = 'NODE' and c.is_active and c.connector_type = 'SSH'
      order by c.id""".query[(String, String, UUID, java.time.Instant)].to[List].flatMap {
      case Nil => (Left("PROVISIONING_TARGET_NOT_FOUND"): Either[String, ProvisioningTarget]).pure[ConnectionIO]
      case rows if rows.size > 1 => (Left("PROVISIONING_TARGET_AMBIGUOUS"): Either[String, ProvisioningTarget]).pure[ConnectionIO]
      case (kind, resourceKind, connectionId, updatedAt) :: Nil =>
        connections.findById(organizationId, connectionId).map {
          case Some(connection) if connection.updatedAt == updatedAt && connection.isActive &&
            connection.connectorType == "SSH" && SshConnectionConfig.from(connection.config).toOption
              .exists(_.hostKeyFingerprint.isDefined) =>
            Right(ProvisioningTarget(kind, resourceKind, connection.id, updatedAt, connection))
          case _ => Left("PROVISIONING_TARGET_NOT_FOUND")
        }
      case _ => (Left("PROVISIONING_TARGET_AMBIGUOUS"): Either[String, ProvisioningTarget]).pure[ConnectionIO]
    }
  override def unchanged(snapshot: domain.provisioning.ProvisioningInputSnapshot): ConnectionIO[Boolean] =
    eligible(snapshot.organizationId, snapshot.resourceId).map(_.exists(target =>
      target.resourceType == snapshot.resourceType && target.resourceKind == snapshot.resourceKind &&
        target.connectionId == snapshot.connectionId && target.connectionUpdatedAt == snapshot.connectionUpdatedAt))
}
