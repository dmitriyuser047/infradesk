package ru.bitec.app.ops
package application.auth

import application.port.{AuthSessionRepository, LoginThrottleKey, LoginThrottleRepository, TimeProvider, TransactionRunner, UserAccountRepository}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.auth.{AuthSession, AuthenticatedUser, LoginThrottleScope, UserAccount}

import java.time.Instant
import java.util.UUID

final case class LoginResult(user: AuthenticatedUser, rawToken: String)

/** How a login attempt ended, from the endpoint's point of view. Invalid credentials and a missing
  * account are one outcome on purpose, so nothing distinguishes them from outside.
  */
sealed trait LoginOutcome
object LoginOutcome {
  final case class Succeeded(result: LoginResult) extends LoginOutcome
  case object InvalidCredentials extends LoginOutcome
  final case class RateLimited(retryAfterSeconds: Long) extends LoginOutcome
}

/** Authenticates a login, throttled against brute force by shared PostgreSQL state.
  *
  * A currently-blocked identifier or source is rejected before any password hashing, so the
  * throttle protects the CPU as well as the account. Otherwise the account is looked up and the
  * password is verified — against the real hash for a known active account, against a fixed dummy
  * hash of the same cost for an unknown or inactive one, so both paths spend one BCrypt and neither
  * timing nor response reveals whether the account exists. A failed attempt records a failure for
  * the identifier and, when a trustworthy source is known, the source; both increments are single
  * atomic statements, so concurrent failures across instances are never lost. A success clears the
  * identifier's throttle but not the source's — one user signing in does not erase an origin's
  * spraying history.
  *
  * BCrypt runs between the short throttle and lookup transactions and the short write transaction,
  * never inside one, so a connection is never held for the duration of a hash.
  */
final class Login[Tx[_]: Monad](
  users: UserAccountRepository[Tx],
  sessions: AuthSessionRepository[Tx],
  throttle: LoginThrottleRepository[Tx],
  throttleHasher: LoginThrottleHasher,
  rateLimit: AuthRateLimitSettings,
  runner: TransactionRunner[IO, Tx],
  passwords: PasswordHasher,
  tokens: SessionTokens,
  time: TimeProvider[IO],
  ttlSeconds: Long
) {

  def execute(email: String, password: String, source: Option[String]): IO[LoginOutcome] = {
    val normalized = UserAccount.normalizeEmail(email)
    val identifierKey = LoginThrottleKey(LoginThrottleScope.Identifier, throttleHasher.hash(normalized))
    val sourceKey = source.filter(_.nonEmpty).map(value =>
      LoginThrottleKey(LoginThrottleScope.Source, throttleHasher.hash(value)))
    for {
      now <- time.now
      blocked <- runner.run(throttle.blockedUntil(identifierKey :: sourceKey.toList, now))
      outcome <- blocked match {
        case Some(until) => IO.pure(LoginOutcome.RateLimited(retryAfterSeconds(until, now)))
        case None => attempt(normalized, password, identifierKey, sourceKey)
      }
    } yield outcome
  }

  private def attempt(
    normalized: String,
    password: String,
    identifierKey: LoginThrottleKey,
    sourceKey: Option[LoginThrottleKey]
  ): IO[LoginOutcome] =
    runner.run(users.findByEmail(normalized)).flatMap { account =>
      account.filter(_.isActive) match {
        // No account: still spend one BCrypt against the dummy hash, then record the failure. The
        // identifier is throttled whether or not it names a real account.
        case None => passwords.verifyDummy(password) *> recordFailure(identifierKey, sourceKey)
        case Some(user) =>
          passwords.verify(password, user.passwordHash).flatMap {
            case false => recordFailure(identifierKey, sourceKey)
            case true => succeed(user, identifierKey)
          }
      }
    }

  private def recordFailure(
    identifierKey: LoginThrottleKey,
    sourceKey: Option[LoginThrottleKey]
  ): IO[LoginOutcome] =
    time.now.flatMap(at =>
      runner.run(
        throttle.recordFailure(identifierKey, at, rateLimit.identifierWindow,
          rateLimit.identifierMaxFailures, rateLimit.identifierBlock) *>
          sourceKey.traverse_(key => throttle.recordFailure(key, at, rateLimit.sourceWindow,
            rateLimit.sourceMaxFailures, rateLimit.sourceBlock))
      )
    // The attempt that trips the block still answers as an ordinary invalid credential; the next
    // attempt is the one the pre-check turns away.
    ).as(LoginOutcome.InvalidCredentials)

  private def succeed(user: UserAccount, identifierKey: LoginThrottleKey): IO[LoginOutcome] =
    for {
      now <- time.now
      rawToken <- IO(tokens.generate())
      session = AuthSession(UUID.randomUUID(), user.id, tokens.hash(rawToken), now,
        now.plusSeconds(ttlSeconds), None)
      _ <- runner.run(sessions.create(session) *> throttle.clear(identifierKey))
    } yield LoginOutcome.Succeeded(
      LoginResult(AuthenticatedUser(user.id, user.email, user.displayName), rawToken))

  /** Rounded up to the next half-minute, so it never reveals the exact remaining time. */
  private def retryAfterSeconds(until: Instant, now: Instant): Long = {
    val remaining = math.max(1L, until.getEpochSecond - now.getEpochSecond)
    ((remaining + 29) / 30) * 30
  }
}
