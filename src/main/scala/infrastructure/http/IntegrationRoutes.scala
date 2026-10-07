package ru.bitec.app.ops
package infrastructure.http

import application.integration.{IntegrationBindings, IntegrationError, IntegrationManagement,
  IntegrationProviderRegistry, IntegrationSync, TestIntegration, CreateIntegrationCommand, UpdateIntegrationCommand}
import application.port.{IntegrationInventoryQuery, IntegrationInventorySummary, IntegrationOverview,
  IntegrationSyncSessionRepository, InventoryFilter, InventoryTypeCounts, TransactionRunner}
import cats.Monad
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration.{Integration, IntegrationObjectType, IntegrationProviderType, RemnawaveCredential,
  RemnawaveNodeState}
import infrastructure.http.dto._
import io.circe.{Decoder, Json}
import io.circe.syntax._
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.{CirceEntityDecoder, CirceEntityEncoder}
import org.http4s.dsl.io._
import java.util.UUID
import scala.util.Try

final class IntegrationRoutes[Tx[_]: Monad](management: IntegrationManagement[Tx],
  test: TestIntegration[Tx], providers: IntegrationProviderRegistry[IO],
  runner: TransactionRunner[IO, Tx], authorization: OrganizationAuthorization,
  sync: IntegrationSync[Tx], bindings: IntegrationBindings[Tx], inventory: IntegrationInventoryQuery[Tx],
  sessions: IntegrationSyncSessionRepository[Tx]) {
  import IntegrationRoutes._
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
          respond(runner.run((management.list(organizationId), inventory.overviews(organizationId)).tupled)
            .flatMap { case (values, overviews) => Ok(values.map(value => withOverview(value, overviews.get(value.id)))) })
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          respond(runner.run(management.get(organizationId, integrationId).flatMap(_.traverse(value =>
            inventory.overviews(organizationId).map(overviews => withOverview(value, overviews.get(value.id)))))).flatMap {
            case Some(value) => Ok(value)
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

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "abandon-recovery-and-delete" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          respond(runner.run(management.delete(context.actor, integrationId, abandonRecovery = true)) *> NoContent())
        }
      }

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "test" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          respond(test.execute(context.actor, integrationId).flatMap(result =>
            Ok(IntegrationTestResponse(result.ok, result.providerType.code, result.latencyMs,
              result.nodeApi.map(api => NodeApiCompatibilityResponse(api.serverVersion, api.apiGeneration,
                api.sourceCommit, api.capabilities.toList.map(_.code).sorted, api.provisioningReady, api.blocker))))))
        }
      }

    // Read-only observation, inventory and manual bindings.

    case request @ POST -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "sync" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          // Answers with the finished session, completed or failed: a remote failure is its content.
          respond(sync.manual(context.actor, integrationId).flatMap(value => Ok(IntegrationInventoryJson.session(value))))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "sync-sessions" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          intParam(request, "limit", 20, 1, 50) match {
            case None => BadRequest(invalid)
            case Some(limit) => respond(existing(organizationId, integrationId)(
              sessions.recent(organizationId, integrationId, limit)).flatMap(values =>
              Ok(Json.obj("items" -> values.map(IntegrationInventoryJson.session).asJson))))
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "inventory" / "summary" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          respond(existing(organizationId, integrationId)(inventory.overviews(organizationId)).flatMap(overviews =>
            Ok(IntegrationInventoryJson.overview(overviews.getOrElse(integrationId, emptyOverview(integrationId))))))
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "inventory" / kind =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          ObjectTypes.get(kind) match {
            case None => NotFound(ApiErrorResponse("NOT_FOUND", "Not found"))
            case Some(objectType) => filterOf(request, objectType) match {
              case None => BadRequest(invalid)
              case Some(filter) => respond(existing(organizationId, integrationId)(
                inventory.list(organizationId, integrationId, objectType, filter))
                .flatMap(page => Ok(IntegrationInventoryJson.page(page, filter.limit, filter.offset))))
            }
          }
        }
      }

    case request @ PUT -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "inventory" / "objects" / objectId / "binding" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          uuid(objectId) match {
            case None => BadRequest(invalid)
            case Some(inventoryObjectId) => request.as[BindingRequest].attempt.flatMap {
              case Left(_) => BadRequest(invalid)
              case Right(body) => respond(runner.run(bindings.bind(context.actor, integrationId, inventoryObjectId,
                body.resourceId)).flatMap(value => Ok(IntegrationInventoryJson.binding(value))))
            }
          }
        }
      }

    case request @ DELETE -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "inventory" / "objects" / objectId / "binding" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (_, integrationId) =>
          uuid(objectId) match {
            case None => BadRequest(invalid)
            case Some(inventoryObjectId) =>
              respond(runner.run(bindings.unbind(context.actor, integrationId, inventoryObjectId)) *> NoContent())
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "integrations" / id / "binding-candidates" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withIntegration(org, context.organizationId, id) { (organizationId, integrationId) =>
          intParam(request, "limit", 50, 1, 200) match {
            case None => BadRequest(invalid)
            case Some(limit) => respond(existing(organizationId, integrationId)(
              inventory.bindingCandidates(organizationId, searchOf(request), limit)).flatMap(values =>
              Ok(Json.obj("items" -> values.map(IntegrationInventoryJson.candidate).asJson))))
          }
        }
      }

    case request @ GET -> Root / "api" / "v1" / "organizations" / org / "resources" / resourceId / "integration-bindings" =>
      authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
        withOrganization(org, context.organizationId) { organizationId =>
          uuid(resourceId) match {
            case None => BadRequest(invalid)
            case Some(value) => respond(runner.run(inventory.resourceContexts(organizationId, value)).flatMap(values =>
              Ok(Json.obj("items" -> values.map(IntegrationInventoryJson.resourceContext).asJson))))
          }
        }
      }
  }

  /** Runs `read` only for an integration of this organization; otherwise answers 404. */
  private def existing[A](organizationId: UUID, integrationId: UUID)(read: Tx[A]): IO[A] =
    runner.run(management.get(organizationId, integrationId).flatMap {
      case Some(_) => read.map(Option(_))
      case None => none[A].pure[Tx]
    }).flatMap(_.liftTo[IO](IntegrationError("INTEGRATION_NOT_FOUND", "Integration was not found")))

  private def withOverview(value: Integration, overview: Option[IntegrationOverview]): Json =
    toResponse(value).asJson.deepMerge(Json.obj("overview" ->
      IntegrationInventoryJson.overview(overview.getOrElse(emptyOverview(value.id)))))

  private def lifecycle(request: org.http4s.Request[IO], rawOrganization: String, id: String,
    enabled: Boolean): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageIntegrations) { context =>
      withIntegration(rawOrganization, context.organizationId, id) { (_, integrationId) =>
        respond(runner.run(management.setEnabled(context.actor, integrationId, enabled))
          .flatMap(value => Ok(toResponse(value))))
      }
    }


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
      case IntegrationSync.AlreadyRunningCode =>
        Conflict(ApiErrorResponse(error.code, "Integration synchronization is already running"))
      case "INTEGRATION_ACTION_ALREADY_RUNNING" =>
        Conflict(ApiErrorResponse(error.code, "An action is already active on this integration"))
      case "INTEGRATION_RECOVERY_REQUIRED" =>
        Conflict(ApiErrorResponse(error.code, "Reconcile unknown outcomes or explicitly abandon recovery before deletion"))
      case "INTEGRATION_OBJECT_NOT_FOUND" => NotFound(ApiErrorResponse(error.code, "Integration object was not found"))
      case "INTEGRATION_BINDING_INVALID_RESOURCE" =>
        UnprocessableEntity(ApiErrorResponse(error.code, "Only an active NODE resource can be bound"))
      case "INTEGRATION_CREDENTIAL_MISSING" | "INTEGRATION_CREDENTIAL_INVALID" =>
        UnprocessableEntity(ApiErrorResponse(error.code, "Integration credential is unusable"))
      case "INTEGRATION_TIMEOUT" => GatewayTimeout(ApiErrorResponse(error.code, "Integration request timed out"))
      // A managed integration keeps its endpoint, its credential and its automatic observation.
      case "INTEGRATION_MANAGEMENT_ACTIVE" =>
        Conflict(ApiErrorResponse(error.code, "Endpoint and credentials cannot change while nodes are managed"))
      case "INTEGRATION_MANAGEMENT_REQUIRES_SYNC" =>
        Conflict(ApiErrorResponse(error.code, "Managing nodes requires automatic synchronization"))
      case "INTEGRATION_CONFIG_ROLLOUT_REQUIRES_SYNC" =>
        Conflict(ApiErrorResponse(error.code,
          "Automatic synchronization cannot be disabled while a guarded rollout is active"))
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

