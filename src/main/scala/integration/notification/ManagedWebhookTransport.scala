package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationEvent
import application.port.{NotificationSendResult, WebhookNotificationTransport}
import cats.effect.IO
import cats.syntax.all._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Uri}

import scala.concurrent.duration.FiniteDuration

/** Posts an event to the URL of a configured webhook channel.
  *
  * The wire contract is the one the deployment's own webhook has always used — the same body and
  * the same stable event id in the header — so a receiver written for that one works for this
  * one. What differs is where it goes: a person configured it, so the destination is checked
  * before the request rather than trusted because it parses.
  *
  * The URL itself is a credential: it may carry a token in its path or query, so it never
  * reaches a log line, an error code or an exception message.
  */
final class ManagedWebhookTransport(
  client: Client[IO],
  policy: OutboundDestinationPolicy,
  requestTimeout: FiniteDuration
) extends WebhookNotificationTransport[IO] {

  override def post(url: String, event: NotificationEvent): IO[NotificationSendResult] =
    Uri.fromString(url).toOption.flatMap(uri => uri.host.map(host => (uri, host.value))) match {
      case None => permanent(ManagedWebhookTransport.InvalidUrl)
      case Some((uri, host)) => policy.check(host).flatMap {
        case Left(code) => permanent(code)
        case Right(_) => client
          .status(request(uri, event))
          .timeout(requestTimeout)
          .map(WebhookNotificationSender.classify)
          .handleError(WebhookNotificationSender.classifyError)
      }
    }

  private def request(uri: Uri, event: NotificationEvent): Request[IO] =
    Request[IO](Method.POST, uri)
      .withEntity(WebhookNotificationSender.payload(event))
      .putHeaders(Header.Raw(WebhookNotificationSender.EventIdHeader, event.eventId.toString))

  private def permanent(code: String): IO[NotificationSendResult] =
    IO.pure(NotificationSendResult.PermanentFailure(code))
}

object ManagedWebhookTransport {

  /** A URL that was accepted when the channel was saved and is not usable now: re-entering it is
    * the only thing that can help, so attempts stop.
    */
  val InvalidUrl: String = "WEBHOOK_URL_INVALID"
}
