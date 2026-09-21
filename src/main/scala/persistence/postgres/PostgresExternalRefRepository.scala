package ru.bitec.app.ops
package persistence.postgres

import application.port.ExternalRefRepository
import domain.externalref.ExternalRef

import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresExternalRefRepository extends ExternalRefRepository[ConnectionIO] {

  override def findByExternalIdentity(
                                       organizationId: UUID,
                                       connectionId: UUID,
                                       externalType: String,
                                       externalId: String
                                     ): ConnectionIO[Option[ExternalRef]] =
    sql"""
      select
        id,
        organization_id,
        connection_id,
        external_type,
        external_id,
        resource_id,
        first_seen_at,
        last_seen_at,
        created_at,
        updated_at,
        last_seen_sync_session_id
      from external_ref
      where organization_id = $organizationId
        and connection_id = $connectionId
        and external_type = $externalType
        and external_id = $externalId
    """
      .query[ExternalRef]
      .option

  override def findByConnection(
                                organizationId: UUID,
                                connectionId: UUID
                              ): ConnectionIO[List[ExternalRef]] =
    sql"""
      select
        id,
        organization_id,
        connection_id,
        external_type,
        external_id,
        resource_id,
        first_seen_at,
        last_seen_at,
        created_at,
        updated_at,
        last_seen_sync_session_id
      from external_ref
      where organization_id = $organizationId
        and connection_id = $connectionId
    """
      .query[ExternalRef]
      .to[List]

  override def save(externalRef: ExternalRef): ConnectionIO[Unit] =
    sql"""
      insert into external_ref (
        id,
        organization_id,
        connection_id,
        external_type,
        external_id,
        resource_id,
        first_seen_at,
        last_seen_at,
        created_at,
        updated_at,
        last_seen_sync_session_id
      )
      values (
        ${externalRef.id},
        ${externalRef.organizationId},
        ${externalRef.connectionId},
        ${externalRef.externalType},
        ${externalRef.externalId},
        ${externalRef.resourceId},
        ${externalRef.firstSeenAt},
        ${externalRef.lastSeenAt},
        ${externalRef.createdAt},
        ${externalRef.updatedAt},
        ${externalRef.lastSeenSyncSessionId}
      )
      on conflict (connection_id, external_type, external_id)
      do update set
        last_seen_at = excluded.last_seen_at,
        updated_at = excluded.updated_at,
        last_seen_sync_session_id = excluded.last_seen_sync_session_id
      where external_ref.organization_id = excluded.organization_id
        and external_ref.resource_id = excluded.resource_id
    """
      .update
      .run
      .flatMap {
        case 1 => ().pure[ConnectionIO]
        case rows =>
          new IllegalStateException(
            s"Expected to save 1 external_ref row, affected: $rows"
          ).raiseError[ConnectionIO, Unit]
      }
}
