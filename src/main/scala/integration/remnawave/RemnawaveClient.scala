package ru.bitec.app.ops
package integration.remnawave

import cats.effect.IO
import cats.syntax.all._
import application.integration.IntegrationConfigProfileDocument
import domain.configuration.CanonicalJson
import domain.integration.{IntegrationActionCode, IntegrationActionRemoteOutcome, IntegrationBaseUrl,
  IntegrationObjectType, IntegrationObservation, RemnawaveCredential,NodeCreateIntent}
import org.http4s.{Header, Method, Request, Response, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import io.circe.parser.parse
import io.circe.Json
import java.time.Instant

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

  /** Explicit full read. The normal listing remains the only request made during observation. */
  def fetchConfigProfile(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential,
    externalId: String): IO[IntegrationConfigProfileDocument] =
    validProfileId(externalId).fold(invalidResponse[IntegrationConfigProfileDocument]) { id =>
      get(baseUrl.endpoint(s"$ConfigProfilesPath/$id"), credential, ConfigProfileMaxResponseBytes)
        .flatMap(body => decodeConfigProfile(body, id).fold(invalidResponse[IntegrationConfigProfileDocument])(IO.pure))
    }

  /** A single lookup. Only the provider's typed node-not-found response at this exact resource
    * path proves absence; an unrelated 404 remains uncertain.
    */
  private[remnawave] def lookupNodeWire(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential,
    externalId: java.util.UUID): IO[domain.integration.NodeLookupOutcome] = {
    import domain.integration.NodeLookupOutcome._
    val path = s"$NodesPath/$externalId"
    val unknown = Unknown("INTEGRATION_NODE_LOOKUP_RESULT_UNKNOWN")
    if (!credential.valid) IO.pure(Unknown("INTEGRATION_CREDENTIAL_INVALID"))
    else IO.fromEither(Uri.fromString(baseUrl.endpoint(path).toASCIIString)
      .leftMap(_ => RemnawaveErrors.error("INTEGRATION_INVALID_REQUEST"))).flatMap { uri =>
      val request = Request[IO](Method.GET, uri).putHeaders(
        Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"))
      val authenticated = credential.caddyApiKey.fold(request)(key =>
        request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
      client.run(authenticated).use { response =>
        if (response.status.code == 404) boundedBody(response, ConfigProfileMaxResponseBytes).map { body =>
          if (body.exists(typedNodeNotFound(_, path))) ConfirmedNotFound else unknown
        }
        else if (response.status.isSuccess) boundedBody(response, ConfigProfileMaxResponseBytes).map { body =>
          body.flatMap(parse(_).toOption).flatMap(_.hcursor.downField("response").success)
            .flatMap(RemnawaveNodeApi.node).filter(_.externalId == externalId)
            .fold[domain.integration.NodeLookupOutcome](unknown)(Found.apply)
        }
        else IO.pure(unknown)
      }
    }.timeout(requestTimeout).handleError(_ => unknown)
  }

  /** Exactly one DELETE. A lost or malformed response cannot be replayed safely. */
  private[remnawave] def deleteNodeWire(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential,
    externalId: java.util.UUID): IO[domain.integration.NodeDeleteOutcome] = {
    import domain.integration.NodeDeleteOutcome._
    val unknown = Unknown("INTEGRATION_NODE_DELETE_RESULT_UNKNOWN")
    if (!credential.valid) IO.pure(Rejected("INTEGRATION_CREDENTIAL_INVALID"))
    else IO.fromEither(Uri.fromString(baseUrl.endpoint(s"$NodesPath/$externalId").toASCIIString)
      .leftMap(_ => RemnawaveErrors.error("INTEGRATION_INVALID_REQUEST"))).flatMap { uri =>
      val request = Request[IO](Method.DELETE, uri).putHeaders(
        Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"))
      val authenticated = credential.caddyApiKey.fold(request)(key =>
        request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
      client.run(authenticated).use { response =>
        if (response.status.code == 200 || response.status.code == 204) IO.pure(Deleted)
        else if (response.status.code == 404) boundedBody(response, ConfigProfileMaxResponseBytes).map { body =>
          if (body.exists(typedNodeNotFound(_, s"$NodesPath/$externalId"))) Deleted else unknown
        }
        else if (response.status.code == 401) IO.pure(Rejected("INTEGRATION_AUTH_FAILED"))
        else if (response.status.code == 403) IO.pure(Rejected("INTEGRATION_FORBIDDEN"))
        else if (response.status.code == 405) IO.pure(Rejected("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
        else IO.pure(unknown)
      }
    }.timeout(requestTimeout).handleError(error =>
      definiteBeforeWrite(error).map(Rejected.apply).getOrElse(unknown))
  }

  private def typedNodeNotFound(body: String, requestedPath: String): Boolean =
    parse(body).toOption.flatMap { json =>
      val cursor = json.hcursor
      for {
        errorCode <- cursor.get[String]("errorCode").toOption
        message <- cursor.get[String]("message").toOption
        path <- cursor.get[String]("path").toOption
        timestamp <- cursor.get[String]("timestamp").toOption
        _ <- Try(Instant.parse(timestamp)).toOption
      } yield errorCode == "A011" && message == "Node not found" &&
        path.stripPrefix("/") == requestedPath
    }.contains(true)

  /** One PATCH. Anything ambiguous after the request may have left this process is UNKNOWN. */
  def updateNodeAddressWire(baseUrl: IntegrationBaseUrl,credential: RemnawaveCredential,id: java.util.UUID,
    desired: NodeCreateIntent): IO[IntegrationActionRemoteOutcome] = {
    import IntegrationActionRemoteOutcome._
    IO.fromEither(Uri.fromString(baseUrl.endpoint(NodesPath).toASCIIString).leftMap(_ => RemnawaveErrors.error("INTEGRATION_NODE_INVALID_REQUEST"))).flatMap { uri =>
      val request=Request[IO](Method.PATCH,uri).withEntity(Json.obj("uuid"->Json.fromString(id.toString),"address"->Json.fromString(desired.address)).noSpaces)
        .putHeaders(Header.Raw(CIString("Authorization"),s"Bearer ${credential.apiToken}"),Header.Raw(CIString("Content-Type"),"application/json"))
      val authenticated=credential.caddyApiKey.fold(request)(key => request.putHeaders(Header.Raw(CIString("X-Api-Key"),key)))
      client.run(authenticated).use { response =>
        if(Set(400,401,403,404).contains(response.status.code)) IO.pure(DefinitelyFailed("INTEGRATION_NODE_ADDRESS_REJECTED"))
        else if(!response.status.isSuccess) IO.pure(OutcomeUnknown("INTEGRATION_NODE_ADDRESS_RESULT_UNKNOWN"))
        else boundedBody(response,ConfigProfileMaxResponseBytes).map { body =>
          val node=body.flatMap(parse(_).toOption).flatMap(_.hcursor.downField("response").success).flatMap(RemnawaveNodeApi.node)
          if(node.exists(n => n.externalId==id && RemnawaveNodeApi.matches(n,desired))) Succeeded
          else OutcomeUnknown("INTEGRATION_NODE_ADDRESS_RESULT_UNKNOWN")
        }
      }
    }.timeout(requestTimeout).handleError(e => definiteBeforeWrite(e).map(DefinitelyFailed.apply)
      .getOrElse(OutcomeUnknown("INTEGRATION_NODE_ADDRESS_RESULT_UNKNOWN")))
  }

  /** One PATCH. Anything ambiguous after the request may have left this process is UNKNOWN. */
  def updateConfigProfile(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential,
    externalId: String, config: Json, desiredSha256: String): IO[IntegrationActionRemoteOutcome] = {
    import IntegrationActionRemoteOutcome._
    validProfileId(externalId) match {
      case None => IO.pure(DefinitelyFailed("INTEGRATION_CONFIG_PROFILE_INVALID"))
      case Some(_) if !credential.valid => IO.pure(DefinitelyFailed("INTEGRATION_CREDENTIAL_INVALID"))
      case Some(_) if !config.isObject => IO.pure(DefinitelyFailed("INTEGRATION_CONFIG_INVALID"))
      case Some(id) =>
        IO.fromEither(Uri.fromString(baseUrl.endpoint(ConfigProfilesPath).toASCIIString)
          .leftMap(_ => RemnawaveErrors.error("INTEGRATION_CONFIG_PROFILE_INVALID"))).flatMap { uri =>
          val payload = Json.obj("uuid" -> Json.fromString(id), "config" -> config).noSpaces
          val request = Request[IO](Method.PATCH, uri).withEntity(payload)
            .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"),
              Header.Raw(CIString("Content-Type"), "application/json"))
          val authenticated = credential.caddyApiKey.fold(request)(key =>
            request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
          client.run(authenticated).use { response =>
            val code = response.status.code
            if (code == 400) IO.pure(DefinitelyFailed("INTEGRATION_CONFIG_REJECTED"))
            else if (code == 401) IO.pure(DefinitelyFailed("INTEGRATION_AUTH_FAILED"))
            else if (code == 403) IO.pure(DefinitelyFailed("INTEGRATION_FORBIDDEN"))
            else if (code == 404) IO.pure(DefinitelyFailed("INTEGRATION_ENDPOINT_NOT_FOUND"))
            else if (!response.status.isSuccess) IO.pure(OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN"))
            else boundedBody(response, ConfigProfileMaxResponseBytes).map { body =>
              val matches = body.flatMap(decodeConfigProfile(_, id))
                .exists(doc => CanonicalJson.sha256(doc.config) == desiredSha256)
              if (matches) Succeeded else OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN")
            }
          }
        }.timeout(requestTimeout).handleError(error => definiteBeforeWrite(error)
          .map(DefinitelyFailed.apply).getOrElse(OutcomeUnknown("INTEGRATION_CONFIG_DEPLOYMENT_RESULT_UNKNOWN")))
    }
  }

  private def validProfileId(value: String): Option[String] =
    Try(java.util.UUID.fromString(value)).toOption.map(_.toString).filter(_ == value)

  private def decodeConfigProfile(body: String, expectedId: String): Option[IntegrationConfigProfileDocument] =
    parse(body).toOption.flatMap { document =>
      val c = document.hcursor.downField("response")
      for {
        uuid <- c.get[String]("uuid").toOption.filter(_ == expectedId)
        config <- c.downField("config").focus.filter(_.isObject)
        updated <- c.get[String]("updatedAt").toOption.flatMap(value => Try(Instant.parse(value)).toOption)
      } yield IntegrationConfigProfileDocument(uuid, config, Some(updated))
    }

  /** A timeout or disconnect can happen after the write reached Remnawave. Never retry here. */
  def action(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential, externalId: String,
    action: IntegrationActionCode): IO[IntegrationActionRemoteOutcome] = {
    import IntegrationActionRemoteOutcome._
    val suffix = action match {
      case IntegrationActionCode.NodeEnable => "enable"
      case IntegrationActionCode.NodeDisable => "disable"
      case IntegrationActionCode.NodeRestart => "restart"
      case IntegrationActionCode.NodeDelete => return IO.pure(IntegrationActionRemoteOutcome.DefinitelyFailed("INTEGRATION_ACTION_UNSUPPORTED"))
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

  private[remnawave] def definiteBeforeWrite(error: Throwable): Option[String] = error match {
    case _: integration.http.OutboundDestinationRejected => Some("INTEGRATION_DESTINATION_NOT_ALLOWED")
    case _: java.net.UnknownHostException | _: java.net.ConnectException | _: java.net.NoRouteToHostException =>
      Some("INTEGRATION_UNREACHABLE")
    case _: javax.net.ssl.SSLHandshakeException => Some("INTEGRATION_TLS_ERROR")
    case other if other.getCause != null && (other.getCause ne other) => definiteBeforeWrite(other.getCause)
    case _ => None
  }

  private[remnawave] def get(target: URI, credential: RemnawaveCredential, maxBytes: Int): IO[String] =
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

  private[remnawave] def protocolProfileLookup(baseUrl: IntegrationBaseUrl, auth: RemnawaveCredential,
    name: String, tag: String, config: Json): IO[Option[domain.integration.NodeProtocolBinding]] =
    get(baseUrl.endpoint(ConfigProfilesPath),auth,DefaultInventoryMaxResponseBytes).flatMap { body =>
      RemnawaveInventory.configProfiles(body).filter(_.size<=DefaultInventoryMaxObjects)
        .liftTo[IO](RemnawaveErrors.error("INTEGRATION_INVALID_RESPONSE")).flatMap { profiles =>
          val candidates=profiles.filter(p => p.displayName==name || (p.summary match {
            case s: domain.integration.RemnawaveConfigProfileSummary => s.inbounds.exists(_.tag==tag)
            case _ => false
          }))
          candidates match {
            case Nil => IO.pure(None)
            case List(p) => p.summary match {
              case s: domain.integration.RemnawaveConfigProfileSummary if p.displayName==name &&
                s.inbounds.size==1 && s.inbounds.head.tag==tag && s.configSha256.contains(CanonicalJson.sha256(config)) =>
                IO.pure(Some(domain.integration.NodeProtocolBinding(java.util.UUID.fromString(p.externalId),
                  s.inbounds.map(i => java.util.UUID.fromString(i.uuid)),CanonicalJson.sha256(config))))
              case _ => IO.raiseError(RemnawaveErrors.error("REMNAWAVE_PROTOCOL_PROFILE_CONFLICT"))
            }
            case _ => IO.raiseError(RemnawaveErrors.error("REMNAWAVE_PROTOCOL_PROFILE_CONFLICT"))
          }
        }
    }

  /** One write only. Even validation errors can follow a committed provider-side write. */
  private[remnawave] def createProtocolProfileWire(baseUrl: IntegrationBaseUrl, auth: RemnawaveCredential,
    name: String, tag: String, config: Json): IO[domain.integration.ProtocolProfileOutcome] = {
    import domain.integration.ProtocolProfileOutcome._
    val unknown = Unknown("REMNAWAVE_PROTOCOL_PROFILE_CREATE_UNKNOWN")
    IO.fromEither(Uri.fromString(baseUrl.endpoint(ConfigProfilesPath).toASCIIString)
      .leftMap(_ => RemnawaveErrors.error("INTEGRATION_INVALID_REQUEST"))).flatMap { uri =>
      val request=Request[IO](Method.POST,uri).withEntity(Json.obj("name"->Json.fromString(name),"config"->config).noSpaces)
        .putHeaders(Header.Raw(CIString("Authorization"),s"Bearer ${auth.apiToken}"),Header.Raw(CIString("Content-Type"),"application/json"))
      val authenticated=auth.caddyApiKey.fold(request)(key => request.putHeaders(Header.Raw(CIString("X-Api-Key"),key)))
      client.run(authenticated).use { response =>
        if(response.status.code==401) IO.pure[domain.integration.ProtocolProfileOutcome](Rejected("INTEGRATION_AUTH_FAILED"))
        else if(response.status.code==403) IO.pure[domain.integration.ProtocolProfileOutcome](Rejected("INTEGRATION_FORBIDDEN"))
        else if(!response.status.isSuccess) IO.pure[domain.integration.ProtocolProfileOutcome](unknown)
        else boundedBody(response,ConfigProfileMaxResponseBytes).map { body =>
          val binding=for {
            json <- body.flatMap(parse(_).toOption)
            c=json.hcursor.downField("response")
            id <- c.get[String]("uuid").toOption.flatMap(s => Try(java.util.UUID.fromString(s)).toOption.filter(_.toString==s))
            _ <- c.get[String]("name").toOption.filter(_==name)
            actual <- c.downField("config").focus.filter(CanonicalJson.sha256(_)==CanonicalJson.sha256(config))
            rows <- c.get[List[Json]]("inbounds").toOption.filter(_.size==1)
            _ <- rows.head.hcursor.get[String]("tag").toOption.filter(_==tag)
            inbound <- rows.head.hcursor.get[String]("uuid").toOption.flatMap(s => Try(java.util.UUID.fromString(s)).toOption.filter(_.toString==s))
          } yield domain.integration.NodeProtocolBinding(id,List(inbound),CanonicalJson.sha256(actual))
          binding.fold[domain.integration.ProtocolProfileOutcome](unknown)(Confirmed.apply)
        }
      }
    }.timeout(requestTimeout).handleError(e => definiteBeforeWrite(e).map(Rejected.apply).getOrElse(unknown))
  }

  /** Called only by the capability-gated adapter. One POST, never an automatic retry. */
  private[remnawave] def createNodeWire(baseUrl: IntegrationBaseUrl, credential: RemnawaveCredential,
    intent: domain.integration.NodeCreateIntent): IO[domain.integration.NodeCreateOutcome] = {
    import domain.integration.NodeCreateOutcome._
    IO.fromEither(Uri.fromString(baseUrl.endpoint(NodesPath).toASCIIString)
      .leftMap(_ => RemnawaveErrors.error("INTEGRATION_INVALID_REQUEST"))).flatMap { uri =>
      val request = Request[IO](Method.POST, uri).withEntity(RemnawaveNodeApi.createPayload(intent).noSpaces)
        .putHeaders(Header.Raw(CIString("Authorization"), s"Bearer ${credential.apiToken}"),
          Header.Raw(CIString("Content-Type"), "application/json"))
      val authenticated = credential.caddyApiKey.fold(request)(key =>
        request.putHeaders(Header.Raw(CIString("X-Api-Key"), key)))
      client.run(authenticated).use { response =>
        response.status.code match {
          // The upstream creates the DB row before checking profile inbounds. A124 returns 404
          // after that write. Neither an arbitrary 400 nor a 404 proves the node was not created.
          case 400 | 404 | 422 => boundedBody(response, MaxResponseBytes).map { body =>
            val code = body.flatMap(parse(_).toOption).flatMap(_.hcursor.get[String]("errorCode").toOption)
            code match {
              case Some("A033" | "A034") => Rejected("INTEGRATION_NODE_CONFLICT")
              case _ => Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")
            }
          }
          case 401 => IO.pure[domain.integration.NodeCreateOutcome](Rejected("INTEGRATION_AUTH_FAILED"))
          case 403 => IO.pure[domain.integration.NodeCreateOutcome](Rejected("INTEGRATION_FORBIDDEN"))
          case 405 => IO.pure[domain.integration.NodeCreateOutcome](Rejected("INTEGRATION_API_CONTRACT_UNCONFIRMED"))
          case 409 => IO.pure[domain.integration.NodeCreateOutcome](Rejected("INTEGRATION_NODE_CONFLICT"))
          case _ if response.status.isSuccess => boundedBody(response, ConfigProfileMaxResponseBytes).map { body =>
            body.flatMap(parse(_).toOption).flatMap(_.hcursor.downField("response").success)
              .flatMap(RemnawaveNodeApi.node)
              .filter(RemnawaveNodeApi.matches(_, intent)).fold[domain.integration.NodeCreateOutcome](
                Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"))(Created.apply)
          }
          case _ => IO.pure[domain.integration.NodeCreateOutcome](Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN"))
        }
      }
    }.timeout(requestTimeout).handleError(error => definiteBeforeWrite(error)
      .map(Rejected.apply).getOrElse(Unknown("INTEGRATION_NODE_CREATE_RESULT_UNKNOWN")))
  }
}

object RemnawaveClient {
  /** The most a stats response may carry. The real envelope is a few kilobytes. */
  val MaxResponseBytes: Int = 64 * 1024
  val DefaultInventoryMaxResponseBytes: Int = 8 * 1024 * 1024
  val DefaultInventoryMaxObjects: Int = 10000
  val NodesPath = "api/nodes"
  val HostsPath = "api/hosts"
  val ConfigProfilesPath = "api/config-profiles"
  val ConfigProfileMaxResponseBytes: Int = 512 * 1024

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
