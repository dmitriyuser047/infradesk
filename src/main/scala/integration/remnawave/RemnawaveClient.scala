package ru.bitec.app.ops
package integration.remnawave

import cats.effect.IO
import domain.integration.{IntegrationBaseUrl, RemnawaveCredential}
import org.http4s.{Header, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** Uses the runtime's validated HTTP client; never opens a client or resolves DNS itself. */
final class RemnawaveClient(client: Client[IO], requestTimeout: FiniteDuration) {
  import RemnawaveClient._

  def probe(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential): IO[Unit] = {
    val uri = Uri.fromString(baseUrl.statsEndpoint.toASCIIString)
      .getOrElse(throw RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
    val request = Request[IO](Method.GET, uri).putHeaders(
      Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"))
    val authenticated = credential.caddyApiKey.fold(request)(key =>
      request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
    client.run(authenticated).use { response =>
      if (!response.status.isSuccess) IO.raiseError[Unit](RemnawaveErrors.status(response.status.code))
      else boundedBody(response).flatMap {
        case Some(body) if RemnawaveModels.isStatsEnvelope(body) => IO.unit
        case _ => IO.raiseError[Unit](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
      }
    }.timeout(requestTimeout).handleErrorWith {
      case error: application.integration.IntegrationError => IO.raiseError(error)
      case error => IO.raiseError(RemnawaveErrors.throwable(error))
    }
  }
}

object RemnawaveClient {
  /** The most a stats response may carry. The real envelope is a few kilobytes. */
  val MaxResponseBytes: Int = 64 * 1024

  /** The body as strict UTF-8, or None when it is larger than the limit or not valid UTF-8.
    *
    * Counted in raw bytes, before decoding: at most MaxResponseBytes + 1 bytes are ever pulled from
    * the connection, so an oversized or endless body is refused without being held in memory.
    */
  private[remnawave] def boundedBody(response: Response[IO]): IO[Option[String]] =
    if (response.contentLength.exists(_ > MaxResponseBytes)) IO.pure(None)
    else response.body.take(MaxResponseBytes.toLong + 1).compile.to(Array).map { bytes =>
      if (bytes.length > MaxResponseBytes) None
      else Try(StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString).toOption
    }
}
