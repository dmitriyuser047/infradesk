package ru.bitec.app.ops
package infrastructure.http

import application.monitor.{CreateMonitorRule, ListMonitorRules, MonitorRuleCommand, UpdateMonitorRule}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.{InvalidMonitorRule, MonitorOperator}
import infrastructure.http.dto.{
  ApiErrorResponse,
  CreateMonitorRuleRequest,
  HttpJsonCodecs,
  UpdateMonitorRuleRequest
}
import infrastructure.http.mapper.MonitorRuleHttpMapper
import org.http4s.HttpRoutes
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._

import java.util.UUID
import scala.util.Try

final class MonitorRuleRoutes[Tx[_]](
  listMonitorRules: ListMonitorRules[Tx],
  createMonitorRule: CreateMonitorRule[Tx],
  updateMonitorRule: UpdateMonitorRule[Tx],
  transactionRunner: TransactionRunner[IO, Tx]
) {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import HttpJsonCodecs._

  private val resourceNotFound = ApiErrorResponse("RESOURCE_NOT_FOUND", "Resource was not found")
  private val monitorRuleNotFound = ApiErrorResponse("MONITOR_RULE_NOT_FOUND", "Monitor rule was not found")
  private val internalError = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")
  private val invalidMonitorRule = ApiErrorResponse("INVALID_REQUEST", "Invalid monitor rule")
  private val invalidRequest = ApiErrorResponse("INVALID_REQUEST", "Invalid request")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "v1" / "organizations" / organizationIdValue / "resources" / resourceIdValue / "monitor-rules" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(resourceIdValue, "resourceId")) match {
        case (Right(organizationId), Right(resourceId)) =>
          transactionRunner.run(listMonitorRules.execute(organizationId, resourceId)).attempt.flatMap {
            case Right(Some(rules)) => Ok(rules.map(MonitorRuleHttpMapper.toResponse))
            case Right(None) => NotFound(resourceNotFound)
            case Left(_) => InternalServerError(internalError)
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / organizationIdValue / "resources" / resourceIdValue / "monitor-rules" =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(resourceIdValue, "resourceId")) match {
        case (Right(organizationId), Right(resourceId)) =>
          request.as[CreateMonitorRuleRequest].attempt.flatMap {
            case Left(_) => BadRequest(invalidRequest)
            case Right(body) =>
              command(body.metricCode, body.operator, body.threshold, body.forSeconds,
                body.noDataSeconds, body.enabled).fold(
                _ => BadRequest(invalidMonitorRule),
                value => respond(
                  transactionRunner.run(createMonitorRule.execute(organizationId, resourceId, value)),
                  rule => Created(MonitorRuleHttpMapper.toResponse(rule)),
                  resourceNotFound
                )
              )
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / organizationIdValue / "monitor-rules" / monitorRuleIdValue =>
      (parseUuid(organizationIdValue, "organizationId"), parseUuid(monitorRuleIdValue, "monitorRuleId")) match {
        case (Right(organizationId), Right(monitorRuleId)) =>
          request.as[UpdateMonitorRuleRequest].attempt.flatMap {
            case Left(_) => BadRequest(invalidRequest)
            case Right(body) =>
              command(body.metricCode, body.operator, body.threshold, body.forSeconds,
                body.noDataSeconds, body.enabled).fold(
                _ => BadRequest(invalidMonitorRule),
                value => respond(
                  transactionRunner.run(updateMonitorRule.execute(organizationId, monitorRuleId, value)),
                  rule => Ok(MonitorRuleHttpMapper.toResponse(rule)),
                  monitorRuleNotFound
                )
              )
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }
  }

  private def respond(
    program: IO[Option[domain.monitor.MonitorRule]],
    onFound: domain.monitor.MonitorRule => IO[org.http4s.Response[IO]],
    onMissing: ApiErrorResponse
  ): IO[org.http4s.Response[IO]] =
    program.attempt.flatMap {
      case Right(Some(rule)) => onFound(rule)
      case Right(None) => NotFound(onMissing)
      // Business validation lives in the application layer; it is still a client error here.
      case Left(_: InvalidMonitorRule) => BadRequest(invalidMonitorRule)
      case Left(_) => InternalServerError(internalError)
    }

  private def parseUuid(value: String, fieldName: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $fieldName")
    }

  private def command(
    metricCode: String,
    operator: String,
    threshold: BigDecimal,
    forSeconds: Long,
    noDataSeconds: Long,
    enabled: Boolean
  ): Either[IllegalArgumentException, MonitorRuleCommand] =
    for {
      parsedMetricCode <- MetricCode.fromCode(metricCode)
      parsedOperator <- MonitorOperator.fromCode(operator)
    } yield MonitorRuleCommand(parsedMetricCode, parsedOperator, threshold, forSeconds, noDataSeconds, enabled)
}
