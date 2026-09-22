package ru.bitec.app.ops
package persistence.postgres

import application.port.{ConnectionSecret, ConnectionSecretRepository}
import cats.syntax.all._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.util.UUID

final class PostgresConnectionSecretRepository extends ConnectionSecretRepository[ConnectionIO] {
  override def save(secret: ConnectionSecret): ConnectionIO[Unit] =
    sql"""insert into connection_secret (id, organization_id, kind, nonce, ciphertext)
           values (${secret.id}, ${secret.organizationId}, ${secret.kind}, ${secret.nonce}, ${secret.ciphertext})""".update.run.void

  override def find(organizationId: UUID, id: UUID): ConnectionIO[Option[ConnectionSecret]] =
    sql"""select id, organization_id, kind, nonce, ciphertext from connection_secret
           where organization_id = $organizationId and id = $id"""
      .query[(UUID, UUID, String, Array[Byte], Array[Byte])]
      .option.map(_.map { case (secretId, orgId, kind, nonce, ciphertext) =>
        ConnectionSecret(secretId, orgId, kind, nonce, ciphertext)
      })

  override def delete(organizationId: UUID, id: UUID): ConnectionIO[Unit] =
    sql"delete from connection_secret where organization_id = $organizationId and id = $id".update.run.void
}
