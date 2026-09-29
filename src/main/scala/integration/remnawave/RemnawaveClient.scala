package ru.bitec.app.ops
package integration.remnawave

import cats.effect.IO
import domain.integration.{IntegrationBaseUrl, RemnawaveCredential}
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString
import scala.concurrent.duration.FiniteDuration

/** Uses the runtime's validated HTTP client; never opens a client or resolves DNS itself. */
final class RemnawaveClient(client: Client[IO], requestTimeout: FiniteDuration) {
  def probe(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential): IO[Unit] = {
    val uri = Uri.fromString(baseUrl.statsEndpoint.toASCIIString)
      .getOrElse(throw RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
    val request = Request[IO](Method.GET, uri).putHeaders(
      Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"))
    val authenticated = credential.caddyApiKey.fold(request)(key =>
      request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
    client.run(authenticated).use { response =>
      if (!response.status.isSuccess) IO.raiseError[Unit](RemnawaveErrors.status(response.status.code))
      else response.bodyText.take(65536).compile.string.flatMap { body =>
        if (RemnawaveModels.isStatsEnvelope(body)) IO.unit
        else IO.raiseError[Unit](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
      }
    }.timeout(requestTimeout).handleErrorWith {
      case error: application.integration.IntegrationError => IO.raiseError(error)
      case error => IO.raiseError(RemnawaveErrors.throwable(error))
    }
  }
}
