package ru.bitec.app.ops
package persistence.postgres

import application.port.{LoginThrottleKey, LoginThrottleRepository}
import cats.syntax.applicative._
import cats.syntax.functor._
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

final class PostgresLoginThrottleRepository extends LoginThrottleRepository[ConnectionIO] {

  override def blockedUntil(keys: List[LoginThrottleKey], now: Instant): ConnectionIO[Option[Instant]] =
    keys match {
      case Nil => Option.empty[Instant].pure[ConnectionIO]
      case _ =>
        // One round trip: the latest moment any named key stays blocked, or nothing if none is.
        val matches = keys
          .map(key => fr"(scope = ${key.scope.code} and key_hash = ${key.keyHash})")
          .reduce((left, right) => left ++ fr" or " ++ right)
        (fr"select max(blocked_until) from auth_login_throttle where blocked_until > $now and (" ++
          matches ++ fr")")
          .query[Option[Instant]].unique
    }

  /** A single atomic statement: the window is started or continued, the count is incremented, and
    * a block is set the moment the count reaches the maximum. The right-hand sides read the row's
    * pre-update values, so the new count is computed once and compared to the threshold inline —
    * two instances recording a failure at once each apply their own increment and neither is lost.
    */
  override def recordFailure(
    key: LoginThrottleKey,
    now: Instant,
    window: FiniteDuration,
    maxFailures: Int,
    block: FiniteDuration
  ): ConnectionIO[Unit] = {
    val windowStart = now.minusSeconds(window.toSeconds)
    val blockUntil = now.plusSeconds(block.toSeconds)
    sql"""
      insert into auth_login_throttle
        (scope, key_hash, failure_count, window_started_at, last_failure_at, blocked_until, updated_at)
      values (
        ${key.scope.code},
        ${key.keyHash},
        1,
        $now,
        $now,
        case when 1 >= $maxFailures then $blockUntil else null end,
        $now
      )
      on conflict (scope, key_hash) do update set
        failure_count = case
          when auth_login_throttle.window_started_at < $windowStart then 1
          else auth_login_throttle.failure_count + 1 end,
        window_started_at = case
          when auth_login_throttle.window_started_at < $windowStart then $now
          else auth_login_throttle.window_started_at end,
        last_failure_at = $now,
        blocked_until = case
          when (case when auth_login_throttle.window_started_at < $windowStart then 1
                     else auth_login_throttle.failure_count + 1 end) >= $maxFailures
            then $blockUntil
          else auth_login_throttle.blocked_until end,
        updated_at = $now
    """.update.run.void
  }

  override def clear(key: LoginThrottleKey): ConnectionIO[Unit] =
    sql"delete from auth_login_throttle where scope = ${key.scope.code} and key_hash = ${key.keyHash}"
      .update.run.void

  override def deleteStale(staleBefore: Instant, now: Instant): ConnectionIO[Int] =
    sql"""delete from auth_login_throttle
            where updated_at < $staleBefore
              and (blocked_until is null or blocked_until <= $now)""".update.run
}
