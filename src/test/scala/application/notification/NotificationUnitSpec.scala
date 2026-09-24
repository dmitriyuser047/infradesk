package ru.bitec.app.ops
package application.notification

import domain.incident.IncidentReason
import domain.notification.{NotificationChannel, NotificationDeliveryStatus, NotificationEventType}
import integration.notification.WebhookNotificationSender
import application.port.NotificationSendResult
import munit.FunSuite
import org.http4s.Status

import java.time.Instant
import java.util.UUID

final class NotificationUnitSpec extends FunSuite {

  test("notification codes round-trip and reject unknown values") {
    NotificationEventType.All.foreach(value =>
      assertEquals(NotificationEventType.fromCode(value.code), Right(value))
    )
    NotificationChannel.All.foreach(value =>
      assertEquals(NotificationChannel.fromCode(value.code), Right(value))
    )
    NotificationDeliveryStatus.All.foreach(value =>
      assertEquals(NotificationDeliveryStatus.fromCode(value.code), Right(value))
    )

    assertEquals(NotificationEventType.All.map(_.code), List("INCIDENT_OPENED", "INCIDENT_RESOLVED"))
    assertEquals(NotificationChannel.All.map(_.code), List("WEBHOOK"))
    assertEquals(NotificationDeliveryStatus.All.map(_.code), List("PENDING", "SENT", "DEAD"))

    assert(NotificationEventType.fromCode("INCIDENT_FLAPPED").isLeft)
    assert(NotificationChannel.fromCode("TELEGRAM").isLeft)
    assert(NotificationDeliveryStatus.fromCode("QUEUED").isLeft)
  }

  test("retry backoff doubles from thirty seconds and stops at an hour") {
    assertEquals(NotificationRetryPolicy.delaySeconds(1), 30L)
    assertEquals(NotificationRetryPolicy.delaySeconds(2), 60L)
    assertEquals(NotificationRetryPolicy.delaySeconds(3), 120L)
    assertEquals(NotificationRetryPolicy.delaySeconds(4), 240L)
    assertEquals(NotificationRetryPolicy.delaySeconds(8), 3600L)
    assertEquals(NotificationRetryPolicy.delaySeconds(1000), 3600L)
    assertEquals(NotificationRetryPolicy.delaySeconds(Long.MaxValue), 3600L)
  }

  test("http statuses are classified into sent, retryable and permanent") {
    List(200, 201, 202, 204).foreach(code =>
      assertEquals(WebhookNotificationSender.classify(status(code)), NotificationSendResult.Sent)
    )
    List(408 -> "HTTP_408", 429 -> "HTTP_429", 500 -> "HTTP_500", 503 -> "HTTP_503").foreach {
      case (code, expected) =>
        assertEquals(WebhookNotificationSender.classify(status(code)),
          NotificationSendResult.RetryableFailure(expected))
    }
    List(400 -> "HTTP_400", 401 -> "HTTP_401", 403 -> "HTTP_403", 404 -> "HTTP_404", 422 -> "HTTP_422")
      .foreach { case (code, expected) =>
        assertEquals(WebhookNotificationSender.classify(status(code)),
          NotificationSendResult.PermanentFailure(expected))
      }
    assertEquals(WebhookNotificationSender.classify(status(302)),
      NotificationSendResult.PermanentFailure("UNEXPECTED_RESPONSE"))
  }

  test("transport failures are retryable with bounded error codes") {
    assertEquals(
      WebhookNotificationSender.classifyError(new java.util.concurrent.TimeoutException("2 seconds")),
      NotificationSendResult.RetryableFailure("TIMEOUT")
    )
    assertEquals(
      WebhookNotificationSender.classifyError(new java.net.ConnectException("secret host detail")),
      NotificationSendResult.RetryableFailure("CONNECTION_FAILED")
    )
    assertEquals(
      WebhookNotificationSender.classifyError(new java.io.IOException("broken pipe")),
      NotificationSendResult.RetryableFailure("CONNECTION_FAILED")
    )
    assertEquals(
      WebhookNotificationSender.classifyError(new IllegalStateException("boom")),
      NotificationSendResult.RetryableFailure("UNEXPECTED_ERROR")
    )
  }

  test("the webhook payload carries the stable event contract") {
    val payload = WebhookNotificationSender.payload(event)

    assertEquals(payload.hcursor.get[String]("eventId"), Right(EventId.toString))
    assertEquals(payload.hcursor.get[String]("eventType"), Right("incident.opened"))
    assertEquals(payload.hcursor.get[String]("occurredAt"), Right("2026-09-24T10:00:00Z"))
    assertEquals(payload.hcursor.get[String]("organizationId"), Right(OrganizationId.toString))
    assertEquals(payload.hcursor.get[String]("resourceId"), Right(ResourceId.toString))
    assertEquals(payload.hcursor.get[String]("monitorRuleId"), Right(RuleId.toString))
    assertEquals(payload.hcursor.get[String]("incidentId"), Right(IncidentId.toString))
    assertEquals(payload.hcursor.get[String]("reason"), Right("THRESHOLD"))
    assertEquals(payload.asObject.map(_.keys.size), Some(8))

    assertEquals(
      WebhookNotificationSender.eventTypeCode(NotificationEventType.IncidentResolved),
      "incident.resolved"
    )
  }

  private def status(code: Int): Status = Status.fromInt(code).getOrElse(fail(s"invalid status $code"))

  private val EventId = UUID.fromString("c0000000-0000-0000-0000-000000000001")
  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-000000000001")
  private val ResourceId = UUID.fromString("70000000-0000-0000-0000-000000000001")
  private val RuleId = UUID.fromString("90000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")

  private val event = NotificationEvent(
    EventId,
    NotificationEventType.IncidentOpened,
    Instant.parse("2026-09-24T10:00:00Z"),
    OrganizationId,
    ResourceId,
    RuleId,
    IncidentId,
    IncidentReason.ThresholdViolation
  )
}
