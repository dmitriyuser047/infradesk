package ru.bitec.app.ops
package infrastructure.http

import application.integration._
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.FleetRollbackScope
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.util.UUID
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import scala.util.Try

/** Closed DTOs accept backend release IDs only; no image/tag/registry/compose input exists. */
final class RemnawaveFleetUpgradeRoutes[Tx[_]](service: RemnawaveFleetUpgradeApi,
  authorization: OrganizationAuthorization, logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid Node image lifecycle request")
  private val Base = "remnawave-fleets"
  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-releases" =>
      read(req)(ctx => ids(integration, fleet)((i, f) => respond(req, ctx)(service.status(ctx.organizationId, i, f).flatMap(Ok(_)))))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-release-target" =>
      manage(req)(ctx => ids(integration, fleet)((i, f) => body(req, Set("releaseId")) { json =>
        json.hcursor.get[String]("releaseId").toOption.filter(_.matches("node-[0-9]+\\.[0-9]+\\.[0-9]+"))
          .fold[IO[Response[IO]]](BadRequest(invalid))(release => respond(req, ctx)(
            service.select(ctx.actor, i, f, release).flatMap(r => Ok(domain.integration.NodeReleaseJson.releaseRevisionEncoder(r)))))
      }))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" / "preview" =>
      control(req)(ctx => ids(integration, fleet)((i, f) => body(req,
        Set("releaseRevisionId", "canaryMemberIds", "waveSize", "automaticRollback", "pauseAfterCanary")) { json =>
        val c = json.hcursor
        val input = for {
          release <- c.get[String]("releaseRevisionId").toOption.flatMap(uuid)
          raw <- c.get[List[String]]("canaryMemberIds").toOption.filter(_.size <= 500)
          canary <- raw.traverse(uuid)
          size <- c.get[Int]("waveSize").toOption.filter(n => n >= 1 && n <= 25)
          automatic <- c.get[Boolean]("automaticRollback").toOption
          pause <- c.get[Boolean]("pauseAfterCanary").toOption
        } yield NodeUpgradePreviewInput(release, canary, size, automatic, pause)
        input.fold[IO[Response[IO]]](BadRequest(invalid))(value => respond(req, ctx)(service.preview(ctx.actor, i, f, value).flatMap(p =>
          Ok(Json.obj("status" -> Json.fromString(p.status), "issues" -> Json.arr(p.issues.map(Json.fromString): _*),
            "planId" -> p.run.fold(Json.Null)(r => Json.fromString(r.id.toString)),
            "plan" -> p.run.fold(Json.Null)(r => domain.integration.NodeReleaseJson.snapshotEncoder(r.snapshot)),
            "expiresAt" -> p.run.fold(Json.Null)(r => Json.fromString(r.expiresAt.toString)))))))
      }))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" =>
      control(req)(ctx => ids(integration, fleet)((i, f) => body(req, Set("planId", "requestId")) { json =>
        val input = for {
          plan <- json.hcursor.get[String]("planId").toOption.flatMap(uuid)
          request <- json.hcursor.get[String]("requestId").toOption.flatMap(uuid)
        } yield plan -> request
        input.fold[IO[Response[IO]]](BadRequest(invalid)) { case (plan, request) =>
          respond(req, ctx)(service.start(ctx.actor, i, f, plan, request).flatMap(r => Accepted(NodeUpgradeJson.run(r))))
        }
      }))
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" =>
      read(req)(ctx => ids(integration, fleet)((i, f) => respond(req, ctx)(service.history(ctx.organizationId, i, f)
        .flatMap(rows => Ok(Json.obj("items" -> Json.arr(rows.map(NodeUpgradeJson.run): _*)))))))
    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" / upgrade =>
      read(req)(ctx => ids(integration, fleet)((i, f) => withId(upgrade)(id => respond(req, ctx)(
        service.detail(ctx.organizationId, i, f, id).flatMap(d => Ok(NodeUpgradeJson.detail(d)))))))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" / upgrade / command
      if Set("pause", "resume")(command) =>
      control(req)(ctx => ids(integration, fleet)((i, f) => withId(upgrade)(id => respond(req, ctx)(
        service.control(ctx.actor, i, f, id, command).flatMap(r => Ok(NodeUpgradeJson.run(r)))))))
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "node-upgrades" / upgrade / "rollback" =>
      control(req)(ctx => ids(integration, fleet)((i, f) => withId(upgrade)(id => body(req, Set("scope")) { json =>
        json.hcursor.get[String]("scope").toOption.flatMap(FleetRollbackScope.fromCode)
          .fold[IO[Response[IO]]](BadRequest(invalid))(scope => respond(req, ctx)(
            service.control(ctx.actor, i, f, id, "rollback", scope).flatMap(r => Accepted(NodeUpgradeJson.run(r)))))
      })))
  }
  private def read(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]) =
    authorization.require(req, OrganizationPermission.ReadOrganization)(next)
  private def manage(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]) =
    authorization.require(req, OrganizationPermission.ManageIntegrations)(_ =>
      authorization.require(req, OrganizationPermission.ManageConfigurations)(next))
  private def control(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]) =
    manage(req)(_ => authorization.require(req, OrganizationPermission.ExecuteOperations)(next))
  private def uuid(v: String): Option[UUID] = Try(UUID.fromString(v)).toOption.filter(_.toString == v)
  private def withId(v: String)(next: UUID => IO[Response[IO]]) = uuid(v).fold[IO[Response[IO]]](BadRequest(invalid))(next)
  private def ids(i: String, f: String)(next: (UUID, UUID) => IO[Response[IO]]) = withId(i)(a => withId(f)(b => next(a, b)))
  private def body(req: Request[IO], keys: Set[String])(next: Json => IO[Response[IO]]): IO[Response[IO]] =
    req.body.take(8193L).compile.to(Array).flatMap { bytes =>
      val text = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toOption
      val json = text.flatMap(io.circe.parser.parse(_).toOption).filter(_.asObject.exists(_.keys.toSet == keys))
      if (bytes.length > 8192 || json.isEmpty) BadRequest(invalid) else next(json.get)
    }
  private def respond(req: Request[IO], ctx: OrganizationAccessContext)(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case e: IntegrationError if e.code.endsWith("_NOT_FOUND") => NotFound(ApiErrorResponse(e.code, e.getMessage))
      case e: IntegrationError => Conflict(ApiErrorResponse(e.code, e.getMessage))
      case _ => logger.warn(s"remnawave.fleet.upgrade.request_failed organizationId=${ctx.organizationId}") *>
        InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "The request could not be completed"))
    }
}
