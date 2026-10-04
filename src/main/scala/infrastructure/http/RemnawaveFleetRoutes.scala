package ru.bitec.app.ops
package infrastructure.http

import application.integration.{FleetDesiredInput, FleetJson, IntegrationError, RemnawaveFleets}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.IntegrationDesiredNodeState
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.UUID
import scala.util.Try

/** Fleet desired state over HTTP. Reading needs organization read; changing metadata needs the
  * integration and configuration permissions; only the refresh action, which causes remote reads,
  * additionally needs the operations permission. Promotion is metadata, so it does not.
  */
final class RemnawaveFleetRoutes[Tx[_]](service: RemnawaveFleets[Tx], authorization: OrganizationAuthorization,
  logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid Remnawave fleet request")
  private val MaxBytes = 32768
  private val Base = "remnawave-fleets"

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` =>
      read(req)(ctx => withId(integration)(id => respond(req, ctx)(
        service.list(ctx.organizationId, id, archived = archivedFlag(req)).flatMap(rows =>
          Ok(Json.obj("items" -> Json.arr(rows.map { case (fleet, summary) =>
            FleetJson.fleet(fleet, summary) }: _*)))))))

    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / "candidates" =>
      read(req)(ctx => withId(integration)(id => respond(req, ctx)(
        service.candidates(ctx.organizationId, id).flatMap(rows =>
          Ok(Json.obj("items" -> Json.arr(rows.map(FleetJson.candidate): _*)))))))

    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet =>
      read(req)(ctx => withId(integration)(id => withId(fleet)(f => respond(req, ctx)(
        service.detail(ctx.organizationId, id, f).flatMap(detail => Ok(FleetJson.detail(detail)))))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` =>
      manage(req)(ctx => withId(integration)(id => body(req, Set("code", "name", "description",
        "desiredConfiguration", "memberNodeIds")) { json =>
        val cursor = json.hcursor
        val decoded = for {
          code <- cursor.get[String]("code").toOption
          name <- cursor.get[String]("name").toOption
          description <- cursor.get[Option[String]]("description").toOption
          desired <- cursor.get[Json]("desiredConfiguration").toOption.flatMap(desiredInput)
          rawMembers <- cursor.get[List[String]]("memberNodeIds").toOption.filter(_.size <= 500)
          members <- rawMembers.traverse(uuid)
        } yield (code, name, description, desired, members)
        decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case (code, name, description, desired, members) =>
          respond(req, ctx)(service.create(ctx.actor, id, code, name, description, desired, members)
            .flatMap(fleet => Created(FleetJson.fleet(fleet, RemnawaveFleets.emptySummary))))
        }
      }))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "revisions" =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f =>
        body(req, Set("desiredConfiguration", "expectedVersion")) { json =>
          val decoded = for {
            desired <- json.hcursor.get[Json]("desiredConfiguration").toOption.flatMap(desiredInput)
            version <- json.hcursor.get[Long]("expectedVersion").toOption
          } yield desired -> version
          decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case (desired, version) =>
            respond(req, ctx)(service.createRevision(ctx.actor, id, f, desired, version).flatMap(revision =>
              Created(FleetJson.revision(revision, None))))
          }
        })))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "revisions" / revision / "preview" =>
      read(req)(ctx => withId(integration)(id => withId(fleet)(f => withId(revision)(r => respond(req, ctx)(
        service.preview(ctx.organizationId, id, f, r).flatMap(value => Ok(FleetJson.impact(value))))))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "revisions" / revision / "promote" =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f => withId(revision)(r =>
        body(req, Set("expectedVersion")) { json =>
          json.hcursor.get[Long]("expectedVersion").toOption.fold[IO[Response[IO]]](BadRequest(invalid))(version =>
            respond(req, ctx)(service.promote(ctx.actor, id, f, r, version).flatMap(value =>
              Ok(FleetJson.fleet(value, RemnawaveFleets.emptySummary)))))
        }))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "members" =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f =>
        body(req, Set("inventoryNodeId", "expectedVersion")) { json =>
          val decoded = for {
            node <- json.hcursor.get[String]("inventoryNodeId").toOption.flatMap(uuid)
            version <- json.hcursor.get[Long]("expectedVersion").toOption
          } yield node -> version
          decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case (node, version) =>
            respond(req, ctx)(service.addMember(ctx.actor, id, f, node, version).flatMap(member =>
              Created(Json.obj("membershipId" -> Json.fromString(member.id.toString)))))
          }
        })))

    case req @ DELETE -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "members" / member =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f => withId(member)(m =>
        expectedVersion(req).fold[IO[Response[IO]]](BadRequest(invalid))(version =>
          respond(req, ctx)(service.removeMember(ctx.actor, id, f, m, version) *> NoContent()))))))

    case req @ PATCH -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f =>
        body(req, Set("name", "description", "expectedVersion")) { json =>
          val decoded = for {
            name <- json.hcursor.get[String]("name").toOption
            description <- json.hcursor.get[Option[String]]("description").toOption
            version <- json.hcursor.get[Long]("expectedVersion").toOption
          } yield (name, description, version)
          decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case (name, description, version) =>
            respond(req, ctx)(service.update(ctx.actor, id, f, name, description, version).flatMap(value =>
              Ok(FleetJson.fleet(value, RemnawaveFleets.emptySummary))))
          }
        })))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "archive" =>
      manage(req)(ctx => withId(integration)(id => withId(fleet)(f =>
        body(req, Set("expectedVersion")) { json =>
          json.hcursor.get[Long]("expectedVersion").toOption.fold[IO[Response[IO]]](BadRequest(invalid))(version =>
            respond(req, ctx)(service.archive(ctx.actor, id, f, version).flatMap(value =>
              Ok(FleetJson.fleet(value, RemnawaveFleets.emptySummary)))))
        })))

    // The only action that causes remote reads, so the only one that needs ExecuteOperations.
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "refresh" =>
      observe(req)(ctx => withId(integration)(id => withId(fleet)(f => respond(req, ctx)(
        service.refresh(ctx.actor, id, f).flatMap(count =>
          Accepted(Json.obj("membersDue" -> Json.fromInt(count))))))))
  }

  private def desiredInput(json: Json): Option[FleetDesiredInput] = {
    val cursor = json.hcursor
    val keys = Set("serverProfileId", "serverProfileRevisionNumber", "inventoryConfigProfileId",
      "configRevisionNumber", "activeInboundIds", "nodePort", "panelCidrs", "desiredNodeState")
    for {
      _ <- Option.when(json.asObject.exists(_.keys.toSet == keys))(())
      profile <- cursor.get[String]("serverProfileId").toOption.flatMap(uuid)
      profileRevision <- cursor.get[Int]("serverProfileRevisionNumber").toOption.filter(_ >= 1)
      configProfile <- cursor.get[String]("inventoryConfigProfileId").toOption.flatMap(uuid)
      configRevision <- cursor.get[Int]("configRevisionNumber").toOption.filter(_ >= 1)
      rawInbounds <- cursor.get[List[String]]("activeInboundIds").toOption.filter(_.size <= 256)
      inbounds <- rawInbounds.traverse(uuid)
      port <- cursor.get[Int]("nodePort").toOption.filter(value => value >= 1 && value <= 65535)
      cidrs <- cursor.get[List[String]]("panelCidrs").toOption.filter(_.size <= 32)
      state <- cursor.get[String]("desiredNodeState").toOption.flatMap(IntegrationDesiredNodeState.fromCode)
    } yield FleetDesiredInput(profile, profileRevision, configProfile, configRevision, inbounds, port, cidrs, state)
  }

  private def archivedFlag(req: Request[IO]): Boolean =
    req.uri.query.params.get("archived").contains("true")
  private def expectedVersion(req: Request[IO]): Option[Long] =
    req.uri.query.params.get("expectedVersion").flatMap(value => Try(value.toLong).toOption)

  private def read(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(req, OrganizationPermission.ReadOrganization)(next)
  private def manage(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(req, OrganizationPermission.ManageIntegrations)(_ =>
      authorization.require(req, OrganizationPermission.ManageConfigurations)(next))
  private def observe(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    manage(req)(_ => authorization.require(req, OrganizationPermission.ExecuteOperations)(next))

  private def uuid(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption.filter(_.toString == value)
  private def withId(value: String)(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
    uuid(value).fold[IO[Response[IO]]](BadRequest(invalid))(f)

  private def body(req: Request[IO], keys: Set[String])(f: Json => IO[Response[IO]]): IO[Response[IO]] =
    req.body.take(MaxBytes + 1L).compile.to(Array).flatMap { bytes =>
      val decoded = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toOption
      val json = decoded.flatMap(io.circe.parser.parse(_).toOption)
        .filter(value => value.asObject.exists(_.keys.toSet == keys))
      if (bytes.length > MaxBytes || json.isEmpty) BadRequest(invalid) else f(json.get)
    }

  private def respond(req: Request[IO], ctx: OrganizationAccessContext)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case e: IntegrationError if e.code == "INTEGRATION_NOT_FOUND" || e.code.endsWith("_NOT_FOUND") =>
      NotFound(ApiErrorResponse(e.code, e.getMessage))
    case e: IntegrationError if e.code == "REMNAWAVE_FLEET_INVALID_INPUT" ||
      e.code.startsWith("REMNAWAVE_FLEET_CIDR") || e.code.startsWith("REMNAWAVE_FLEET_PORT") ||
      e.code.startsWith("REMNAWAVE_FLEET_INBOUND") || e.code.startsWith("REMNAWAVE_FLEET_REVISION_HASH") =>
      BadRequest(ApiErrorResponse(e.code, e.getMessage))
    case e: IntegrationError => Conflict(ApiErrorResponse(e.code, e.getMessage))
    case e => ReadModelHttp.failedAs(logger, "remnawave.fleet.request_failed", req, "fleet",
      "organizationId" -> ctx.organizationId, "actorUserId" -> ctx.user.id)(e)
  }
}
