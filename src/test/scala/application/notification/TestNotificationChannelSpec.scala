package ru.bitec.app.ops
package application.notification

import application.port.{NotificationSendRequest, NotificationSendResult, NotificationSender}
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.incident.IncidentReason
import domain.notification.{NotificationChannelType, NotificationDeliveryTarget, NotificationEventType}
import munit.FunSuite

import java.util.UUID

/** The button behind "test this channel": what it sends, and through what.
  *
  * `ManagedNotificationSenderSpec` already covers everything about resolving a channel — which
  * transport its current settings choose, a rotated credential, a switched-off channel, tenant
  * isolation. What is specific to this wrapper is only the request it builds: this is what these
  * tests are about, with a recording double standing in for the sender itself.
  */
final class TestNotificationChannelSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ChannelId = UUID.fromString("30000000-0000-0000-0000-000000000001")

  test("sends one event addressed to exactly the channel asked for") {
    val recording = new RecordingSender(NotificationSendResult.Sent)
    val testChannel = new TestNotificationChannel(recording)

    val result = testChannel.execute(OrganizationId, ChannelId, NotificationChannelType.Telegram)
      .unsafeRunSync()

    assertEquals(result, NotificationSendResult.Sent)
    assertEquals(recording.requests.size, 1)
    assertEquals(recording.requests.head.target,
      NotificationDeliveryTarget.Managed(ChannelId, NotificationChannelType.Telegram))
    assertEquals(recording.requests.head.event.organizationId, OrganizationId)
  }

  test("the event is unmistakably synthetic: nil identifiers no real incident could carry") {
    val recording = new RecordingSender(NotificationSendResult.Sent)
    new TestNotificationChannel(recording)
      .execute(OrganizationId, ChannelId, NotificationChannelType.Email)
      .unsafeRunSync()

    val event = recording.requests.head.event
    val nil = new UUID(0L, 0L)
    assertEquals(event.resourceId, nil)
    assertEquals(event.monitorRuleId, nil)
    assertEquals(event.incidentId, nil)
    assertEquals(event.eventType, NotificationEventType.IncidentOpened)
    assertEquals(event.reason, IncidentReason.ThresholdViolation)
    // Two calls are two distinguishable events, not the same one repeated.
    new TestNotificationChannel(recording)
      .execute(OrganizationId, ChannelId, NotificationChannelType.Email)
      .unsafeRunSync()
    assertEquals(recording.requests.map(_.event.eventId).distinct.size, 2)
  }

  test("whatever the sender answers is what this returns, unchanged") {
    for (result <- List(
      NotificationSendResult.Sent,
      NotificationSendResult.RetryableFailure("SMTP_TIMEOUT"),
      NotificationSendResult.PermanentFailure("CHANNEL_CREDENTIAL_INVALID")
    )) {
      val recording = new RecordingSender(result)
      val actual = new TestNotificationChannel(recording)
        .execute(OrganizationId, ChannelId, NotificationChannelType.Webhook)
        .unsafeRunSync()
      assertEquals(actual, result)
    }
  }

  private final class RecordingSender(result: NotificationSendResult) extends NotificationSender[IO] {
    @volatile var requests: List[NotificationSendRequest] = Nil
    override def send(request: NotificationSendRequest): IO[NotificationSendResult] =
      IO { requests = requests :+ request } *> IO.pure(result)
  }
}
