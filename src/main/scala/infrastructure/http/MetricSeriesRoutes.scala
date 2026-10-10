package ru.bitec.app.ops
package infrastructure.http

import application.port.TransactionRunner
import application.resource.{GetResourceMetricSeries, InvalidMetricSeriesPeriodException, MetricSeries}
import cats.effect.IO
import domain.auth.OrganizationPermission
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import io.circe.syntax._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger

import java.time.Instant
import java.util.UUID
import scala.util.Try

/**
 * `GET .../resources/{id}/metric-series?from&to`: chart points at the resolution the period calls
 * for — every observation up to six hours, five-minute buckets up to three days, hours up to
 * 45 days and days beyond, 400 days at most.
 */
final class MetricSeriesRoutes[Tx[_]](
  getSeries: GetResourceMetricSeries[Tx],
  runner: TransactionRunner[IO, Tx],
  authorization: OrganizationAuthorization,
  logger: Logger[IO]
) {
  import HttpJsonCodecs._

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "resources" / resource / "metric-series" =>
      authorization.require(request, OrganizationPermission.ReadOrganization) { _ =>
        val params = request.uri.query.params
        (uuid(org), uuid(resource), params.get("from").flatMap(instant), params.get("to").flatMap(instant)) match {
          case (Some(organizationId), Some(resourceId), Some(from), Some(to)) =>
            runner.run(getSeries.execute(organizationId, resourceId, from, to)).attempt.flatMap {
              case Right(Some(series)) => Ok(response(series))
              case Right(None) => NotFound(ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found"))
              case Left(_: InvalidMetricSeriesPeriodException) => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid period"))
              case Left(failure) => logger.error(failure)(s"metrics.series.failed organizationId=$organizationId resourceId=$resourceId") *>
                InternalServerError(ApiErrorResponse("INTERNAL_ERROR", "Internal server error"))
            }
          case _ => BadRequest(ApiErrorResponse("INVALID_REQUEST", "Invalid metric series request"))
        }
      }
  }

  private def response(series: MetricSeries): Json = Json.obj(
    "resolution" -> series.resolution.code.asJson,
    "from" -> series.from.asJson,
    "to" -> series.to.asJson,
    "points" -> series.points.map(point => Json.obj(
      "metricCode" -> point.metricCode.code.asJson,
      "bucketStart" -> point.bucketStart.asJson,
      "average" -> point.average.asJson,
      "minimum" -> point.minimum.asJson,
      "maximum" -> point.maximum.asJson,
      "samples" -> point.samples.asJson
    )).asJson
  )

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption
  private def instant(raw: String): Option[Instant] = Try(Instant.parse(raw)).toOption
}
