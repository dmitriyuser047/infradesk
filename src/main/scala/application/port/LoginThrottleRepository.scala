package ru.bitec.app.ops
package application.port

import domain.auth.LoginThrottleScope

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** One throttle key to check or record against: a scope and the HMAC digest of its value. */
final case class LoginThrottleKey(scope: LoginThrottleScope, keyHash: String)

/** The login throttle, coordinated in PostgreSQL so several backend instances share one count.
  *
  * Enforcement lives in the database, never in JVM memory: the failure increment is a single
  * atomic statement, so two instances recording a failure at once cannot both read the same count
  * and write the same next value.
  */
trait LoginThrottleRepository[F[_]] {

  /** The latest moment any of these keys is blocked until, if one is blocked now; used to reject
    * an attempt before any password hashing. `None` means none is currently blocked.
    */
  def blockedUntil(keys: List[LoginThrottleKey], now: Instant): F[Option[Instant]]

  /** Atomically records one failure for a key: starts or continues its window, increments the
    * count, and sets a block when the count reaches the maximum. One statement, no read-then-write.
    */
  def recordFailure(
    key: LoginThrottleKey,
    now: Instant,
    window: FiniteDuration,
    maxFailures: Int,
    block: FiniteDuration
  ): F[Unit]

  /** Clears a key's throttle state after a successful login (used for the identifier, not the
    * source, so one success never erases an origin's spraying history).
    */
  def clear(key: LoginThrottleKey): F[Unit]

  /** Removes rows untouched since `staleBefore` that are not currently blocked; returns the count
    * deleted. The condition is in the DELETE itself, so a row that just took a failure is safe.
    */
  def deleteStale(staleBefore: Instant, now: Instant): F[Int]
}
