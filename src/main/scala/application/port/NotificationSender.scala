package ru.bitec.app.ops
package application.port

import application.notification.NotificationEvent

/** How one attempt ended. The dispatcher needs to tell retrying apart from giving up, so this is
  * an explicit result instead of a boolean.
  */
sealed trait NotificationSendResult

object NotificationSendResult {
  case object Sent extends NotificationSendResult

  /** The receiver may accept the same event later: timeouts, refused connections, 5xx, 429. */
  final case class RetryableFailure(code: String) extends NotificationSendResult

  /** Repeating the request cannot help: most 4xx, unexpected redirects. */
  final case class PermanentFailure(code: String) extends NotificationSendResult
}

/** Delivers one event to an external system. Implementations live in the integration layer. */
trait NotificationSender[F[_]] {
  def send(event: NotificationEvent): F[NotificationSendResult]
}
