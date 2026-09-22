package ru.bitec.app.ops
package application.port

import domain.auth.UserAccount

import java.util.UUID

trait UserAccountRepository[F[_]] {
  def findByEmail(email: String): F[Option[UserAccount]]
  def findActiveById(id: UUID): F[Option[UserAccount]]
  def createIfMissing(user: UserAccount): F[Unit]
}
