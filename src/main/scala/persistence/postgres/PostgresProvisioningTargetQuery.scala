package ru.bitec.app.ops
package persistence.postgres

import application.port.{ProvisioningTarget, ProvisioningTargetQuery}
import cats.syntax.all._
import integration.ssh.SshConnectionConfig
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.util.UUID

final class PostgresProvisioningTargetQuery extends ProvisioningTargetQuery[ConnectionIO] {
  private val connections = new PostgresConnectionRepository
  override def eligible(organizationId: UUID, resourceId: UUID): ConnectionIO[Either[String, ProvisioningTarget]] =
    eligibleBatch(organizationId, List(resourceId)).map(_(resourceId))

  override def eligibleBatch(organizationId: UUID, resourceIds: List[UUID]): ConnectionIO[Map[UUID, Either[String, ProvisioningTarget]]] = for {
    rows <- sql"""select distinct r.id, rt.code, coalesce(r.spec ->> 'kind', ''), c.id, c.updated_at
      from resource r join resource_type rt on rt.id = r.resource_type_id
      join external_ref e on e.resource_id = r.id and e.organization_id = r.organization_id
      join connection c on c.id = e.connection_id and c.organization_id = e.organization_id
      where r.organization_id = $organizationId and r.id=any(${resourceIds.toArray[UUID]}) and r.is_active
        and rt.code = 'NODE' and c.is_active and c.connector_type = 'SSH'
      order by r.id,c.id""".query[(UUID, String, String, UUID, java.time.Instant)].to[List]
    candidates = rows.groupBy(_._1)
    connectionIds = candidates.valuesIterator.collect { case (_, _, _, id, _) :: Nil => id }.toList.distinct
    loaded <- connections.findByIds(organizationId, connectionIds)
  } yield {
    resourceIds.iterator.map { id =>
      val result: Either[String, ProvisioningTarget] = candidates.getOrElse(id, Nil) match {
        case Nil => Left("PROVISIONING_TARGET_NOT_FOUND")
        case (_, kind, resourceKind, connectionId, updatedAt) :: Nil =>
          loaded.get(connectionId) match {
            case Some(connection) if connection.updatedAt == updatedAt && connection.isActive &&
              connection.connectorType == "SSH" && SshConnectionConfig.from(connection.config).toOption
                .exists(_.hostKeyFingerprint.isDefined) =>
              Right(ProvisioningTarget(kind, resourceKind, connection.id, updatedAt, connection))
            case _ => Left("PROVISIONING_TARGET_NOT_FOUND")
          }
        case _ => Left("PROVISIONING_TARGET_AMBIGUOUS")
      }
      id -> result
    }.toMap
  }
  override def unchanged(snapshot: domain.provisioning.ProvisioningInputSnapshot): ConnectionIO[Boolean] =
    eligible(snapshot.organizationId, snapshot.resourceId).map(_.exists(target =>
      target.resourceType == snapshot.resourceType && target.resourceKind == snapshot.resourceKind &&
        target.connectionId == snapshot.connectionId && target.connectionUpdatedAt == snapshot.connectionUpdatedAt))
}
