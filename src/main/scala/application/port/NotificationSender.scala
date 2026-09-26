package ru.bitec.app.ops
package application.port

import application.notification.NotificationEvent
import domain.notification.NotificationDeliveryTarget

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

/** One attempt to deliver: what happened, and where it is going.
  *
  * The event describes the incident and nothing else; the target says which destination this
  * attempt is for. They are separate because a sender needs both and neither belongs inside the
  * other: an event is not addressed to anyone, and a target says nothing about what happened.
  */
final case class NotificationSendRequest(
  event: NotificationEvent,
  target: NotificationDeliveryTarget
)

/** Delivers one event to an external system. Implementations live in the integration layer, or,
  * where a destination has to be resolved first, in the application layer on top of them.
  */
trait NotificationSender[F[_]] {
  def send(request: NotificationSendRequest): F[NotificationSendResult]
}
