package ru.bitec.app.ops
package application.auth

import scala.concurrent.duration._

/** How the login endpoint throttles failures, per scope.
  *
  * Two independent budgets. The identifier budget is tight — a person mistyping their own password
  * a handful of times is normal, a hundred tries is not — and blocks for a fixed cooldown that
  * always expires, so it can never become a permanent account lockout. The source budget is much
  * larger, so an office or a NAT behind one address does not lock its own users out, while a single
  * origin spraying many accounts still trips it. Blocks are a fixed duration, not a growing one:
  * bounded by construction and self-healing.
  */
final case class AuthRateLimitSettings(
  identifierWindow: FiniteDuration,
  identifierMaxFailures: Int,
  identifierBlock: FiniteDuration,
  sourceWindow: FiniteDuration,
  sourceMaxFailures: Int,
  sourceBlock: FiniteDuration,
  /** Rows untouched for longer than this are stale and may be cleaned up (unless still blocked). */
  retention: FiniteDuration
)

object AuthRateLimitSettings {
  val default: AuthRateLimitSettings = AuthRateLimitSettings(
    identifierWindow = 15.minutes,
    identifierMaxFailures = 10,
    identifierBlock = 15.minutes,
    sourceWindow = 15.minutes,
    sourceMaxFailures = 50,
    sourceBlock = 15.minutes,
    retention = 1.day
  )
}
