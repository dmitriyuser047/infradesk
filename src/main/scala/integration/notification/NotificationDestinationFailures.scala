package ru.bitec.app.ops
package integration.notification

import application.port.NotificationSendResult
import integration.http.{OutboundDestinationRejected, OutboundDestinationUnresolvable}

/** What a refused or unresolvable outbound destination means for a notification delivery: a
  * forbidden destination will not become allowed by retrying, an unresolved one may.
  */
object NotificationDestinationFailures {
  def classify(error: Throwable): Option[NotificationSendResult] = error match {
    case rejected: OutboundDestinationRejected => Some(NotificationSendResult.PermanentFailure(rejected.code))
    case unresolved: OutboundDestinationUnresolvable => Some(NotificationSendResult.RetryableFailure(unresolved.code))
    case _ => Option(error.getCause).filter(_ ne error).flatMap(classify)
  }
}
