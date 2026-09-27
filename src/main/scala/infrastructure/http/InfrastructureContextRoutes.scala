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
import application.port.{IncidentCursor, IncidentListItem, TransactionRunner}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.incident.IncidentStatus
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, InfrastructureContextResponses}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._

import java.time.Instant
import java.util.UUID
import scala.util.Try

/** Where connections, resources and incidents meet: each endpoint answers one question a detail
  * page asks about its neighbourhood, with a fixed number of statements however many rows it
  * returns. Reading only; the ordinary organization read permission applies.
  *
  * The runner is a read-only snapshot, so a composite read — counts and the previews they
  * summarize — sees one committed state.
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
  authorization: OrganizationAuthorization
) {
  import HttpJsonCodecs._
  import InfrastructureContextResponses.Codecs._

  private val connectionNotFound = ApiErrorResponse("CONNECTION_NOT_FOUND", "Connection was not found")
  private val resourceNotFound = ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "infrastructure-summary" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          runner.run(connectionSummary.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(summary)) => InfrastructureContextResponses.summary(summary).fold(_ => failed, Ok(_))
            case Right(None) => NotFound(connectionNotFound)
            case Left(_) => failed
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "resources" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          runner.run(connectionResources.execute(organizationId, connectionId)).attempt.flatMap {
            case Right(Some(resources)) =>
              resources.traverse(InfrastructureContextResponses.located).fold(_ => failed, Ok(_))
            case Right(None) => NotFound(connectionNotFound)
            case Left(_) => failed
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connections" / connection / "incidents" =>
      read(request) { organizationId =>
        withId(connection, "connectionId") { connectionId =>
          withPage(request) { page =>
            incidents(runner.run(connectionIncidents.execute(organizationId, connectionId, page)), connectionNotFound)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "connection-infrastructure" =>
      read(request) { organizationId =>
        runner.run(connectionCounts.execute(organizationId)).attempt.flatMap {
          case Right(counts) => Ok(counts.map(InfrastructureContextResponses.counts))
          case Left(_) => failed
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "context" =>
      read(request) { organizationId =>
        withId(resource, "resourceId") { resourceId =>
          runner.run(resourceContext.execute(organizationId, resourceId)).attempt.flatMap {
            case Right(Some(context)) => InfrastructureContextResponses.resourceContext(context).fold(_ => failed, Ok(_))
            case Right(None) => NotFound(resourceNotFound)
            case Left(_) => failed
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resource / "incidents" =>
      read(request) { organizationId =>
        withId(resource, "resourceId") { resourceId =>
          withPage(request) { page =>
            incidents(runner.run(resourceIncidents.execute(organizationId, resourceId, page)), resourceNotFound)
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "environments" / environment / "resource-sources" =>
      read(request) { organizationId =>
        withId(environment, "environmentId") { environmentId =>
          runner.run(environmentResourceSources.execute(organizationId, environmentId)).attempt.flatMap {
            case Right(sources) => Ok(sources.map(InfrastructureContextResponses.sources))
            case Left(_) => failed
          }
        }
      }
  }

  private def read(request: Request[IO])(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ReadOrganization)(context => next(context.organizationId))

  private def failed: IO[Response[IO]] = InternalServerError(internalError)

  private def incidents(program: IO[Option[List[IncidentListItem]]], notFound: ApiErrorResponse): IO[Response[IO]] =
    program.attempt.flatMap {
      case Right(Some(items)) => Ok(items.map(InfrastructureContextResponses.incident))
      case Right(None) => NotFound(notFound)
      case Left(_) => failed
    }

  private def withId(raw: String, name: String)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(id) => next(id)
      case None => BadRequest(ApiErrorResponse("INVALID_REQUEST", s"Invalid $name"))
    }

  /** `status`, `limit` and a two-part cursor, `beforeOpenedAt` with `beforeId`, that travel together
    * so a page continues after one exact row even when several incidents opened at the same instant.
    */
  private def withPage(request: Request[IO])(next: IncidentPageRequest => IO[Response[IO]]): IO[Response[IO]] = {
    val params = request.uri.query.params
    val status: Either[ApiErrorResponse, Option[IncidentStatus]] = params.get("status").traverse(IncidentStatus.fromCode).leftMap(_ => invalid("status"))
    val cursor: Either[ApiErrorResponse, Option[IncidentCursor]] = (params.get("beforeOpenedAt"), params.get("beforeId")) match {
      case (None, None) => Right(None)
      case (Some(openedAt), Some(id)) =>
        (Try(Instant.parse(openedAt)).toOption, Try(UUID.fromString(id)).toOption)
          .mapN(IncidentCursor.apply)
          .map(Option(_))
          .toRight(invalid("incident cursor"))
      case _ => Left(invalid("incident cursor"))
    }
    val limit: Either[ApiErrorResponse, Int] = params.get("limit") match {
      case None => Right(IncidentPageRequest.DefaultLimit)
      case Some(raw) =>
        Try(raw.toInt).toOption.filter(value => value > 0 && value <= IncidentPageRequest.MaxLimit).toRight(invalid("limit"))
    }
    (status, cursor, limit).mapN(IncidentPageRequest.apply) match {
      case Right(page) => next(page)
      case Left(error) => BadRequest(error)
    }
  }

  private def invalid(name: String): ApiErrorResponse = ApiErrorResponse("INVALID_REQUEST", s"Invalid $name")
}
