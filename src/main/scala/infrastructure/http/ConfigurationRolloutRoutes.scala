package ru.bitec.app.ops
package infrastructure.http

import application.configuration._
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration._
import infrastructure.http.dto.{ApiErrorResponse, ConfigurationDeploymentDtos, HttpJsonCodecs}
import io.circe.{Decoder, Encoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

final case class RolloutPreflightRequest(targets: List[ConfigurationRolloutTarget])
final case class ApprovedTargetRequest(assignmentId: UUID, expectedVersion: Int, connectionId: UUID,
  connectionUpdatedAt: Instant, desiredSha256: String, expectedRemoteSha256: Option[String],
  expectedRemoteMissing: Boolean, execution: ConfigurationExecutionPolicy)
final case class CreateRolloutRequest(profileId: UUID, revisionNumber: Int, requestId: UUID,
  canaryCount: Int, batchSize: Int, pauseSeconds: Int, stopOnFailure: Boolean,
  rollbackMode: ConfigurationRollbackMode, targets: List[ApprovedTargetRequest])

final class ConfigurationRolloutRoutes[Tx[_]](
  rollouts: ConfigurationRollouts[IO, Tx], authorization: OrganizationAuthorization, logger: Logger[IO]
) {
  import ConfigurationDeploymentDtos.Codecs.{policyDecoder, activationDecoder, validatorDecoder}
  import HttpJsonCodecs._

  private implicit val targetDecoder: Decoder[ConfigurationRolloutTarget] =
    Decoder.forProduct4("assignmentId", "expectedVersion", "connectionId", "execution")(
      ConfigurationRolloutTarget.apply)
  private implicit val preflightDecoder: Decoder[RolloutPreflightRequest] =
    Decoder.forProduct1("targets")(RolloutPreflightRequest.apply)
  private implicit val rollbackDecoder: Decoder[ConfigurationRollbackMode] = Decoder.decodeString.emap { code =>
    ConfigurationRollbackMode.all.find(_.code == code).toRight("Invalid rollback mode")
  }
  private implicit val approvedDecoder: Decoder[ApprovedTargetRequest] = Decoder.forProduct8(
    "assignmentId", "expectedVersion", "connectionId", "connectionUpdatedAt", "desiredSha256",
    "expectedRemoteSha256", "expectedRemoteMissing", "execution")(ApprovedTargetRequest.apply)
  private implicit val createDecoder: Decoder[CreateRolloutRequest] = Decoder.forProduct9(
    "profileId", "revisionNumber", "requestId", "canaryCount", "batchSize", "pauseSeconds",
    "stopOnFailure", "rollbackMode", "targets")(CreateRolloutRequest.apply)

  private def expected(hash: Option[String], missing: Boolean): Option[ExpectedRemoteState] =
    if (missing == hash.isDefined) None
    else Some(hash.fold[ExpectedRemoteState](ExpectedRemoteState.Missing)(ExpectedRemoteState.Sha256.apply))

  private def state(value: ExpectedRemoteState): Json = value match {
    case ExpectedRemoteState.Missing => Json.obj("exists" -> Json.False, "sha256" -> Json.Null)
    case ExpectedRemoteState.Sha256(hash) => Json.obj("exists" -> Json.True, "sha256" -> Json.fromString(hash))
  }

  private implicit val preflightEncoder: Encoder[List[ConfigurationRolloutPreflightItem]] = Encoder.instance { items =>
    Json.arr(items.map { item => Json.obj(
      "assignmentId" -> Json.fromString(item.target.assignmentId.toString),
      "expectedVersion" -> Json.fromInt(item.target.expectedVersion),
      "connectionId" -> Json.fromString(item.target.connectionId.toString),
      "ready" -> Json.fromBoolean(item.ready),
      "desiredSha256" -> item.desiredSha256.fold(Json.Null)(Json.fromString),
      "connectionUpdatedAt" -> item.connectionUpdatedAt.fold(Json.Null)(instantEncoder(_)),
      "remote" -> item.expectedRemoteState.fold(Json.Null)(state),
      "addedLines" -> Json.fromInt(item.addedLines),
      "removedLines" -> Json.fromInt(item.removedLines),
      "errorCode" -> item.errorCode.fold(Json.Null)(Json.fromString)) }: _*)
  }

  private def rolloutJson(value: ConfigurationRollout): Json = Json.obj(
    "id" -> Json.fromString(value.id.toString),
    "profileId" -> Json.fromString(value.profileId.toString),
    "profileRevisionNumber" -> Json.fromInt(value.profileRevisionNumber),
    "state" -> Json.fromString(value.state.code),
    "canaryCount" -> Json.fromInt(value.strategy.canaryCount),
    "batchSize" -> Json.fromInt(value.strategy.batchSize),
    "pauseSeconds" -> Json.fromInt(value.strategy.pauseSeconds),
    "stopOnFailure" -> Json.fromBoolean(value.strategy.stopOnFailure),
    "rollbackMode" -> Json.fromString(value.strategy.rollbackMode.code),
    "createdAt" -> instantEncoder(value.createdAt),
    "startedAt" -> value.startedAt.fold(Json.Null)(instantEncoder(_)),
    "finishedAt" -> value.finishedAt.fold(Json.Null)(instantEncoder(_)))

  private def itemJson(value: ConfigurationRolloutItem): Json = Json.obj(
    "id" -> Json.fromString(value.id.toString), "position" -> Json.fromInt(value.position),
    "assignmentId" -> Json.fromString(value.assignmentId.toString),
    "assignmentVersion" -> Json.fromInt(value.assignmentVersion),
    "resourceId" -> Json.fromString(value.resourceId.toString),
    "targetPath" -> Json.fromString(value.targetPath),
    "state" -> Json.fromString(value.state.code),
    "deploymentId" -> value.deploymentId.fold(Json.Null)(id => Json.fromString(id.toString)))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / "preflight" =>
      authorized(request) { context => body[RolloutPreflightRequest](request) { input =>
        respond(request, context, "configuration.rollout.preflight")(
          rollouts.preflight(context.organizationId, input.targets).flatMap(Ok(_)))
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" =>
      authorized(request) { context => body[CreateRolloutRequest](request) { input =>
        val approved = input.targets.traverse { item =>
          expected(item.expectedRemoteSha256, item.expectedRemoteMissing).map { remote =>
            ConfigurationRolloutApprovedTarget(ConfigurationRolloutTarget(item.assignmentId,
              item.expectedVersion, item.connectionId, item.execution),
              item.connectionUpdatedAt, item.desiredSha256, remote)
          }
        }
        approved.fold[IO[Response[IO]]](BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid remote state"))) { targets =>
          respond(request, context, "configuration.rollout.request")(
            rollouts.launch(context.actor, input.profileId, input.revisionNumber, input.requestId,
              ConfigurationRolloutStrategy(input.canaryCount, input.batchSize, input.pauseSeconds,
                input.stopOnFailure, input.rollbackMode), targets).flatMap { id =>
              Accepted(Json.obj("rolloutId" -> Json.fromString(id.toString), "state" -> Json.fromString("QUEUED")))
            })
        }
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / id =>
      authorized(request) { context => withId(id) { rolloutId =>
        respond(request, context, "configuration.rollout.detail")(
          rollouts.detail(context.organizationId, rolloutId).flatMap { case (rollout, items) =>
            Ok(Json.obj("rollout" -> rolloutJson(rollout), "items" -> Json.arr(items.map(itemJson): _*)))
          })
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / id / "cancel" =>
      authorized(request) { context => withId(id) { rolloutId =>
        respond(request, context, "configuration.rollout.cancel")(
          rollouts.cancel(context.actor, rolloutId) *> Ok(Json.obj("cancelRequested" -> Json.True)))
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" =>
      authorized(request) { context =>
        val params = request.uri.query.params
        val profile = params.get("profileId").traverse(raw => Try(UUID.fromString(raw)).toOption)
        val cursor = (params.get("beforeCreatedAt"), params.get("beforeId")) match {
          case (None, None) => Some(None)
          case (Some(at), Some(id)) => (Try(Instant.parse(at)).toOption,
            Try(UUID.fromString(id)).toOption).mapN((time, uuid) => Some((time, uuid)))
          case _ => None
        }
        val limit = params.get("limit").fold[Option[Int]](Some(50))(
          raw => Try(raw.toInt).toOption.filter(n => n >= 1 && n <= 200))
        (profile, cursor, limit) match {
          case (Some(p), Some(c), Some(n)) => respond(request, context, "configuration.rollout.history")(
            rollouts.history(context.organizationId, p, c, n).flatMap(values => Ok(Json.arr(values.map(rolloutJson): _*))))
          case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid cursor or limit"))
        }
      }
  }

  private def authorized(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.DeployConfigurations)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid rollout")))(next)

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(262145).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > 262144) BadRequest(ApiErrorResponse("INVALID_REQUEST", "Request too large"))
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8))
        .fold(_ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid rollout request")), next)
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: ConfigurationRolloutError => error match {
      case ConfigurationRolloutError.Invalid => BadRequest(ApiErrorResponse(error.code, error.getMessage))
      case ConfigurationRolloutError.NotFound => NotFound(ApiErrorResponse(error.code, error.getMessage))
      case _ => Conflict(ApiErrorResponse(error.code, error.getMessage))
    }
    case error => ReadModelHttp.failedAs(logger, "configuration.rollout.failed", request, operation,
      "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
  }
}
