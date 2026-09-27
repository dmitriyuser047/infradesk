package ru.bitec.app.ops
package persistence.postgres

import application.port.AuthSessionRepository
import cats.syntax.functor._
import domain.auth.{AuthSession, AuthenticatedSession, AuthenticatedUser, UserSessionSummary}
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

  override def findAuthenticatedSessionByTokenHash(
    tokenHash: String,
    now: Instant
  ): ConnectionIO[Option[AuthenticatedSession]] =
    sql"""select s.id, u.id, u.email, u.display_name
            from auth_session s
            join user_account u on u.id = s.user_id
            where s.token_hash = $tokenHash
              and s.revoked_at is null
              and s.expires_at > $now
              and u.is_active = true"""
      .query[(UUID, UUID, String, String)]
      .option.map(_.map { case (sessionId, id, email, displayName) =>
        AuthenticatedSession(AuthenticatedUser(id, email, displayName), sessionId)
      })

  override def revokeByTokenHash(tokenHash: String, now: Instant): ConnectionIO[Unit] =
    sql"""update auth_session set revoked_at = $now
            where token_hash = $tokenHash and revoked_at is null""".update.run.void

  override def listActiveByUser(userId: UUID, now: Instant): ConnectionIO[List[UserSessionSummary]] =
    sql"""select id, created_at, expires_at
            from auth_session
            where user_id = $userId
              and revoked_at is null
              and expires_at > $now
            order by created_at desc, id desc limit 100"""
      .query[(UUID, Instant, Instant)]
      .to[List].map(_.map { case (id, createdAt, expiresAt) => UserSessionSummary(id, createdAt, expiresAt) })

  override def revokeByIdForUser(userId: UUID, sessionId: UUID, now: Instant): ConnectionIO[Boolean] =
    sql"""update auth_session set revoked_at = $now
            where id = $sessionId and user_id = $userId and revoked_at is null and expires_at > $now"""
      .update.run.map(_ == 1)

  override def revokeOthersForUser(userId: UUID, exceptSessionId: UUID, now: Instant): ConnectionIO[Int] =
    sql"""update auth_session set revoked_at = $now
            where user_id = $userId and id <> $exceptSessionId and revoked_at is null and expires_at > $now"""
      .update.run

  override def revokeAllForUser(userId: UUID, now: Instant): ConnectionIO[Int] =
    sql"""update auth_session set revoked_at = $now
            where user_id = $userId and revoked_at is null and expires_at > $now"""
      .update.run
}
