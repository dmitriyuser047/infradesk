package ru.bitec.app.ops
package application.auth

import application.port.{AuthSessionRepository, TransactionRunner, UserAccountRepository}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.{AuthSession, AuthenticatedUser, UserAccount}

import java.time.Instant
import java.util.UUID

final case class LoginResult(user: AuthenticatedUser, rawToken: String)

final class Login[Tx[_]](
  users: UserAccountRepository[Tx],
  sessions: AuthSessionRepository[Tx],
  runner: TransactionRunner[IO, Tx],
  passwords: PasswordHasher,
  tokens: SessionTokens,
  ttlSeconds: Long
) {
  def execute(email: String, password: String): IO[Option[LoginResult]] =
    runner.run(users.findByEmail(UserAccount.normalizeEmail(email))).flatMap { account =>
      account.filter(_.isActive) match {
        case None => passwords.verifyDummy(password).as(None)
        case Some(user) =>
          passwords.verify(password, user.passwordHash).flatMap {
            case false => IO.pure(None)
            case true =>
              for {
                now <- IO(Instant.now())
                rawToken <- IO(tokens.generate())
                session = AuthSession(
                  UUID.randomUUID(), user.id, tokens.hash(rawToken), now,
                  now.plusSeconds(ttlSeconds), None
                )
                _ <- runner.run(sessions.create(session))
              } yield Some(LoginResult(AuthenticatedUser(user.id, user.email, user.displayName), rawToken))
          }
      }
    }
}
