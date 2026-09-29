package ru.bitec.app.ops
package integration.remnawave

import cats.effect.IO
import cats.syntax.all._
import domain.integration.{IntegrationBaseUrl, IntegrationObjectType, IntegrationObservation, RemnawaveCredential}
import org.http4s.{Header, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** Read-only Remnawave API client. Uses the runtime's validated HTTP client; never opens a client
  * or resolves DNS itself, and never sends anything but GET.
  */
final class RemnawaveClient(client: Client[IO], requestTimeout: FiniteDuration,
  inventoryMaxResponseBytes: Int = RemnawaveClient.DefaultInventoryMaxResponseBytes,
  inventoryMaxObjects: Int = RemnawaveClient.DefaultInventoryMaxObjects) {
  import RemnawaveClient._

  def probe(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential): IO[Unit] =
    get(baseUrl.statsEndpoint, credential, MaxResponseBytes).flatMap { body =>
      if (RemnawaveModels.isStatsEnvelope(body)) IO.unit else invalidResponse[Unit]
    }

  /** One snapshot: exactly three listings, at most three requests at a time, whatever the number of
    * objects. Any failure fails the whole snapshot; nothing partial is ever returned.
    */
  def inventory(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential): IO[IntegrationObservation] = {
    def listing(path: String, decode: String => Option[List[domain.integration.ObservedIntegrationObject]]) =
      get(baseUrl.endpoint(path), credential, inventoryMaxResponseBytes).flatMap(body =>
        decode(body).fold(invalidResponse[List[domain.integration.ObservedIntegrationObject]])(IO.pure))
    (listing(NodesPath, RemnawaveInventory.nodes), listing(HostsPath, RemnawaveInventory.hosts),
      listing(ConfigProfilesPath, RemnawaveInventory.configProfiles)).parTupled.flatMap { case (nodes, hosts, profiles) =>
      val objects = nodes ++ hosts ++ profiles
      if (objects.size > inventoryMaxObjects) invalidResponse[IntegrationObservation]
      else IO.pure(IntegrationObservation(objects, IntegrationObjectType.All.toSet))
    }
  }

  private def get(target: URI, credential: RemnawaveCredential, maxBytes: Int): IO[String] =
    IO.fromEither(Uri.fromString(target.toASCIIString).leftMap(_ => RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE")))
      .flatMap { uri =>
        val request = Request[IO](Method.GET, uri).putHeaders(
          Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"))
        val authenticated = credential.caddyApiKey.fold(request)(key =>
          request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
        client.run(authenticated).use { response =>
          if (!response.status.isSuccess) IO.raiseError[String](RemnawaveErrors.status(response.status.code))
          else boundedBody(response, maxBytes).flatMap(_.fold(invalidResponse[String])(IO.pure))
        }
      }.timeout(requestTimeout).handleErrorWith {
        case error: application.integration.IntegrationError => IO.raiseError(error)
        case error => IO.raiseError(RemnawaveErrors.throwable(error))
      }

  private def invalidResponse[A]: IO[A] = IO.raiseError(RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE"))
}

object RemnawaveClient {
  /** The most a stats response may carry. The real envelope is a few kilobytes. */
  val MaxResponseBytes: Int = 64 * 1024
  val DefaultInventoryMaxResponseBytes: Int = 8 * 1024 * 1024
  val DefaultInventoryMaxObjects: Int = 10000
  val NodesPath = "api/nodes"
  val HostsPath = "api/hosts"
  val ConfigProfilesPath = "api/config-profiles"

  /** The body as strict UTF-8, or None when it is larger than the limit or not valid UTF-8.
    *
    * Counted in raw bytes, before decoding: at most `maxBytes + 1` bytes are ever pulled from the
    * connection, so an oversized or endless body is refused without being held in memory.
    */
  private[remnawave] def boundedBody(response: Response[IO], maxBytes: Int = MaxResponseBytes): IO[Option[String]] =
    if (response.contentLength.exists(_ > maxBytes)) IO.pure(None)
    else response.body.take(maxBytes.toLong + 1).compile.to(Array).map { bytes =>
      if (bytes.length > maxBytes) None
      else Try(StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString).toOption
    }
}
