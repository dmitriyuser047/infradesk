package ru.bitec.app.ops
package support

import application.port.{LoginThrottleKey, LoginThrottleRepository}
import cats.effect.IO

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** An in-memory login throttle with the same semantics as the PostgreSQL one, for use-case tests.
  * The distributed atomicity itself is proven by the PostgreSQL integration test, not here.
  */
final class InMemoryLoginThrottle extends LoginThrottleRepository[IO] {
  private final case class State(failureCount: Int, windowStartedAt: Instant, blockedUntil: Option[Instant], updatedAt: Instant)
  private var rows: Map[(String, String), State] = Map.empty

  private def keyOf(key: LoginThrottleKey): (String, String) = (key.scope.code, key.keyHash)

  def failureCount(key: LoginThrottleKey): Int = synchronized(rows.get(keyOf(key)).map(_.failureCount).getOrElse(0))
  def isBlocked(key: LoginThrottleKey, now: Instant): Boolean =
    synchronized(rows.get(keyOf(key)).flatMap(_.blockedUntil).exists(_.isAfter(now)))
  def size: Int = synchronized(rows.size)

  override def blockedUntil(keys: List[LoginThrottleKey], now: Instant): IO[Option[Instant]] = IO(synchronized {
    keys.flatMap(k => rows.get(keyOf(k))).flatMap(_.blockedUntil).filter(_.isAfter(now)).maxOption
  })

  override def recordFailure(
    key: LoginThrottleKey,
    now: Instant,
    window: FiniteDuration,
    maxFailures: Int,
    block: FiniteDuration
  ): IO[Unit] = IO(synchronized {
    val windowStart = now.minusSeconds(window.toSeconds)
    val previous = rows.get(keyOf(key))
    val (count, windowStarted) = previous match {
      case Some(state) if state.windowStartedAt.isBefore(windowStart) => (1, now)
      case Some(state) => (state.failureCount + 1, state.windowStartedAt)
      case None => (1, now)
    }
    val blocked =
      if (count >= maxFailures) Some(now.plusSeconds(block.toSeconds)) else previous.flatMap(_.blockedUntil)
    rows = rows.updated(keyOf(key), State(count, windowStarted, blocked, now))
  })

  override def clear(key: LoginThrottleKey): IO[Unit] = IO(synchronized { rows = rows - keyOf(key) })

  override def deleteStale(staleBefore: Instant, now: Instant): IO[Int] = IO(synchronized {
    val (stale, fresh) = rows.partition { case (_, state) =>
      state.updatedAt.isBefore(staleBefore) && !state.blockedUntil.exists(_.isAfter(now))
    }
    rows = fresh
    stale.size
  })
}
