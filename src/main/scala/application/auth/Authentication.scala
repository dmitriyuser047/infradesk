package ru.bitec.app.ops
package application.auth

import application.port.{AuthSessionRepository, MyOrganization, OrganizationMembershipRepository, TransactionRunner}
import cats.effect.IO
import domain.auth.AuthenticatedUser

import java.time.Instant
import java.util.UUID

final class Authentication[Tx[_]](
  sessions: AuthSessionRepository[Tx],
  memberships: OrganizationMembershipRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  tokens: SessionTokens
) {
  def authenticate(rawToken: String): IO[Option[AuthenticatedUser]] =
    IO(Instant.now()).flatMap(now =>
      runner.run(sessions.findAuthenticatedUserByTokenHash(tokens.hash(rawToken), now))
    )

  def revoke(rawToken: String): IO[Unit] =
    IO(Instant.now()).flatMap(now => runner.run(sessions.revokeByTokenHash(tokens.hash(rawToken), now)))

  def hasOrganizationAccess(userId: UUID, organizationId: UUID): IO[Boolean] =
    runner.run(memberships.hasActiveMembership(userId, organizationId))

  def organizations(userId: UUID): IO[List[MyOrganization]] =
    runner.run(memberships.listActiveOrganizations(userId))
}
