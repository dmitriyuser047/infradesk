package ru.bitec.app.ops
package infrastructure.http

import application.incident.{GetIncidentDetail, ListIncidents}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs, InfrastructureContextResponses}
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.util.UUID
import scala.util.Try

/** The incident list and detail. Both carry each incident's infrastructure context — resource,
  * environment, project, rule and source connections — read in one statement. The list is a
  * keyset page (`status`, `limit`, `beforeOpenedAt` with `beforeId`), never the whole history.
  */
final class IncidentRoutes[Tx[_]](
  get: GetIncidentDetail[Tx],
  list: ListIncidents[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._
  import InfrastructureContextResponses.Codecs._

  private val notFound = ApiErrorResponse("INCIDENT_NOT_FOUND", "Incident was not found")

  private def uuid(raw: String, name: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(raw)).toEither.leftMap(_ => ReadModelHttp.invalid(name))

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "incidents" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { _ =>
        (uuid(org, "organizationId"), ReadModelHttp.incidentPage(request)) match {
          case (Left(error), _) => BadRequest(error)
          case (_, Left(error)) => BadRequest(error)
          case (Right(organizationId), Right(page)) =>
            runner.run(list.execute(organizationId, page)).attempt.flatMap {
              case Right(items) => Ok(items.map(InfrastructureContextResponses.incident))
              case Left(error) => ReadModelHttp.failed(logger, request, "incidents.list", "organizationId" -> organizationId)(error)
            }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "incidents" / id =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { _ =>
        (uuid(org, "organizationId"), uuid(id, "incidentId")) match {
          case (Left(error), _) => BadRequest(error)
          case (_, Left(error)) => BadRequest(error)
          case (Right(organizationId), Right(incidentId)) =>
            runner.run(get.execute(organizationId, incidentId)).attempt.flatMap {
              case Right(Some(item)) => Ok(InfrastructureContextResponses.incident(item))
              case Right(None) => NotFound(notFound)
              case Left(error) => ReadModelHttp.failed(logger, request, "incidents.detail",
                "organizationId" -> organizationId, "incidentId" -> incidentId)(error)
            }
        }
      }
  }
}
