package ru.bitec.app.ops
package application.scheduler

object SyncBackoffPolicy {
  val FirstFailureSeconds = 300L
  val SecondFailureSeconds = 900L
  val RepeatedFailureSeconds = 1800L

  def nextFailureCount(previous: Long): Long =
    if (previous == Long.MaxValue) Long.MaxValue else previous + 1

  def delaySeconds(intervalSeconds: Long, consecutiveFailures: Long): Long = {
    val backoff =
      if (consecutiveFailures <= 1) FirstFailureSeconds
      else if (consecutiveFailures == 2) SecondFailureSeconds
      else RepeatedFailureSeconds
    math.max(intervalSeconds, backoff)
  }
}
