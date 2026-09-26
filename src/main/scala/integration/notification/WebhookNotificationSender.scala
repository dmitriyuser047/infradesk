package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationEvent
import application.port.{NotificationSendRequest, NotificationSendResult, NotificationSender}
import cats.effect.IO
import cats.syntax.all._
import domain.notification.NotificationEventType
import io.circe.Json
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Status, Uri}
import org.typelevel.ci.CIString

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Posts one incident event to the webhook the deployment configures through its environment.
  *
  * This is the destination that predates configured channels: one URL for the whole deployment,
  * known at startup, so the attempt carries no destination of its own and its target is not
  * consulted.
  *
  * The request carries the stable event id both in the body and in a header, so a receiver can
  * deduplicate the repeats that at-least-once delivery implies. Only the status code is read: the
  * response body is drained and never stored or logged.
  */
final class WebhookNotificationSender(
  client: Client[IO],
  url: Uri,
  requestTimeout: FiniteDuration
) extends NotificationSender[IO] {

  import WebhookNotificationSender._

  override def send(request: NotificationSendRequest): IO[NotificationSendResult] =
    client
      .status(httpRequest(request.event))
      .timeout(requestTimeout)
      .map(classify)
      .handleError(classifyError)

  private def httpRequest(event: NotificationEvent): Request[IO] =
    Request[IO](Method.POST, url)
      .withEntity(payload(event))
      .putHeaders(Header.Raw(EventIdHeader, event.eventId.toString))
}

object WebhookNotificationSender {

  val EventIdHeader: CIString = CIString("X-InfraDesk-Event-Id")

  /** The stable wire contract of the webhook. */
  def eventTypeCode(eventType: NotificationEventType): String =
    eventType match {
      case NotificationEventType.IncidentOpened => "incident.opened"
      case NotificationEventType.IncidentResolved => "incident.resolved"
    }

  def payload(event: NotificationEvent): Json =
    Json.obj(
      "eventId" -> Json.fromString(event.eventId.toString),
      "eventType" -> Json.fromString(eventTypeCode(event.eventType)),
      "occurredAt" -> Json.fromString(event.occurredAt.toString),
      "organizationId" -> Json.fromString(event.organizationId.toString),
      "resourceId" -> Json.fromString(event.resourceId.toString),
      "monitorRuleId" -> Json.fromString(event.monitorRuleId.toString),
      "incidentId" -> Json.fromString(event.incidentId.toString),
      "reason" -> Json.fromString(event.reason.code)
    )

  /** 2xx accepts the event; overload and server-side problems are worth repeating; anything else
    * the receiver rejected outright will be rejected again.
    */
  def classify(status: Status): NotificationSendResult =
    status.code match {
      case code if code >= 200 && code < 300 => NotificationSendResult.Sent
      case 408 | 429 => NotificationSendResult.RetryableFailure(s"HTTP_${status.code}")
      case code if code >= 500 => NotificationSendResult.RetryableFailure(s"HTTP_$code")
      // Redirects are not followed: the configured URL is expected to be the final endpoint.
      case code if code >= 300 && code < 400 => NotificationSendResult.PermanentFailure("UNEXPECTED_RESPONSE")
      case code => NotificationSendResult.PermanentFailure(s"HTTP_$code")
    }

  /** Transport problems say nothing about the event itself, so they are all retryable. */
  def classifyError(error: Throwable): NotificationSendResult =
    error match {
      case _: TimeoutException => NotificationSendResult.RetryableFailure("TIMEOUT")
      case _: java.net.ConnectException => NotificationSendResult.RetryableFailure("CONNECTION_FAILED")
      case _: java.io.IOException => NotificationSendResult.RetryableFailure("CONNECTION_FAILED")
      case _ => NotificationSendResult.RetryableFailure("UNEXPECTED_ERROR")
    }
}
