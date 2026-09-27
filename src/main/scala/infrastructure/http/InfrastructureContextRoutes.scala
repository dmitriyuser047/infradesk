package ru.bitec.app.ops
package infrastructure.http

import application.context.{
  GetConnectionInfrastructureSummary,
  GetResourceContext,
  ListConnectionInfrastructureCounts,
  ListConnectionResources,
  ListEnvironmentResourceSources
}
import application.incident.{IncidentPageRequest, ListConnectionIncidents, ListResourceIncidents}
import application.port.{IncidentListItem, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, InfrastructureContextResponses}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.util.Try

/** Where connections, resources and incidents meet: each endpoint answers one question a detail
  * page asks about its neighbourhood, with a fixed number of statements however many rows it
  * returns. Reading only; the ordinary organization read permission applies.
  *
  * The runner is a read-only snapshot, so a composite read — counts and the previews they
  * summarize — sees one committed state. An unexpected failure is logged with its cause and the
  * identifiers of the request, and answered with a generic error.
  */
final class InfrastructureContextRoutes[Tx[_]](
  connectionSummary: GetConnectionInfrastructureSummary[Tx],
  connectionResources: ListConnectionResources[Tx],
  connectionIncidents: ListConnectionIncidents[Tx],
  connectionCounts: ListConnectionInfrastructureCounts[Tx],
  resourceContext: GetResourceContext[Tx],
  resourceIncidents: ListResourceIncidents[Tx],
  environmentResourceSources: ListEnvironmentResourceSources[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import InfrastructureContextResponses.Codecs._

  private val connectionNotFound = ApiErrorResponse("CONNECTION_NOT_FOUND", "Connection was not found")
  private val resourceNotFound = ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "infrastructure-summary" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          val fail = failed(request, "connection.summary", "organizationId" -> organizationId, "connectionId" -> connectionId) _
          runner.run(connectionSummary.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(summary)) => InfrastructureContextResponses.summary(summary).fold(fail, Ok(_))
            case Right(None) => NotFound(connectionNotFound)
            case Left(error) => fail(error)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "resources" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          val fail = failed(request, "connection.resources", "organizationId" -> organizationId, "connectionId" -> connectionId) _
          runner.run(connectionResources.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(resources)) => resources.traverse(InfrastructureContextResponses.located).fold(fail, Ok(_))
            case Right(None) => NotFound(connectionNotFound)
            case Left(error) => fail(error)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "incidents" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          withPage(request) { page =>
            incidents(runner.run(connectionIncidents.execute(organizationId, connectionId, page)), connectionNotFound,
              failed(request, "connection.incidents", "organizationId" -> organizationId, "connectionId" -> connectionId))
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connection-infrastructure" =>
      read(request) { organizationId =>
        runner.run(connectionCounts.execute(organizationId)).attempt.flatMap {
          case Right(counts) => Ok(counts.map(InfrastructureContextResponses.counts))
          case Left(error) => failed(request, "connection.counts", "organizationId" -> organizationId)(error)
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "context" =>
      read(request) { organizationId =>
        withId(resource, "resourceId") { resourceId =>
          val fail = failed(request, "resource.context", "organizationId" -> organizationId, "resourceId" -> resourceId) _
          runner.run(resourceContext.execute(organizationId, resourceId)).attempt.flatMap {
            case Right(Some(context)) => InfrastructureContextResponses.resourceContext(context).fold(fail, Ok(_))
            case Right(None) => NotFound(resourceNotFound)
            case Left(error) => fail(error)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "incidents" =>
      read(request) { organizationId =>
        withId(resource, "resourceId") { resourceId =>
          withPage(request) { page =>
            incidents(runner.run(resourceIncidents.execute(organizationId, resourceId, page)), resourceNotFound,
              failed(request, "resource.incidents", "organizationId" -> organizationId, "resourceId" -> resourceId))
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "environments" / environment / "resource-sources" =>
      read(request) { organizationId =>
        withId(environment, "environmentId") { environmentId =>
          runner.run(environmentResourceSources.execute(organizationId, environmentId)).attempt.flatMap {
            case Right(sources) => Ok(sources.map(InfrastructureContextResponses.sources))
            case Left(error) =>
              failed(request, "environment.resourceSources", "organizationId" -> organizationId, "environmentId" -> environmentId)(error)
          }
        }
      }
  }

  private def read(request: Request[IO])(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ReadOrganization)(context => next(context.organizationId))

  private def failed(request: Request[IO], operation: String, context: (String, Any)*)(error: Throwable): IO[Response[IO]] =
    ReadModelHttp.failed(logger, request, operation, context: _*)(error)

  private def incidents(
    program: IO[Option[List[IncidentListItem]]],
    notFound: ApiErrorResponse,
    fail: Throwable => IO[Response[IO]]
  ): IO[Response[IO]] =
    program.attempt.flatMap {
      case Right(Some(items)) => Ok(items.map(InfrastructureContextResponses.incident))
      case Right(None) => NotFound(notFound)
      case Left(error) => fail(error)
    }

  private def withId(raw: String, name: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ReadModelHttp.invalid(name))
    }

  private def withPage(request: Request[IO])(next: IncidentPageRequest => IO[Response[IO]]): IO[Response[IO]] =
    ReadModelHttp.incidentPage(request) match {
      case Right(page) => next(page)
      case Left(error) => BadRequest(error)
    }
}
