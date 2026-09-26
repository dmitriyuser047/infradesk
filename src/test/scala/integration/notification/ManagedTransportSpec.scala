package ru.bitec.app.ops
package integration.notification

import application.notification.{NotificationEvent, NotificationMessage}
import application.port.NotificationSendResult
import cats.effect.{IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s.{Host, Port}
import domain.incident.IncidentReason
import domain.notification.NotificationEventType
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.dsl.io._
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpRoutes, Response, Status, Uri}

import java.net.InetAddress
import java.time.Instant
import java.util.UUID
import scala.concurrent.duration._

/** The two HTTP transports, against a server this spec runs itself.
  *
  * A real server rather than a mocked client: what is being checked is the request that leaves
  * the process and how an answer is classified, which a stubbed client would not show. The
  * webhook client is built exactly as production builds it, on a `ValidatingSocketGroup`, so
  * these tests exercise the same destination pinning production requests go through — with a
  * lenient test policy standing in for the real one where a test needs to reach its own local
  * server on the loopback interface, which the real policy can never allow.
  */
final class ManagedTransportSpec extends FunSuite {

  override val munitTimeout: Duration = 60.seconds

  private val EventId = UUID.fromString("c0000000-0000-0000-0000-000000000001")

  private val event = NotificationEvent(
    eventId = EventId,
    eventType = NotificationEventType.IncidentOpened,
    occurredAt = Instant.parse("2026-09-25T10:00:00Z"),
    organizationId = UUID.fromString("20000000-0000-0000-0000-000000000001"),
    resourceId = UUID.fromString("70000000-0000-0000-0000-000000000001"),
    monitorRuleId = UUID.fromString("90000000-0000-0000-0000-000000000001"),
    incidentId = UUID.fromString("a0000000-0000-0000-0000-000000000001"),
    reason = IncidentReason.ThresholdViolation
  )

  private val message = NotificationMessage.of(event)

  test("a webhook receives the event, the stable event id and nothing else") {
    val seen = Ref.unsafe[IO, List[(String, Json)]](Nil)
    withServer(HttpRoutes.of[IO] {
      case request @ POST -> Root / "hook" / "token" =>
        request.as[Json].flatMap(body => seen.update(_ :+
          (request.headers.get(WebhookNotificationSender.EventIdHeader)
            .map(_.head.value).getOrElse("") -> body))) *> Ok()
    }) { (client, base) =>
      val transport = new ManagedWebhookTransport(client, 5.seconds)
      for {
        result <- transport.post((base / "hook" / "token").renderString, event)
        recorded <- seen.get
      } yield IO {
        assertEquals(result, NotificationSendResult.Sent)
        assertEquals(recorded.map(_._1), List(EventId.toString))
        // The body is the contract the deployment webhook already has.
        assertEquals(recorded.map(_._2), List(WebhookNotificationSender.payload(event)))
      }
    }
  }

  test("a webhook answer decides whether the event is worth repeating") {
    val answers = List(
      Status.Accepted -> NotificationSendResult.Sent,
      Status.TooManyRequests -> NotificationSendResult.RetryableFailure("HTTP_429"),
      Status.ServiceUnavailable -> NotificationSendResult.RetryableFailure("HTTP_503"),
      Status.BadRequest -> NotificationSendResult.PermanentFailure("HTTP_400"),
      Status.NotFound -> NotificationSendResult.PermanentFailure("HTTP_404")
    )

    for ((status, expected) <- answers)
      withServer(HttpRoutes.of[IO] { case POST -> Root / "hook" => IO.pure(Response[IO](status)) }) {
        (client, base) =>
          new ManagedWebhookTransport(client, 5.seconds)
            .post((base / "hook").renderString, event)
            .map(result => IO(assertEquals(result, expected, clues(status.code))))
      }
  }

  test("a webhook that does not answer in time is worth repeating, and says only that") {
    withServer(HttpRoutes.of[IO] { case POST -> Root / "hook" => IO.sleep(2.seconds) *> Ok() }) {
      (client, base) =>
        new ManagedWebhookTransport(client, 200.millis)
          .post((base / "hook").withQueryParam("t", "secret-token").renderString, event)
          .map(result => IO {
            assertEquals(result, NotificationSendResult.RetryableFailure("TIMEOUT"))
            // The URL may carry a token, so nothing of it survives into the outcome.
            assert(!result.toString.contains("secret-token"))
          })
    }
  }

  test("a webhook pointing at the deployment itself is refused before any request is made") {
    val reached = Ref.unsafe[IO, Int](0)
    withServer(HttpRoutes.of[IO] { case POST -> Root / "hook" => reached.update(_ + 1) *> Ok() }) {
      (_, base) =>
        // The lenient test policy the fixture's own client is built on would let this reach the
        // server; this test is about the real one, against a server that really is on the
        // loopback interface, which no deployment may ever reach.
        strictClient.use { realClient =>
          val transport = new ManagedWebhookTransport(realClient, 5.seconds)
          for {
            result <- transport.post((base / "hook").renderString, event)
            calls <- reached.get
          } yield IO {
            assertEquals(result,
              NotificationSendResult.PermanentFailure(OutboundDestinationPolicy.Forbidden))
            assertEquals(calls, 0)
          }
        }
    }
  }

