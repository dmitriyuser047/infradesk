package ru.bitec.app.ops
package integration.remnawave

import cats.effect.IO
import cats.syntax.all._
import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationBaseUrl,
  IntegrationObjectType, IntegrationObservation, RemnawaveCredential}
import org.http4s.{Header, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import io.circe.parser.parse

/** Remnawave API client. Observation stays read-only; explicit actions use one POST each.
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

  /** A timeout or disconnect can happen after the write reached Remnawave. Never retry here. */
  def action(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential, externalId: String,
    action: IntegrationActionCode): IO[IntegrationActionRemoteOutcome] = {
    import IntegrationActionRemoteOutcome._
    val suffix = action match {
      case IntegrationActionCode.NodeEnable => "enable"
      case IntegrationActionCode.NodeDisable => "disable"
      case IntegrationActionCode.NodeRestart => "restart"
    }
    val expected = Try(java.util.UUID.fromString(externalId)).toOption
    if (!credential.valid) IO.pure(DefinitelyFailed("INTEGRATION_CREDENTIAL_INVALID"))
    else if (expected.isEmpty) IO.pure(DefinitelyFailed("INTEGRATION_ACTION_UNSUPPORTED"))
    else IO.fromEither(Uri.fromString(baseUrl.endpoint(s"api/nodes/${expected.get}/actions/$suffix").toASCIIString)
      .leftMap(_ => RemnawaveErrors.error("INTEGRATION_ACTION_UNSUPPORTED"))).flatMap { uri =>
      val body = if (action == IntegrationActionCode.NodeRestart) "{\"forceRestart\":false}" else ""
      val request = Request[IO](Method.POST, uri)
        .withEntity(body)
        .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"),
          Header.Raw(CIString("Content-Type"), "application/json"))
      val authenticated = credential.caddyApiKey.fold(request)(key =>
        request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
      client.run(authenticated).use { response =>
        val code = response.status.code
        if (code == 401) IO.pure(DefinitelyFailed("INTEGRATION_AUTH_FAILED"))
        else if (code == 403) IO.pure(DefinitelyFailed("INTEGRATION_FORBIDDEN"))
        else if (code == 404) IO.pure(DefinitelyFailed("INTEGRATION_ENDPOINT_NOT_FOUND"))
        else if (code == 400) IO.pure(DefinitelyFailed("INTEGRATION_ACTION_UNSUPPORTED"))
        else if (code == 429 || code >= 500) IO.pure(OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
        else if (action == IntegrationActionCode.NodeRestart && code == 202) IO.pure(Succeeded)
        else if (response.status.isSuccess && action != IntegrationActionCode.NodeRestart)
          boundedBody(response, MaxResponseBytes).map { value =>
            val valid = value.flatMap(parse(_).toOption).flatMap(_.hcursor.downField("response")
              .get[String]("uuid").toOption).exists(raw => Try(java.util.UUID.fromString(raw)).toOption == expected)
            if (valid) Succeeded else OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN")
          }
        else IO.pure(OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN"))
      }.timeout(requestTimeout).handleError(error => definiteBeforeWrite(error)
        .map(DefinitelyFailed.apply).getOrElse(OutcomeUnknown("INTEGRATION_ACTION_RESULT_UNKNOWN")))
    }
  }

  private def definiteBeforeWrite(error: Throwable): Option[String] = error match {
    case _: integration.http.OutboundDestinationRejected => Some("INTEGRATION_DESTINATION_NOT_ALLOWED")
    case _: java.net.UnknownHostException | _: java.net.ConnectException | _: java.net.NoRouteToHostException =>
      Some("INTEGRATION_UNREACHABLE")
    case _: javax.net.ssl.SSLHandshakeException => Some("INTEGRATION_TLS_ERROR")
    case other if other.getCause != null && (other.getCause ne other) => definiteBeforeWrite(other.getCause)
    case _ => None
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
