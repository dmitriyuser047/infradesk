package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationEvent
import application.port.{NotificationSendResult, WebhookNotificationTransport}
import cats.effect.IO
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Uri}

import scala.concurrent.duration.FiniteDuration

/** Posts an event to the URL of a configured webhook channel.
  *
  * The wire contract is the one the deployment's own webhook has always used — the same body and
  * the same stable event id in the header — so a receiver written for that one works for this
  * one. What differs is where it goes: a person configured it, so `client` is built on a
  * `SocketGroup` that resolves and validates the destination itself, at the moment it connects,
  * rather than trusting a check made earlier against a name that could since answer differently.
  *
  * The URL itself is a credential: it may carry a token in its path or query, so it never
  * reaches a log line, an error code or an exception message.
  */
final class ManagedWebhookTransport(
  client: Client[IO],
  requestTimeout: FiniteDuration
) extends WebhookNotificationTransport[IO] {

  override def post(url: String, event: NotificationEvent): IO[NotificationSendResult] =
    // A URI without a host parses without error (http4s accepts a bare relative reference), but
    // there is nothing there to connect to: the same rejection as a string that fails to parse
    // at all, and for the same reason.
    Uri.fromString(url).toOption.filter(_.host.isDefined) match {
      case None => permanent(ManagedWebhookTransport.InvalidUrl)
      case Some(uri) =>
        client
          .status(request(uri, event))
          .timeout(requestTimeout)
          .map(WebhookNotificationSender.classify)
          .handleError(error =>
            OutboundDestinationPolicy.classify(error).getOrElse(WebhookNotificationSender.classifyError(error)))
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
