package ru.bitec.app.ops
package integration.notification

import integration.http.{OutboundDestinationRejected, OutboundDestinationUnresolvable}
import munit.FunSuite

final class NotificationDestinationFailuresSpec extends FunSuite {
  test("a forbidden or unresolvable destination classifies the exceptions transports throw") {
    assertEquals(
      NotificationDestinationFailures.classify(new OutboundDestinationRejected("X")),
      Some(application.port.NotificationSendResult.PermanentFailure("X")))
    assertEquals(
      NotificationDestinationFailures.classify(new OutboundDestinationUnresolvable("Y")),
      Some(application.port.NotificationSendResult.RetryableFailure("Y")))
    // Wrapped one level deep, as Jakarta Mail wraps a socket failure in a MessagingException.
    val wrapped = new jakarta.mail.MessagingException("failed", new OutboundDestinationRejected("Z"))
    assertEquals(NotificationDestinationFailures.classify(wrapped),
      Some(application.port.NotificationSendResult.PermanentFailure("Z")))
    assertEquals(NotificationDestinationFailures.classify(new RuntimeException("unrelated")), None)
  }
}