object IntegrationRoutes {
  def toResponse(value: Integration): IntegrationResponse =
    IntegrationResponse(value.id, value.name, value.providerType.code, value.baseUrl.value,
      value.enabled, IntegrationCredentialStatus(apiTokenConfigured = true,
        value.caddyApiKeyConfigured), value.createdAt, value.updatedAt, value.managementMode.code)

  final case class BindingRequest(resourceId: UUID)
  implicit val bindingRequestDecoder: Decoder[BindingRequest] = Decoder.forProduct1("resourceId")(BindingRequest.apply)

  private val ObjectTypes: Map[String, IntegrationObjectType] = Map(
    "nodes" -> IntegrationObjectType.Node,
    "hosts" -> IntegrationObjectType.Host,
    "config-profiles" -> IntegrationObjectType.ConfigProfile)

  private val MaxSearchLength = 128

  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption

  private def emptyOverview(integrationId: UUID): IntegrationOverview = {
    val none = InventoryTypeCounts(0, 0)
    IntegrationOverview(integrationId, None, None, None, IntegrationInventorySummary(none, none, none))
  }

  /** An absent parameter takes its default; a present but invalid one rejects the request. */
  private def intParam(request: Request[IO], name: String, default: Int, min: Int, max: Int): Option[Int] =
    request.params.get(name) match {
      case None => Some(default)
      case Some(raw) => raw.toIntOption.filter(value => value >= min && value <= max)
    }

  private def searchOf(request: Request[IO]): Option[String] =
    request.params.get("search").map(_.trim).filter(_.nonEmpty).map(_.take(MaxSearchLength))

  private def filterOf(request: Request[IO], objectType: IntegrationObjectType): Option[InventoryFilter] = {
    val active = request.params.get("active") match {
      case None => Some(None)
      case Some("true") => Some(Some(true))
      case Some("false") => Some(Some(false))
      case Some(_) => None
    }
    // Remnawave's connection state only exists for nodes.
    val state = request.params.get("state") match {
      case None => Some(None)
      case Some(raw) if objectType == IntegrationObjectType.Node => RemnawaveNodeState.fromCode(raw).map(Some(_))
      case Some(_) => None
    }
    (active, state, intParam(request, "limit", 100, 1, 200), intParam(request, "offset", 0, 0, Int.MaxValue))
      .mapN((a, s, limit, offset) => InventoryFilter(a, searchOf(request), s, limit, offset))
  }
}
