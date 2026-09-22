package ru.bitec.app.ops
package infrastructure.http

import application.monitor.{CreateMonitorRule, ListMonitorRules, UpdateMonitorRule}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.metric.MetricCode
import domain.monitor.MonitorOperator
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
              validate(body.metricCode, body.operator, body.forSeconds).fold(
                _ => BadRequest(invalidMonitorRule),
                { case (metricCode, operator) =>
                  transactionRunner.run(
                    createMonitorRule.execute(
                      organizationId,
                      resourceId,
                      metricCode,
                      operator,
                      body.threshold,
                      body.forSeconds,
                      body.enabled
                    )
                  ).attempt.flatMap {
                    case Right(Some(rule)) => Created(MonitorRuleHttpMapper.toResponse(rule))
                    case Right(None) => NotFound(resourceNotFound)
                    case Left(_) => InternalServerError(internalError)
                  }
                }
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
              validate(body.metricCode, body.operator, body.forSeconds).fold(
                _ => BadRequest(invalidMonitorRule),
                { case (metricCode, operator) =>
                  transactionRunner.run(
                    updateMonitorRule.execute(
                      organizationId,
                      monitorRuleId,
                      metricCode,
                      operator,
                      body.threshold,
                      body.forSeconds,
                      body.enabled
                    )
                  ).attempt.flatMap {
                    case Right(Some(rule)) => Ok(MonitorRuleHttpMapper.toResponse(rule))
                    case Right(None) => NotFound(monitorRuleNotFound)
                    case Left(_) => InternalServerError(internalError)
                  }
                }
              )
          }
        case (Left(error), _) => BadRequest(error)
        case (_, Left(error)) => BadRequest(error)
      }
  }

  private def parseUuid(value: String, fieldName: String): Either[ApiErrorResponse, UUID] =
    Try(UUID.fromString(value)).toEither.leftMap { _ =>
      ApiErrorResponse("INVALID_REQUEST", s"Invalid $fieldName")
    }

  private def validate(
    metricCode: String,
    operator: String,
    forSeconds: Long
  ): Either[IllegalArgumentException, (MetricCode, MonitorOperator)] =
    for {
      parsedMetricCode <- MetricCode.fromCode(metricCode)
      parsedOperator <- MonitorOperator.fromCode(operator)
      _ <- Either.cond(
        forSeconds >= 0,
        (),
        new IllegalArgumentException("forSeconds must not be negative")
      )
    } yield parsedMetricCode -> parsedOperator
}
