package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{ConfigurationPromotionError, ConfigurationPromotionPreview, ConfigurationPromotionResult, ConfigurationPromotions}
import application.port.ConfigurationPromotionSelection
import cats.effect.IO
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.{Decoder, Encoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.util.Try

final case class ConfigurationPromotionRequest(revisionNumber: Int, assignments: List[ConfigurationPromotionSelection])

final class ConfigurationPromotionRoutes[Tx[_]](
  promotions: ConfigurationPromotions[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._

  private implicit val selectionDecoder: Decoder[ConfigurationPromotionSelection] =
    Decoder.forProduct2("assignmentId", "expectedVersion")(ConfigurationPromotionSelection.apply)
  private implicit val requestDecoder: Decoder[ConfigurationPromotionRequest] =
    Decoder.forProduct2("revisionNumber", "assignments")(ConfigurationPromotionRequest.apply)
  private implicit val previewEncoder: Encoder[ConfigurationPromotionPreview] = Encoder.instance { preview =>
    Json.obj("revisionNumber" -> Json.fromInt(preview.revisionNumber),
      "compatible" -> Json.fromBoolean(preview.compatible),
      "items" -> Json.arr(preview.items.map { item => Json.obj(
        "assignmentId" -> Json.fromString(item.assignmentId.toString),
        "expectedVersion" -> Json.fromInt(item.expectedVersion),
        "compatible" -> Json.fromBoolean(item.compatible),
        "errorCode" -> item.errorCode.fold(Json.Null)(Json.fromString),
        "variableName" -> item.variableName.fold(Json.Null)(Json.fromString)) }: _*))
  }
  private implicit val resultEncoder: Encoder[ConfigurationPromotionResult] = Encoder.instance { result =>
    Json.obj("revisionNumber" -> Json.fromInt(result.revisionNumber),
      "assignments" -> Json.arr(result.assignments.map { case (id, version) =>
        Json.obj("assignmentId" -> Json.fromString(id.toString), "version" -> Json.fromInt(version))
      }: _*))
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id /
      "assignment-promotions" / "preview" =>
      authorized(request, OrganizationPermission.ManageConfigurations) { context => withId(id) { profileId =>
        body(request) { input => respond(request, context, "configuration.promotion.preview")(
          promotions.preview(context.organizationId, profileId, input.revisionNumber, input.assignments)
            .flatMap(Ok(_))) }
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-profiles" / id /
      "assignment-promotions" =>
      authorized(request, OrganizationPermission.ManageConfigurations) { context => withId(id) { profileId =>
        body(request) { input => respond(request, context, "configuration.promotion.commit")(
          promotions.commit(context.actor, profileId, input.revisionNumber, input.assignments)
            .flatMap(Ok(_))) }
      } }
  }

  private def authorized(request: Request[IO], permission: OrganizationPermission)(
    next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, permission)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid profile")))(next)

  private def body(request: Request[IO])(next: ConfigurationPromotionRequest => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(65537).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > 65536) BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid promotion"))
      else io.circe.parser.decode[ConfigurationPromotionRequest](new String(bytes, StandardCharsets.UTF_8))
        .fold(_ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid promotion")), next)
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: ConfigurationPromotionError => error match {
      case ConfigurationPromotionError.Invalid => BadRequest(ApiErrorResponse(error.code, error.getMessage))
      case ConfigurationPromotionError.RevisionMissing => NotFound(ApiErrorResponse(error.code, error.getMessage))
      case _ => Conflict(ApiErrorResponse(error.code, error.getMessage))
    }
    case error => ReadModelHttp.failedAs(logger, "configuration.promotion.failed", request, operation,
      "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
  }
}