  test("a webhook URL that is not one is refused rather than attempted") {
    withServer(HttpRoutes.of[IO] { case POST -> Root / "hook" => Ok() }) { (client, _) =>
      val transport = new ManagedWebhookTransport(client, 5.seconds)
      transport.post("not-a-url", event).map(result =>
        IO(assertEquals(result,
          NotificationSendResult.PermanentFailure(ManagedWebhookTransport.InvalidUrl))))
    }
  }

  test("Telegram is asked to post the message in the chat, as the bot") {
    val seen = Ref.unsafe[IO, List[(String, Json)]](Nil)
    withServer(HttpRoutes.of[IO] {
      case request @ POST -> Root / token / "sendMessage" =>
        request.as[Json].flatMap(body => seen.update(_ :+ (token -> body))) *>
          Ok(Json.obj("ok" -> Json.True))
    }) { (client, base) =>
      val transport = new TelegramTransport(client, 5.seconds, base)
      for {
        result <- transport.send("123:secret-bot-token", "-100777", message)
        recorded <- seen.get
      } yield IO {
        assertEquals(result, NotificationSendResult.Sent)
        assertEquals(recorded.map(_._1), List("bot123:secret-bot-token"))
        assertEquals(recorded.map(_._2.hcursor.get[String]("chat_id")), List(Right("-100777")))
        assertEquals(recorded.map(_._2.hcursor.get[String]("text")), List(Right(message.text)))
      }
    }
  }

  test("Telegram saying it did not work is not the same as the network saying nothing") {
    val answers = List(
      (Status.Ok, Json.obj("ok" -> Json.False), NotificationSendResult.PermanentFailure(
        TelegramTransport.ApiError)),
      (Status.TooManyRequests, Json.obj("ok" -> Json.False), NotificationSendResult
        .RetryableFailure(TelegramTransport.RateLimited)),
      (Status.BadGateway, Json.obj("ok" -> Json.False), NotificationSendResult.RetryableFailure(
        TelegramTransport.ServerError)),
      (Status.Unauthorized, Json.obj("ok" -> Json.False), NotificationSendResult.PermanentFailure(
        TelegramTransport.AuthError)),
      (Status.Forbidden, Json.obj("ok" -> Json.False), NotificationSendResult.PermanentFailure(
        TelegramTransport.AuthError)),
      (Status.BadRequest, Json.obj("ok" -> Json.False, "description" ->
        Json.fromString("chat not found")), NotificationSendResult.PermanentFailure(
        TelegramTransport.ApiError))
    )

    for ((status, body, expected) <- answers)
      withServer(HttpRoutes.of[IO] {
        case POST -> Root / _ / "sendMessage" => IO.pure(Response[IO](status).withEntity(body))
      }) { (client, base) =>
        new TelegramTransport(client, 5.seconds, base)
          .send("123:abc", "-100777", message)
          .map(result => IO {
            assertEquals(result, expected, clues(status.code))
            // Whatever Telegram said, the token is not in what comes back.
            assert(!result.toString.contains("123:abc"))
          })
      }
  }

  test("Telegram not answering in time is worth repeating") {
    withServer(HttpRoutes.of[IO] {
      case POST -> Root / _ / "sendMessage" => IO.sleep(2.seconds) *> Ok(Json.obj())
    }) { (client, base) =>
      new TelegramTransport(client, 200.millis, base)
        .send("123:abc", "-100777", message)
        .map(result => IO(assertEquals(result,
          NotificationSendResult.RetryableFailure(TelegramTransport.Timeout))))
    }
  }

  /** Resolves normally and forbids nothing: what a webhook client is built on in production
    * minus the SSRF refusal, so a test can reach its own local server. The refusal itself has
    * its own tests, against the real policy.
    */
  private object Lenient extends OutboundDestinationPolicy {
    override def pin(host: String): IO[Either[OutboundDestinationFailure, InetAddress]] =
      IO.blocking(InetAddress.getAllByName(host).headOption
        .toRight(OutboundDestinationFailure.ResolutionFailed(OutboundDestinationPolicy.ResolutionFailed)))
  }

  /** The real policy, the one every deployment actually runs with. */
  private def strictClient: Resource[IO, Client[IO]] =
    EmberClientBuilder.default[IO]
      .withSocketGroup(new ValidatingSocketGroup(fs2.io.net.Network[IO],
        OutboundDestinationPolicy.resolving(allowPrivateNetworks = true)))
      .build

  private def withServer(routes: HttpRoutes[IO])(body: (Client[IO], Uri) => IO[IO[Unit]]): Unit =
    server(routes).use { case (client, base) => body(client, base).flatten }.unsafeRunSync()

  private def server(routes: HttpRoutes[IO]): Resource[IO, (Client[IO], Uri)] =
    for {
      running <- EmberServerBuilder
        .default[IO]
        .withHost(Host.fromString("127.0.0.1").get)
        // Port zero: the operating system picks, so parallel suites do not collide.
        .withPort(Port.fromInt(0).get)
        .withHttpApp(routes.orNotFound)
        .build
      // Built the same way production builds the webhook client: on a ValidatingSocketGroup,
      // wrapping fs2's default socket group, so these tests go through the real pinning
      // mechanism and not around it.
      client <- EmberClientBuilder.default[IO]
        .withSocketGroup(new ValidatingSocketGroup(fs2.io.net.Network[IO], Lenient))
        .build
    } yield (client, Uri.unsafeFromString(
      s"http://${running.address.getHostString}:${running.address.getPort}"))
}
