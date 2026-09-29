package ru.bitec.app.ops
package persistence.postgres

import application.port.{IntegrationRepository, IntegrationSecret, IntegrationSecretRepository}
import cats.syntax.all._
import domain.integration.{Integration, IntegrationBaseUrl, IntegrationProviderType}
import org.typelevel.doobie.{ConnectionIO, Fragment, Query0}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import java.time.Instant
import java.util.UUID

final class PostgresIntegrationRepository extends IntegrationRepository[ConnectionIO] {
  private val columns = fr"id, organization_id, name, provider_type, base_url, enabled, secret_id, caddy_api_key_configured, created_at, updated_at"
  private type Row = (UUID, UUID, String, String, String, Boolean, UUID, Boolean, Instant, Instant)

  private def query(where: Fragment): Query0[Row] =
    (fr"select" ++ columns ++ fr"from integration" ++ where).query[Row]

  private def typed(row: Row): ConnectionIO[Integration] = {
    val (id, org, name, provider, url, enabled, secret, caddy, created, updated) = row
    (for { p <- IntegrationProviderType.fromCode(provider); u <- IntegrationBaseUrl.parse(url) }
      yield Integration(id, org, name, p, u, enabled, secret, caddy, created, updated)).liftTo[ConnectionIO]
  }

  override def listByOrganization(org: UUID): ConnectionIO[List[Integration]] =
    query(fr"where organization_id = $org order by name, id").to[List].flatMap(_.traverse(typed))

  override def findById(org: UUID, id: UUID): ConnectionIO[Option[Integration]] =
    query(fr"where organization_id = $org and id = $id").option.flatMap(_.traverse(typed))

  override def findByIdForUpdate(org: UUID, id: UUID): ConnectionIO[Option[Integration]] =
    query(fr"where organization_id = $org and id = $id for update").option.flatMap(_.traverse(typed))

  override def save(value: Integration): ConnectionIO[Unit] =
    sql"""insert into integration (id, organization_id, name, provider_type, base_url, enabled,
           secret_id, caddy_api_key_configured, created_at, updated_at)
           values (${value.id}, ${value.organizationId}, ${value.name}, ${value.providerType.code},
           ${value.baseUrl.value}, ${value.enabled}, ${value.secretId}, ${value.caddyApiKeyConfigured},
           ${value.createdAt}, ${value.updatedAt})
           on conflict (id) do update set name = excluded.name, provider_type = excluded.provider_type,
           base_url = excluded.base_url, enabled = excluded.enabled, secret_id = excluded.secret_id,
           caddy_api_key_configured = excluded.caddy_api_key_configured, updated_at = excluded.updated_at
           where integration.organization_id = ${value.organizationId}""".update.run.flatMap {
      case 1 => ().pure[ConnectionIO]
      case _ => new IllegalStateException("Integration was not written").raiseError[ConnectionIO, Unit]
    }

  override def delete(org: UUID, id: UUID): ConnectionIO[Unit] =
    sql"delete from integration where organization_id = $org and id = $id".update.run.void
}

final class PostgresIntegrationSecretRepository extends IntegrationSecretRepository[ConnectionIO] {
  override def save(secret: IntegrationSecret): ConnectionIO[Unit] =
    sql"""insert into integration_secret (id, organization_id, kind, nonce, ciphertext, created_at)
           values (${secret.id}, ${secret.organizationId}, ${secret.kind}, ${secret.nonce},
             ${secret.ciphertext}, current_timestamp)""".update.run.void

  override def find(org: UUID, id: UUID): ConnectionIO[Option[IntegrationSecret]] =
    sql"""select id, organization_id, kind, nonce, ciphertext from integration_secret
           where organization_id = $org and id = $id"""
      .query[(UUID, UUID, String, Array[Byte], Array[Byte])].option.map(_.map {
        case (secretId, organizationId, kind, nonce, ciphertext) =>
          IntegrationSecret(secretId, organizationId, kind, nonce, ciphertext)
      })

  override def delete(org: UUID, id: UUID): ConnectionIO[Unit] =
    sql"delete from integration_secret where organization_id = $org and id = $id".update.run.void
}
