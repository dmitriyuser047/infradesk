package ru.bitec.app.ops
package infrastructure.http

import application.configuration._
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration._
import infrastructure.http.dto.{ApiErrorResponse, ConfigurationDeploymentDtos, HttpJsonCodecs}
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

final case class RolloutPreflightRequest(profileId: UUID, revisionNumber: Int, targets: List[ConfigurationRolloutTarget])
final case class ApprovedTargetRequest(assignmentId: UUID, expectedVersion: Int, connectionId: UUID,
  connectionUpdatedAt: Instant, desiredSha256: String, expectedRemoteSha256: Option[String],
  expectedRemoteMissing: Boolean, execution: ConfigurationExecutionPolicy)
final case class CreateRolloutRequest(profileId: UUID, revisionNumber: Int, requestId: UUID,
  strategy: ConfigurationRolloutStrategy, targets: List[ApprovedTargetRequest])

/** Rollouts run remote changes on many nodes, so every route needs DEPLOY_CONFIGURATIONS. */
final class ConfigurationRolloutRoutes[Tx[_]](
  rollouts: ConfigurationRollouts[IO, Tx], authorization: OrganizationAuthorization, logger: Logger[IO]
) {
  import ConfigurationDeploymentDtos.Codecs.policyDecoder
  import HttpJsonCodecs._

  private val MaxBodyBytes = 256 * 1024
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid rollout request")

  private implicit val targetDecoder: Decoder[ConfigurationRolloutTarget] = Decoder.instance { cursor =>
    for {
      assignment <- cursor.get[UUID]("assignmentId")
      version <- cursor.get[Int]("expectedVersion")
      connection <- cursor.get[UUID]("connectionId")
      execution <- cursor.getOrElse[ConfigurationExecutionPolicy]("execution")(ConfigurationExecutionPolicy.Default)
    } yield ConfigurationRolloutTarget(assignment, version, connection, execution)
  }
  private implicit val preflightDecoder: Decoder[RolloutPreflightRequest] =
    Decoder.forProduct3("profileId", "revisionNumber", "targets")(RolloutPreflightRequest.apply)
  private implicit val rollbackDecoder: Decoder[ConfigurationRollbackMode] = Decoder.decodeString.emap { code =>
    ConfigurationRollbackMode.all.find(_.code == code).toRight("Invalid rollback mode")
  }
  private implicit val strategyDecoder: Decoder[ConfigurationRolloutStrategy] = Decoder.instance { cursor =>
    val d = ConfigurationRolloutStrategy.Default
    for {
      canary <- cursor.getOrElse[Int]("canaryCount")(d.canaryCount)
      batch <- cursor.getOrElse[Int]("batchSize")(d.batchSize)
      pause <- cursor.getOrElse[Int]("pauseSeconds")(d.pauseSeconds)
      stop <- cursor.getOrElse[Boolean]("stopOnFailure")(d.stopOnFailure)
      mode <- cursor.getOrElse[ConfigurationRollbackMode]("rollbackMode")(d.rollbackMode)
    } yield ConfigurationRolloutStrategy(canary, batch, pause, stop, mode)
  }
  private implicit val approvedDecoder: Decoder[ApprovedTargetRequest] = Decoder.instance { cursor =>
    for {
      assignment <- cursor.get[UUID]("assignmentId")
      version <- cursor.get[Int]("expectedVersion")
      connection <- cursor.get[UUID]("connectionId")
      updatedAt <- cursor.get[Instant]("connectionUpdatedAt")
      desired <- cursor.get[String]("desiredSha256")
      remote <- cursor.get[Option[String]]("expectedRemoteSha256")
      missing <- cursor.getOrElse[Boolean]("expectedRemoteMissing")(false)
      execution <- cursor.getOrElse[ConfigurationExecutionPolicy]("execution")(ConfigurationExecutionPolicy.Default)
    } yield ApprovedTargetRequest(assignment, version, connection, updatedAt, desired, remote, missing, execution)
  }
  private implicit val createDecoder: Decoder[CreateRolloutRequest] = Decoder.instance { cursor =>
    for {
      profile <- cursor.get[UUID]("profileId")
      revision <- cursor.get[Int]("revisionNumber")
      requestId <- cursor.get[UUID]("requestId")
      strategy <- cursor.getOrElse[ConfigurationRolloutStrategy]("strategy")(ConfigurationRolloutStrategy.Default)
      targets <- cursor.get[List[ApprovedTargetRequest]]("targets")
    } yield CreateRolloutRequest(profile, revision, requestId, strategy, targets)
  }

  private def remoteJson(value: ExpectedRemoteState): Json = value match {
    case ExpectedRemoteState.Missing => Json.obj("exists" -> Json.False, "sha256" -> Json.Null)
    case ExpectedRemoteState.Sha256(hash) => Json.obj("exists" -> Json.True, "sha256" -> Json.fromString(hash))
  }

  private def preflightJson(item: ConfigurationRolloutPreflightItem): Json = Json.obj(
    "assignmentId" -> Json.fromString(item.target.assignmentId.toString),
    "expectedVersion" -> Json.fromInt(item.target.expectedVersion),
    "connectionId" -> Json.fromString(item.target.connectionId.toString),
    "connectionName" -> item.connectionName.fold(Json.Null)(Json.fromString),
    "ready" -> Json.fromBoolean(item.ready),
    "desiredSha256" -> item.desiredSha256.fold(Json.Null)(Json.fromString),
    "connectionUpdatedAt" -> item.connectionUpdatedAt.fold(Json.Null)(instantEncoder(_)),
    "remote" -> item.expectedRemoteState.fold(Json.Null)(remoteJson),
    "changed" -> Json.fromBoolean(item.changed),
    "addedLines" -> Json.fromInt(item.addedLines),
    "removedLines" -> Json.fromInt(item.removedLines),
    "errorCode" -> item.errorCode.fold(Json.Null)(Json.fromString))

  private def rolloutJson(value: ConfigurationRolloutListItem): Json = {
    val rollout = value.rollout
    Json.obj(
      "id" -> Json.fromString(rollout.id.toString),
      "profileId" -> Json.fromString(rollout.profileId.toString),
      "profileRevisionNumber" -> Json.fromInt(rollout.profileRevisionNumber),
      "state" -> Json.fromString(rollout.state.code),
      "strategy" -> Json.obj(
        "canaryCount" -> Json.fromInt(rollout.strategy.canaryCount),
        "batchSize" -> Json.fromInt(rollout.strategy.batchSize),
        "pauseSeconds" -> Json.fromInt(rollout.strategy.pauseSeconds),
        "stopOnFailure" -> Json.fromBoolean(rollout.strategy.stopOnFailure),
        "rollbackMode" -> Json.fromString(rollout.strategy.rollbackMode.code)),
      "cancelRequested" -> Json.fromBoolean(rollout.cancelRequested),
      "rollbackRequested" -> Json.fromBoolean(rollout.rollbackRequested),
      "actor" -> Json.obj("id" -> Json.fromString(rollout.actorUserId.toString), "name" -> Json.fromString(value.actorName)),
      "counts" -> Json.obj("items" -> Json.fromInt(value.items), "succeeded" -> Json.fromInt(value.succeeded),
        "failed" -> Json.fromInt(value.failed)),
      "createdAt" -> instantEncoder(rollout.createdAt),
      "startedAt" -> rollout.startedAt.fold(Json.Null)(instantEncoder(_)),
      "finishedAt" -> rollout.finishedAt.fold(Json.Null)(instantEncoder(_)),
      "nextActionAt" -> rollout.nextActionAt.fold(Json.Null)(instantEncoder(_)))
  }

  private def itemJson(value: ConfigurationRolloutItemView): Json = {
    val item = value.item
    Json.obj(
      "id" -> Json.fromString(item.id.toString),
      "position" -> Json.fromInt(item.position),
      "assignmentId" -> Json.fromString(item.assignmentId.toString),
      "assignmentVersion" -> Json.fromInt(item.assignmentVersion),
      "resource" -> Json.obj("id" -> Json.fromString(item.resourceId.toString), "name" -> Json.fromString(value.resourceName)),
      "targetPath" -> Json.fromString(item.targetPath),
      "state" -> Json.fromString(item.state.code),
      "deployment" -> item.deploymentId.fold(Json.Null)(id => Json.obj(
        "id" -> Json.fromString(id.toString),
        "state" -> value.deploymentState.fold(Json.Null)(state => Json.fromString(state.code)),
        "phase" -> value.deploymentPhase.fold(Json.Null)(phase => Json.fromString(phase.code)),
        "failureCode" -> value.failureCode.fold(Json.Null)(Json.fromString),
        "startedAt" -> value.startedAt.fold(Json.Null)(instantEncoder(_)),
        "finishedAt" -> value.finishedAt.fold(Json.Null)(instantEncoder(_)))))
  }

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / "preflight" =>
      authorized(request) { context => body[RolloutPreflightRequest](request) { input =>
        respond(request, context, "configuration.rollout.preflight")(
          rollouts.preflight(context.organizationId, input.profileId, input.revisionNumber, input.targets)
            .flatMap(items => Ok(Json.obj("items" -> Json.arr(items.map(preflightJson): _*),
              "ready" -> Json.fromBoolean(items.forall(_.ready))))))
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" =>
      authorized(request) { context => body[CreateRolloutRequest](request) { input =>
        val approved = input.targets.traverse { item =>
          Option.when(item.expectedRemoteMissing != item.expectedRemoteSha256.isDefined) {
            ConfigurationRolloutApprovedTarget(ConfigurationRolloutTarget(item.assignmentId,
              item.expectedVersion, item.connectionId, item.execution),
              item.connectionUpdatedAt, item.desiredSha256, ExpectedRemoteState.of(item.expectedRemoteSha256))
          }
        }
        approved.fold[IO[Response[IO]]](BadRequest(invalid)) { targets =>
          respond(request, context, "configuration.rollout.request")(
            rollouts.launch(context.actor, input.profileId, input.revisionNumber, input.requestId,
              input.strategy, targets).flatMap { id =>
              Accepted(Json.obj("rolloutId" -> Json.fromString(id.toString), "state" -> Json.fromString("QUEUED")))
            })
        }
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / id =>
      authorized(request) { context => withId(id) { rolloutId =>
        respond(request, context, "configuration.rollout.detail")(
          rollouts.detail(context.organizationId, rolloutId).flatMap { case (rollout, items) =>
            Ok(rolloutJson(rollout).deepMerge(Json.obj("items" -> Json.arr(items.map(itemJson): _*))))
          })
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" / id / "cancel" =>
      authorized(request) { context => withId(id) { rolloutId =>
        body[Json](request) { input =>
          val rollback = input.hcursor.getOrElse[Boolean]("rollbackApplied")(false)
          rollback.fold(_ => BadRequest(invalid), rollbackApplied =>
            respond(request, context, "configuration.rollout.cancel")(
              rollouts.cancel(context.actor, rolloutId, rollbackApplied) *>
                Ok(Json.obj("cancelRequested" -> Json.True, "rollbackRequested" -> Json.fromBoolean(rollbackApplied)))))
        }
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-rollouts" =>
      authorized(request) { context =>
        HistoryPage.parse(request).flatMap(page => page.uuid("profileId").map(page -> _)) match {
          case Some((page, profile)) => respond(request, context, "configuration.rollout.history")(
            rollouts.history(context.organizationId, profile, page.cursor, page.limit + 1).flatMap { rows =>
              Ok(page.json(rows.map(rolloutJson), rows.lift(page.limit - 1).filter(_ => rows.size > page.limit)
                .map(last => last.rollout.createdAt -> last.rollout.id)))
            })
          case None => BadRequest(invalid)
        }
      }
  }

  private def authorized(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.DeployConfigurations)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(invalid))(next)

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes + 1L).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > MaxBodyBytes) BadRequest(invalid)
      else {
        val text = new String(bytes, StandardCharsets.UTF_8)
        io.circe.parser.decode[A](if (text.trim.isEmpty) "{}" else text).fold(_ => BadRequest(invalid), next)
      }
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: ConfigurationRolloutError => error match {
      case ConfigurationRolloutError.Invalid => BadRequest(ApiErrorResponse(error.code, error.getMessage))
      case ConfigurationRolloutError.NotFound => NotFound(ApiErrorResponse(error.code, error.getMessage))
      case _ => Conflict(ApiErrorResponse(error.code, error.getMessage))
    }
    case error: ConfigurationDeploymentError => ConfigurationDeploymentRoutes.status(error)
    case error => ReadModelHttp.failedAs(logger, "configuration.rollout.failed", request, operation,
      "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
  }
}
