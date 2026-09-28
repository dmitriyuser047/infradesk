package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{ConfigurationDeploymentError, ConfigurationDeployments}
import application.port.RemoteConfigurationFailure
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration.ExpectedRemoteState
import infrastructure.http.dto._
import io.circe.syntax._
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Remote reads and writes need DEPLOY_CONFIGURATIONS, a capability of its own; history needs the
  * configuration capability. HTTP never waits for a queued deployment, and neither a diff nor a
  * remote path's content is ever logged.
  */
final class ConfigurationDeploymentRoutes[Tx[_]](
  deployments: ConfigurationDeployments[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import ConfigurationDeploymentDtos.Codecs._
  import HttpJsonCodecs._

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid deployment request")
  private val MaxBodyBytes = 64 * 1024
  private val MaxSummaries = 100

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id / "deployment-preview" =>
      deploy(request) { context => withId(id) { assignmentId => body[DeploymentPreviewRequest](request) { input =>
        if (input.expectedAssignmentVersion < 1) BadRequest(invalid)
        else respond(request, context, "configuration.deployment.preview")(
          deployments.preview(context.organizationId, assignmentId,
            input.expectedAssignmentVersion, input.connectionId).flatMap(value => Ok(value)))
      } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id / "deployments" =>
      deploy(request) { context => withId(id) { assignmentId => body[DeploymentRequest](request) { input =>
        if (input.expectedAssignmentVersion < 1 || input.expectedRemoteMissing == input.expectedRemoteSha256.isDefined)
          BadRequest(invalid)
        else respond(request, context, "configuration.deployment.request")(
          deployments.request(context.actor, assignmentId, input.expectedAssignmentVersion,
            input.connectionId, ExpectedRemoteState.of(input.expectedRemoteSha256), input.execution,
            input.requestId, input.retryOfDeploymentId).flatMap { deploymentId =>
            Accepted(Json.obj("deploymentId" -> Json.fromString(deploymentId.toString),
              "state" -> Json.fromString("QUEUED")))
          })
      } } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployment-summaries" =>
      read(request) { context =>
        val raw = request.uri.query.multiParams.getOrElse("assignmentId", Nil).toList
        raw.traverse(value => Try(UUID.fromString(value)).toOption) match {
          case Some(ids) if ids.nonEmpty && ids.size <= MaxSummaries =>
            respond(request, context, "configuration.deployment.summaries")(
              deployments.summaries(context.organizationId, ids).flatMap(values => Ok(values.asJson)))
          case _ => BadRequest(invalid)
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" / id =>
      read(request) { context => withId(id) { deploymentId =>
        respond(request, context, "configuration.deployment.detail")(
          deployments.detail(context.organizationId, deploymentId).flatMap(value => Ok(value.asJson)))
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" / id / "cancel" =>
      deploy(request) { context => withId(id) { deploymentId =>
        respond(request, context, "configuration.deployment.cancel")(
          deployments.cancel(context.actor, deploymentId) *> Ok(Json.obj("cancelRequested" -> Json.True)))
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" =>
      read(request) { context =>
        HistoryPage.parse(request) match {
          case Some(page) =>
            val profile = page.uuid("profileId")
            val resource = page.uuid("resourceId")
            (profile, resource) match {
              case (Some(p), Some(r)) => respond(request, context, "configuration.deployment.history")(
                deployments.history(context.organizationId, p, r, page.cursor, page.limit + 1).flatMap { rows =>
                  Ok(page.json(rows.map(_.asJson), rows.lift(page.limit - 1).filter(_ => rows.size > page.limit)
                    .map(last => last.deployment.createdAt -> last.deployment.id)))
                })
              case _ => BadRequest(invalid)
            }
          case None => BadRequest(invalid)
        }
      }
  }

  private def deploy(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.DeployConfigurations)(next)

  private def read(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageConfigurations)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(invalid))(next)

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes + 1L).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > MaxBodyBytes) BadRequest(invalid)
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalid), next)
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ConfigurationDeploymentError => ConfigurationDeploymentRoutes.status(error)
      case error => ReadModelHttp.failedAs(logger, "configuration.deployment.failed", request, operation,
        "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
    }
}

object ConfigurationDeploymentRoutes {
  import HttpJsonCodecs._

  /** Expected outcomes are typed answers with a stable code, never a 500. */
  def status(error: ConfigurationDeploymentError): IO[Response[IO]] = {
    val body = ApiErrorResponse(error.code, error.getMessage)
    error match {
      case ConfigurationDeploymentError.NotFound | ConfigurationDeploymentError.AssignmentNotFound |
           ConfigurationDeploymentError.ConnectionMissing => NotFound(body)
      case ConfigurationDeploymentError.InvalidExecution | ConfigurationDeploymentError.InvalidExpectedRemote |
           ConfigurationDeploymentError.RequestReused | ConfigurationDeploymentError.InvalidRetry => BadRequest(body)
      case ConfigurationDeploymentError.Remote(RemoteConfigurationFailure.Unavailable) => ServiceUnavailable(body)
      case _ => Conflict(body)
    }
  }
}

/** Keyset pagination shared by deployment and rollout history. */
private[http] final case class HistoryPage(params: Map[String, String], cursor: Option[(Instant, UUID)], limit: Int) {
  import HttpJsonCodecs._

  /** None when the parameter is absent; a malformed value is a malformed request. */
  def uuid(name: String): Option[Option[UUID]] = params.get(name).traverse(value => Try(UUID.fromString(value)).toOption)

  def json(items: List[Json], next: Option[(Instant, UUID)]): Json = Json.obj(
    "items" -> Json.arr(items.take(limit): _*),
    "nextCursor" -> next.fold(Json.Null) { case (at, id) =>
      Json.obj("beforeCreatedAt" -> instantEncoder(at), "beforeId" -> Json.fromString(id.toString))
    })
}

private[http] object HistoryPage {
  val DefaultLimit = 25
  val MaxLimit = 100

  def parse(request: Request[IO]): Option[HistoryPage] = {
    val params = request.uri.query.params
    val cursor = (params.get("beforeCreatedAt"), params.get("beforeId")) match {
      case (None, None) => Some(None)
      case (Some(at), Some(id)) =>
        (Try(Instant.parse(at)).toOption, Try(UUID.fromString(id)).toOption).mapN((time, uuid) => Some(time -> uuid))
      case _ => None
    }
    val limit = params.get("limit").fold[Option[Int]](Some(DefaultLimit))(value =>
      Try(value.toInt).toOption.filter(n => n >= 1 && n <= MaxLimit))
    (cursor, limit).mapN((c, n) => HistoryPage(params, c, n))
  }
}
