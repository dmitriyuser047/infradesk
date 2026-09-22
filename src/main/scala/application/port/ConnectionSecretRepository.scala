package ru.bitec.app.ops
package application.port

import java.util.UUID

final case class ConnectionSecret(id: UUID, organizationId: UUID, kind: String, nonce: Array[Byte], ciphertext: Array[Byte])

trait ConnectionSecretRepository[F[_]] {
  def save(secret: ConnectionSecret): F[Unit]
  def find(organizationId: UUID, id: UUID): F[Option[ConnectionSecret]]
  def delete(organizationId: UUID, id: UUID): F[Unit]
}
