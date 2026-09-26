package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationEvent
import application.port.NotificationSendResult
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s._
import domain.incident.IncidentReason
import domain.notification.NotificationEventType
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes, Response, Status, Uri}

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The sender against a real local HTTP server: no outbound internet calls. */
final class WebhookNotificationSenderSpec extends FunSuite {

  test("a 2xx response delivers the event with its id in the body and in a header") {
    val captured = Ref.unsafe[IO, List[(Json, Option[String])]](List.empty)

    val result = withSender(respondWith(Status.Ok, captured), requestTimeout = 5.seconds) { sender =>
      sender.send(attempt)
    }
    val requests = captured.get.unsafeRunSync()
    val (payload, eventIdHeader) = requests.head

    assertEquals(result, NotificationSendResult.Sent)
    assertEquals(requests.size, 1)
    assertEquals(eventIdHeader, Some(EventId.toString))
    assertEquals(payload.hcursor.get[String]("eventId"), Right(EventId.toString))
    assertEquals(payload.hcursor.get[String]("eventType"), Right("incident.opened"))
    assertEquals(payload.hcursor.get[String]("incidentId"), Right(IncidentId.toString))
    assertEquals(payload.hcursor.get[String]("reason"), Right("THRESHOLD"))
  }

  test("an overloaded or failing receiver is retryable") {
    val tooManyRequests = withSender(respondWith(Status.TooManyRequests), 5.seconds)(_.send(attempt))
    val serverError = withSender(respondWith(Status.InternalServerError), 5.seconds)(_.send(attempt))

    assertEquals(tooManyRequests, NotificationSendResult.RetryableFailure("HTTP_429"))
    assertEquals(serverError, NotificationSendResult.RetryableFailure("HTTP_500"))
  }

  test("a receiver that rejects the request permanently is not retried") {
    val unauthorized = withSender(respondWith(Status.Unauthorized), 5.seconds)(_.send(attempt))
    val notFound = withSender(respondWith(Status.NotFound), 5.seconds)(_.send(attempt))

    assertEquals(unauthorized, NotificationSendResult.PermanentFailure("HTTP_401"))
    assertEquals(notFound, NotificationSendResult.PermanentFailure("HTTP_404"))
  }

  test("a receiver slower than the request timeout is retryable and does not hang") {
    val slow = HttpRoutes.of[IO] {
      case POST -> Root / "hook" => IO.sleep(5.seconds) *> Ok()
    }.orNotFound

    val result = withSender(slow, requestTimeout = 300.millis)(_.send(attempt))

    assertEquals(result, NotificationSendResult.RetryableFailure("TIMEOUT"))
  }

  test("a receiver that is not listening is retryable") {
    val result = EmberClientBuilder.default[IO].build.use { client =>
      // Nothing is bound on this port: the connection is refused rather than answered.
      new WebhookNotificationSender(client, Uri.unsafeFromString("http://127.0.0.1:1/hook"), 2.seconds)
        .send(attempt)
    }.unsafeRunSync()

    assertEquals(result, NotificationSendResult.RetryableFailure("CONNECTION_FAILED"))
  }

  private def respondWith(
    status: Status,
    captured: Ref[IO, List[(Json, Option[String])]] = Ref.unsafe[IO, List[(Json, Option[String])]](List.empty)
  ): HttpApp[IO] =
    HttpRoutes.of[IO] {
      case request @ POST -> Root / "hook" =>
        request.as[Json].flatMap { body =>
          val header = request.headers.headers
            .find(_.name == WebhookNotificationSender.EventIdHeader).map(_.value)
          captured.update(_ :+ ((body, header)))
        } *> IO.pure(Response[IO](status))
    }.orNotFound

  private def withSender[A](
    app: HttpApp[IO],
    requestTimeout: FiniteDuration
  )(use: WebhookNotificationSender => IO[A]): A = {
    val resources = for {
      server <- EmberServerBuilder.default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(app)
        .build
      client <- EmberClientBuilder.default[IO].build
    } yield (server.baseUri, client)

    resources.use { case (baseUri, client: Client[IO]) =>
      use(new WebhookNotificationSender(client, baseUri / "hook", requestTimeout))
    }.unsafeRunSync()
  }

  private val EventId = UUID.fromString("c0000000-0000-0000-0000-000000000001")
  private val IncidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001")

  private val event = NotificationEvent(
    EventId,
    NotificationEventType.IncidentOpened,
    Instant.parse("2026-09-24T10:00:00Z"),
    UUID.fromString("20000000-0000-0000-0000-000000000001"),
    UUID.fromString("70000000-0000-0000-0000-000000000001"),
    UUID.fromString("90000000-0000-0000-0000-000000000001"),
    IncidentId,
    IncidentReason.ThresholdViolation
  )

  /** The legacy webhook has one destination, so every attempt is aimed at the same place. */
  private val attempt = application.port.NotificationSendRequest(event,
    domain.notification.NotificationDeliveryTarget.LegacyWebhook)
}
