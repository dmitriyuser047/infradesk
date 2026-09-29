package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationError, IntegrationManagement, IntegrationProviderRegistry,
  TestIntegration, CreateIntegrationCommand, UpdateIntegrationCommand}
import application.port.TransactionRunner
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.{Integration, IntegrationProviderType, RemnawaveCredential}
import infrastructure.http.dto._
import org.http4s.{HttpRoutes, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import java.util.UUID
import scala.util.Try

final class IntegrationRoutes[Tx[_]](management: IntegrationManagement[Tx],
  test: TestIntegration[Tx], providers: IntegrationProviderRegistry[IO],
  runner: TransactionRunner[IO, Tx], authorization: OrganizationAuthorization) {
  import CirceEntityDecoder._
  import CirceEntityEncoder._
  import HttpJsonCodecs._

  private val notFound = ApiErrorResponse("INTEGRATION_NOT_FOUND", "Integration was not found")
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid integration request")
  private val internal = ApiErrorResponse("INTERNAL_ERROR", "Internal server error")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integration-providers" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withOrganization(org, context.organizationId) { _ =>
          Ok(providers.all.map(provider => IntegrationProviderResponse(provider.providerType.code,
            provider.displayName, provider.capabilities.toList.map(_.code).sorted)))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withOrganization(org, context.organizationId) { organizationId =>
          respond(runner.run(management.list(organizationId)).flatMap(values => Ok(values.map(toResponse))))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          respond(runner.run(management.get(organizationId, integrationId)).flatMap {
            case Some(value) => Ok(toResponse(value))
            case None => NotFound(notFound)
          })
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withOrganization(org, context.organizationId) { _ =>
          request.as[CreateIntegrationRequest].attempt.flatMap {
            case Left(_) => BadRequest(invalid)
            case Right(body) => IntegrationProviderType.fromCode(body.providerType) match {
              case Left(_) => BadRequest(ApiErrorResponse("INTEGRATION_PROVIDER_UNSUPPORTED", "Unsupported provider"))
              case Right(providerType) if providers.find(providerType).isEmpty =>
                BadRequest(ApiErrorResponse("INTEGRATION_PROVIDER_UNSUPPORTED", "Unsupported provider"))
              case Right(providerType) =>
                val credential = RemnawaveCredential(body.credentials.apiToken, body.credentials.caddyApiKey)
                respond(runner.run(management.create(context.actor, CreateIntegrationCommand(
                  body.name, providerType, body.baseUrl, credential))).flatMap(value => Created(toResponse(value))))
            }
          }
        }
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "integrations" / id =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          request.as[UpdateIntegrationRequest].attempt.flatMap {
            case Left(_) => BadRequest(invalid)
            case Right(body) =>
              val credential = body.credentials.map(v => RemnawaveCredential(v.apiToken, v.caddyApiKey))
              respond(runner.run(management.update(context.actor, integrationId,
                UpdateIntegrationCommand(body.name, body.baseUrl, credential))).flatMap(value => Ok(toResponse(value))))
          }
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "enable" =>
      lifecycle(request, org, id, enabled = true)
    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "disable" =>
      lifecycle(request, org, id, enabled = false)

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / org / "integrations" / id =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          respond(runner.run(management.delete(context.actor, integrationId)) *> NoContent())
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "test" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          respond(test.execute(context.actor, integrationId).flatMap(result =>
            Ok(IntegrationTestResponse(result.ok, result.providerType.code, result.latencyMs))))
        }
      }
  }

  private def lifecycle(request: org.http4s.Request[IO], rawOrganization: String, id: String,
    enabled: Boolean): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
      withIntegration(rawOrganization, context.organizationId, id) { (_, integrationId) =>
        respond(runner.run(management.setEnabled(context.actor, integrationId, enabled))
          .flatMap(value => Ok(toResponse(value))))
      }
    }

  private def toResponse(value: Integration): IntegrationResponse =
    IntegrationResponse(value.id, value.name, value.providerType.code, value.baseUrl.value,
      value.enabled, IntegrationCredentialStatus(apiTokenConfigured = true,
        value.caddyApiKeyConfigured), value.createdAt, value.updatedAt)

  private def withOrganization(raw: String, expected: UUID)(next: UUID => IO[Response[IO]]): IO[Response[IO]] =
    Try(UUID.fromString(raw)).toOption match {
      case Some(value) if value == expected => next(value)
      case Some(_) => NotFound(notFound)
      case None => BadRequest(invalid)
    }

  private def withIntegration(rawOrg: String, expected: UUID, rawId: String)(
    next: (UUID, UUID) => IO[Response[IO]]): IO[Response[IO]] =
    withOrganization(rawOrg, expected) { org =>
      Try(UUID.fromString(rawId)).toOption match {
        case Some(id) => next(org, id)
        case None => BadRequest(invalid)
      }
    }

  private def respond(action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case error: IntegrationError => error.code match {
      case "INTEGRATION_NOT_FOUND" => NotFound(notFound)
      case "INTEGRATION_TIMEOUT" => GatewayTimeout(ApiErrorResponse(error.code, "Integration request timed out"))
      case code if code.startsWith("INTEGRATION_") &&
        !Set("INTEGRATION_CREDENTIAL_MISSING", "INTEGRATION_CREDENTIAL_INVALID").contains(code) =>
        BadGateway(ApiErrorResponse(code, "Integration check failed"))
      case "INVALID_REQUEST" => BadRequest(invalid)
      case _ => InternalServerError(internal)
    }
    case error: org.postgresql.util.PSQLException if error.getSQLState == "23505" =>
      Conflict(ApiErrorResponse("INTEGRATION_NAME_CONFLICT", "Integration name already exists"))
    case _ => InternalServerError(internal)
  }
}
