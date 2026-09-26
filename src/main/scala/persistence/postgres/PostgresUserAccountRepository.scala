package ru.bitec.app.ops
package persistence.postgres

import application.port.UserAccountRepository
import cats.syntax.functor._
import domain.auth.UserAccount
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresUserAccountRepository extends UserAccountRepository[ConnectionIO] {
  private final case class UserRow(
    id: UUID,
    email: String,
    passwordHash: String,
    displayName: String,
    isActive: Boolean,
    createdAt: Instant,
    updatedAt: Instant
  ) {
    def toDomain: UserAccount =
      UserAccount(id, email, passwordHash, displayName, isActive, createdAt, updatedAt)
  }

  override def findByEmail(email: String): ConnectionIO[Option[UserAccount]] =
    sql"""select id, email, password_hash, display_name, is_active, created_at, updated_at
            from user_account where lower(email) = $email"""
      .query[UserRow].option.map(_.map(_.toDomain))

  override def findActiveById(id: UUID): ConnectionIO[Option[UserAccount]] =
    sql"""select id, email, password_hash, display_name, is_active, created_at, updated_at
            from user_account where id = $id and is_active = true"""
      .query[UserRow].option.map(_.map(_.toDomain))

  override def createIfMissing(user: UserAccount): ConnectionIO[Unit] =
    sql"""insert into user_account
            (id, email, password_hash, display_name, is_active, created_at, updated_at)
            values (${user.id}, ${user.email}, ${user.passwordHash}, ${user.displayName},
                    ${user.isActive}, ${user.createdAt}, ${user.updatedAt})
            on conflict do nothing""".update.run.void

  override def updatePasswordHash(
    id: UUID,
    passwordHash: String,
    updatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""update user_account
            set password_hash = $passwordHash, updated_at = $updatedAt
            where id = $id and is_active = true""".update.run.map(_ == 1)

  override def updateDisplayName(
    id: UUID,
    displayName: String,
    updatedAt: Instant
  ): ConnectionIO[Boolean] =
    sql"""update user_account
            set display_name = $displayName, updated_at = $updatedAt
            where id = $id and is_active = true""".update.run.map(_ == 1)
}
