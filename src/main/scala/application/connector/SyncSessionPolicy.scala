package ru.bitec.app.ops
package application.connector

import scala.concurrent.duration._

/** How long a synchronization attempt may stay running before another one may retire it.
  *
  * The horizon belongs to the attempt, not to the clock: recovering earlier would let a second
  * discovery start next to a first one whose commands are still allowed to be running.
  */
object SyncSessionPolicy {

  /** The floor a fast connection still gets, and the horizon rows written before V21 carry. */
  val MinimumRecoverAfter: FiniteDuration = 15.minutes

  /** Room for connection setup, the completion transaction and clock skew between instances. */
  val SafetyMargin: FiniteDuration = 1.minute

  /** The boundary belongs to the session that holds it: a deadline of exactly now is not past. */
  def isRecoverable(recoverAfterAt: java.time.Instant, at: java.time.Instant): Boolean =
    recoverAfterAt.isBefore(at)

  def recoverAfter(attemptBudget: FiniteDuration): FiniteDuration =
    if (attemptBudget + SafetyMargin > MinimumRecoverAfter) attemptBudget + SafetyMargin
    else MinimumRecoverAfter
}
