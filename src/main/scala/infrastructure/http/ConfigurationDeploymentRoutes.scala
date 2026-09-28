package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{ConfigurationDeploymentError, ConfigurationDeployments}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.configuration.ExpectedRemoteState
import infrastructure.http.dto._
import io.circe.{Decoder, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Remote reads and writes have their own capability; HTTP never waits for a queued deployment. */
final class ConfigurationDeploymentRoutes[Tx[_]](
  deployments: ConfigurationDeployments[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import ConfigurationDeploymentDtos.Codecs._
  import HttpJsonCodecs._

  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid deployment request")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id / "deployment-preview" =>
      authorized(request) { context => withId(id) { assignmentId => body[DeploymentPreviewRequest](request) { input =>
        if (input.expectedAssignmentVersion < 1) BadRequest(invalid)
        else respond(request, context, "configuration.deployment.preview")(
          deployments.preview(context.organizationId, assignmentId,
            input.expectedAssignmentVersion, input.connectionId).flatMap(value => Ok(value)))
      } } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-assignments" / id / "deployments" =>
      authorized(request) { context => withId(id) { assignmentId => body[DeploymentRequest](request) { input =>
        if (input.expectedAssignmentVersion < 1 || input.expectedRemoteMissing == input.expectedRemoteSha256.isDefined)
          BadRequest(invalid)
        else {
          val expected = input.expectedRemoteSha256.fold[ExpectedRemoteState](ExpectedRemoteState.Missing)(
            ExpectedRemoteState.Sha256.apply)
          respond(request, context, "configuration.deployment.request")(
            deployments.request(context.actor, assignmentId, input.expectedAssignmentVersion,
              input.connectionId, expected, input.execution, input.requestId).flatMap { deploymentId =>
              Accepted(Json.obj("deploymentId" -> Json.fromString(deploymentId.toString),
                "state" -> Json.fromString("QUEUED")))
            })
        }
      } } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" / id =>
      authorized(request) { context => withId(id) { deploymentId =>
        respond(request, context, "configuration.deployment.detail")(
          deployments.detail(context.organizationId, deploymentId).flatMap(value => Ok(value)))
      } }

    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" / id / "cancel" =>
      authorized(request) { context => withId(id) { deploymentId =>
        respond(request, context, "configuration.deployment.cancel")(
          deployments.cancel(context.actor, deploymentId) *> Ok(Json.obj("cancelled" -> Json.True)))
      } }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "configuration-deployments" =>
      authorized(request) { context =>
        val params = request.uri.query.params
        val profile = params.get("profileId").traverse(value => Try(UUID.fromString(value)).toOption)
        val cursor = (params.get("beforeCreatedAt"), params.get("beforeId")) match {
          case (None, None) => Some(None)
          case (Some(at), Some(id)) => for {
            time <- Try(Instant.parse(at)).toOption
            uuid <- Try(UUID.fromString(id)).toOption
          } yield Some((time, uuid))
          case _ => None
        }
        val limit = params.get("limit").fold(Some(50))(value => Try(value.toInt).toOption.filter(n => n >= 1 && n <= 200))
        (profile, cursor, limit) match {
          case (Some(p), Some(c), Some(n)) => respond(request, context, "configuration.deployment.history")(
            deployments.history(context.organizationId, p, c, n).flatMap(values => Ok(values)))
          case _ => BadRequest(invalid)
        }
      }
  }

  private def authorized(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.DeployConfigurations)(next)

  private def withId(raw: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption.fold(BadRequest(invalid))(next)

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(65537).compile.toVector.map(_.toArray).flatMap { bytes =>
      if (bytes.length > 65536) BadRequest(invalid)
      else io.circe.parser.decode[A](new String(bytes, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalid), next)
    }

  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(
    action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ConfigurationDeploymentError =>
        val body = ApiErrorResponse(error.code, error.getMessage)
        error match {
          case ConfigurationDeploymentError.NotFound | ConfigurationDeploymentError.ConnectionMissing => NotFound(body)
          case ConfigurationDeploymentError.AssignmentChanged |
               ConfigurationDeploymentError.ConnectionChanged |
               ConfigurationDeploymentError.ConnectionNotSource |
               ConfigurationDeploymentError.AlreadyActive => Conflict(body)
          case ConfigurationDeploymentError.InvalidExecution |
               ConfigurationDeploymentError.InvalidExpectedRemote => BadRequest(body)
          case _ => Conflict(body)
        }
      case _: integration.ssh.RemoteFileTooLarge =>
        Conflict(ApiErrorResponse("CONFIGURATION_REMOTE_FILE_TOO_LARGE", "Remote file is too large"))
      case _: integration.ssh.SshTransportFailure.HostKeyMismatch =>
        Conflict(ApiErrorResponse("CONFIGURATION_HOST_KEY_MISMATCH", "SSH host key has changed"))
      case error => ReadModelHttp.failedAs(logger, "configuration.deployment.failed", request, operation,
        "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
    }
}
