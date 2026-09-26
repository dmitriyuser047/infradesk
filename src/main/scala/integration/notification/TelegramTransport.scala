package ru.bitec.app.ops
package integration.notification

import application.notification.NotificationMessage
import application.port.{NotificationSendResult, TelegramNotificationTransport}
import cats.effect.IO
import cats.syntax.all._
import io.circe.{Json, parser}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, Uri}

import java.util.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/** Sends a message through the Telegram Bot API.
  *
  * The endpoint belongs to Telegram, not to the channel: a person configures which chat to post
  * in and which bot to post as, never where the request goes. That is why this transport needs
  * no destination check — there is no user-controlled host to check.
  *
  * The bot token is part of the request path, so the request is never logged and never appears
  * in an error code: what comes back is a category, not a response.
  */
final class TelegramTransport(
  client: Client[IO],
  requestTimeout: FiniteDuration,
  baseUri: Uri = TelegramTransport.BotApi
) extends TelegramNotificationTransport[IO] {

  import TelegramTransport._

  override def send(
    botToken: String,
    chatId: String,
    message: NotificationMessage
  ): IO[NotificationSendResult] =
    client
      .run(request(botToken, chatId, message))
      .use(response => response.bodyText.compile.string.map(response.status -> _))
      .timeout(requestTimeout)
      .map { case (status, body) => classify(status, body) }
      .handleError(classifyError)

  private def request(botToken: String, chatId: String, message: NotificationMessage): Request[IO] =
    Request[IO](Method.POST, baseUri / s"bot$botToken" / "sendMessage")
      .withEntity(Json.obj(
        "chat_id" -> Json.fromString(chatId),
        "text" -> Json.fromString(message.text),
        // A message that is not marked up cannot be broken by a value that looks like markup.
        "disable_web_page_preview" -> Json.True
      ))
}

object TelegramTransport {

  val BotApi: Uri = Uri.unsafeFromString("https://api.telegram.org")

  val RateLimited: String = "TELEGRAM_RATE_LIMITED"
  val ServerError: String = "TELEGRAM_SERVER_ERROR"
  val AuthError: String = "TELEGRAM_AUTH_ERROR"
  val ApiError: String = "TELEGRAM_API_ERROR"
  val Timeout: String = "TELEGRAM_TIMEOUT"
  val ConnectionFailed: String = "TELEGRAM_CONNECTION_FAILED"
  val UnexpectedError: String = "TELEGRAM_UNEXPECTED_ERROR"

  /** Telegram answers 200 with `ok: false` for some refusals, so the body decides as well as the
    * status. Nothing from the body is kept beyond that decision.
    */
  def classify(status: Status, body: String): NotificationSendResult = {
    val accepted = parser.parse(body).toOption
      .flatMap(_.hcursor.get[Boolean]("ok").toOption)
      .getOrElse(false)

    status.code match {
      case code if code >= 200 && code < 300 && accepted => NotificationSendResult.Sent
      // A 2xx that says it did not work is the bot or the chat being wrong, not the network.
      case code if code >= 200 && code < 300 => NotificationSendResult.PermanentFailure(ApiError)
      case 429 => NotificationSendResult.RetryableFailure(RateLimited)
      case code if code >= 500 => NotificationSendResult.RetryableFailure(ServerError)
      case 401 | 403 => NotificationSendResult.PermanentFailure(AuthError)
      case _ => NotificationSendResult.PermanentFailure(ApiError)
    }
  }

  /** Transport problems say nothing about the message itself, so they are all worth repeating. */
  def classifyError(error: Throwable): NotificationSendResult = error match {
    case _: TimeoutException => NotificationSendResult.RetryableFailure(Timeout)
    case _: java.net.ConnectException => NotificationSendResult.RetryableFailure(ConnectionFailed)
    case _: java.io.IOException => NotificationSendResult.RetryableFailure(ConnectionFailed)
    case _ => NotificationSendResult.RetryableFailure(UnexpectedError)
  }
}
