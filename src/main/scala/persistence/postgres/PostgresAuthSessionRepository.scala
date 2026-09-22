package ru.bitec.app.ops
package persistence.postgres

import application.port.AuthSessionRepository
import cats.syntax.functor._
import domain.auth.{AuthSession, AuthenticatedUser}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresAuthSessionRepository extends AuthSessionRepository[ConnectionIO] {
  override def create(session: AuthSession): ConnectionIO[Unit] =
    sql"""insert into auth_session
            (id, user_id, token_hash, created_at, expires_at, revoked_at)
            values (${session.id}, ${session.userId}, ${session.tokenHash},
                    ${session.createdAt}, ${session.expiresAt}, ${session.revokedAt})"""
      .update.run.void

  override def findAuthenticatedUserByTokenHash(
    tokenHash: String,
    now: Instant
  ): ConnectionIO[Option[AuthenticatedUser]] =
    sql"""select u.id, u.email, u.display_name
            from auth_session s
            join user_account u on u.id = s.user_id
            where s.token_hash = $tokenHash
              and s.revoked_at is null
              and s.expires_at > $now
              and u.is_active = true"""
      .query[(UUID, String, String)]
      .option.map(_.map { case (id, email, displayName) => AuthenticatedUser(id, email, displayName) })

  override def revokeByTokenHash(tokenHash: String, now: Instant): ConnectionIO[Unit] =
    sql"""update auth_session set revoked_at = $now
            where token_hash = $tokenHash and revoked_at is null""".update.run.void
}
