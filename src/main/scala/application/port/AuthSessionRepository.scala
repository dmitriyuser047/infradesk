package ru.bitec.app.ops
package application.port

import domain.auth.{AuthSession, AuthenticatedUser}

import java.time.Instant

trait AuthSessionRepository[F[_]] {
  def create(session: AuthSession): F[Unit]
  def findAuthenticatedUserByTokenHash(tokenHash: String, now: Instant): F[Option[AuthenticatedUser]]
  def revokeByTokenHash(tokenHash: String, now: Instant): F[Unit]
}
