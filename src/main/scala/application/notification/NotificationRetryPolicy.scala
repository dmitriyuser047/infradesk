package ru.bitec.app.ops
package application.notification

/** Deterministic exponential backoff: 30s, 60s, 120s, 240s ... capped at one hour.
  *
  * `attemptCount` is the number of attempts already made, so the delay after the first failure is
  * the base delay. No jitter in this version: the schedule stays reproducible in tests.
  */
object NotificationRetryPolicy {

  val BaseDelaySeconds: Long = 30L
  val MaxDelaySeconds: Long = 3600L

  def delaySeconds(attemptCount: Long): Long =
    if (attemptCount <= 1) BaseDelaySeconds
    else {
      val exponent = math.min(attemptCount - 1, 32L).toInt
      val delay = BaseDelaySeconds * (1L << exponent)
      if (delay <= 0 || delay > MaxDelaySeconds) MaxDelaySeconds else delay
    }
}
