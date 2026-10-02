package ru.bitec.app.ops
package infrastructure.http

import application.provisioning.{ProvisioningError, ProvisioningPlan, ProvisioningRuns}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.provisioning._
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.{Decoder, DecodingFailure, HCursor, Json}
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import scala.util.Try

final class ProvisioningRoutes[Tx[_]](service: ProvisioningRuns[IO, Tx], authorization: OrganizationAuthorization,
  logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid provisioning request")
  private val MaxBodyBytes = 16 * 1024

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "provisioning" / "plan" =>
      mutate(request) { context => body[PlanRequest](request) { input =>
        respond(request, context, "provisioning.plan")(service.plan(context.organizationId, input.resourceId).flatMap(p => Ok(planJson(p))))
      } }
    case request @ POST -> Root / "api" / "v1" / "organizations" / _ / "provisioning" / "runs" =>
      mutate(request) { context => body[StartRequest](request) { input =>
        respond(request, context, "provisioning.start")(service.start(context.actor, input.planId, input.requestId)
          .flatMap(run => Accepted(runJson(run))))
      } }
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "provisioning" / "runs" / id =>
      read(request) { context => uuid(id).fold[IO[Response[IO]]](BadRequest(invalid))(runId =>
        respond(request, context, "provisioning.detail")(service.detail(context.organizationId, runId)
          .flatMap { case (run, steps) => Ok(Json.obj("run" -> runJson(run), "steps" -> Json.arr(steps.map(stepJson): _*))) })) }
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "resources" / resourceId / "provisioning" / "runs" =>
      read(request) { context => uuid(resourceId).fold[IO[Response[IO]]](BadRequest(invalid))(id =>
        respond(request, context, "provisioning.resource-history")(service.history(context.organizationId, Some(id), 50)
          .flatMap(rows => Ok(Json.obj("items" -> Json.arr(rows.map(runJson): _*)))))) }
    case request @ GET -> Root / "api" / "v1" / "organizations" / _ / "provisioning" / "runs" =>
      read(request) { context => respond(request, context, "provisioning.history")(
        service.history(context.organizationId, None, 50).flatMap(rows => Ok(Json.obj("items" -> Json.arr(rows.map(runJson): _*))))) }
  }

  private def mutate(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ManageConfigurations) { _ =>
      authorization.require(request, OrganizationPermission.ExecuteOperations)(next)
    }
  private def read(request: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(request, OrganizationPermission.ReadOrganization)(next)
  private def uuid(raw: String): Option[UUID] = Try(UUID.fromString(raw)).toOption

  private def body[A: Decoder](request: Request[IO])(next: A => IO[Response[IO]]): IO[Response[IO]] =
    request.body.take(MaxBodyBytes + 1L).compile.toVector.flatMap { bytes =>
      if (bytes.size > MaxBodyBytes) BadRequest(invalid)
      else io.circe.parser.decode[A](new String(bytes.toArray, StandardCharsets.UTF_8)).fold(_ => BadRequest(invalid), next)
    }
  private def respond(request: Request[IO], context: OrganizationAccessContext, operation: String)(action: IO[Response[IO]]): IO[Response[IO]] =
    action.handleErrorWith {
      case error: ProvisioningError =>
        val status = error match {
          case ProvisioningError.NotFound | ProvisioningError.TargetNotFound => NotFound(ApiErrorResponse(error.code, error.getMessage))
          case ProvisioningError.PlanExpired => Conflict(ApiErrorResponse(error.code, error.getMessage))
          case ProvisioningError.Disabled => ServiceUnavailable(ApiErrorResponse(error.code, error.getMessage))
          case ProvisioningError.RequestReused | ProvisioningError.UnsupportedTarget => BadRequest(ApiErrorResponse(error.code, error.getMessage))
          case _ => Conflict(ApiErrorResponse(error.code, error.getMessage))
        }
        status
      case error => ReadModelHttp.failedAs(logger, "provisioning.failed", request, operation,
        "organizationId" -> context.organizationId, "actorUserId" -> context.user.id)(error)
    }

  private def runJson(run: ProvisioningRun): Json = Json.obj(
    "id" -> Json.fromString(run.id.toString), "organizationId" -> Json.fromString(run.organizationId.toString),
    "resourceId" -> Json.fromString(run.resourceId.toString), "requestId" -> run.requestId.fold(Json.Null)(id => Json.fromString(id.toString)),
    "requestedByUserId" -> run.requestedBy.fold(Json.Null)(id => Json.fromString(id.toString)),
    "runKind" -> Json.fromString(run.input.runKind.code),
    "currentStep" -> run.currentStep.fold(Json.Null)(step => Json.fromString(step.code)),
    "state" -> Json.fromString(run.state.code), "createdAt" -> HttpJsonCodecs.instantEncoder(run.createdAt),
    "updatedAt" -> HttpJsonCodecs.instantEncoder(run.updatedAt),
    "startedAt" -> run.startedAt.fold(Json.Null)(HttpJsonCodecs.instantEncoder.apply),
    "finishedAt" -> run.finishedAt.fold(Json.Null)(HttpJsonCodecs.instantEncoder.apply),
    "failureCode" -> run.failureCode.fold(Json.Null)(Json.fromString),
    "safeMessage" -> run.safeMessage.fold(Json.Null)(Json.fromString),
    "inputSnapshot" -> inputJson(run.input))
  private def stepJson(step: ProvisioningStep): Json = Json.obj("id" -> Json.fromString(step.id.toString),
    "position" -> Json.fromInt(step.position), "kind" -> Json.fromString(step.kind.code),
    "displayName" -> Json.fromString(step.displayName), "attempt" -> Json.fromInt(step.attempt),
    "state" -> Json.fromString(step.state.code), "startedAt" -> step.startedAt.fold(Json.Null)(HttpJsonCodecs.instantEncoder.apply),
    "finishedAt" -> step.finishedAt.fold(Json.Null)(HttpJsonCodecs.instantEncoder.apply),
    "facts" -> Json.obj(step.facts.toList.map { case (k,v) => k -> Json.fromString(v) }: _*),
    "failureCode" -> step.failureCode.fold(Json.Null)(Json.fromString),
    "safeMessage" -> step.safeMessage.fold(Json.Null)(Json.fromString),
    "outputSummary" -> step.outputSummary.fold(Json.Null)(Json.fromString),
    "verificationResult" -> step.verificationResult.fold(Json.Null)(Json.fromBoolean))
    .deepMerge(Json.obj("outputTruncated" -> Json.fromBoolean(step.outputTruncated)))
  private def planJson(plan: ProvisioningPlan): Json = Json.obj("run" -> runJson(plan.run),
    "approvalInput" -> inputJson(plan.run.input),
    "connectionName" -> Json.fromString(plan.connectionName),
    "steps" -> Json.arr(plan.steps.map(stepJson): _*), "warnings" -> Json.arr(plan.warnings.map(Json.fromString): _*),
    "blockingProblems" -> Json.arr(plan.blockingProblems.map(Json.fromString): _*))
  private def inputJson(input: ProvisioningInputSnapshot): Json = Json.obj(
    "schemaVersion" -> Json.fromInt(input.schemaVersion), "runKind" -> Json.fromString(input.runKind.code),
    "resourceId" -> Json.fromString(input.resourceId.toString),
    "resourceType" -> Json.fromString(input.resourceType), "resourceKind" -> Json.fromString(input.resourceKind),
    "connectionId" -> Json.fromString(input.connectionId.toString),
    "connectionUpdatedAt" -> HttpJsonCodecs.instantEncoder(input.connectionUpdatedAt),
    "steps" -> Json.arr(input.steps.map(step => Json.fromString(step.code)): _*),
    "profileApply" -> input.profileApply.fold(Json.Null)(p => Json.obj(
      "assignmentId" -> Json.fromString(p.assignmentId.toString), "assignmentVersion" -> Json.fromLong(p.assignmentVersion),
      "profileId" -> Json.fromString(p.profileId.toString), "revisionId" -> Json.fromString(p.revisionId.toString),
      "revisionNumber" -> Json.fromInt(p.revisionNumber), "revisionHash" -> Json.fromString(p.revisionHash),
      "content" -> p.content.json, "observationId" -> Json.fromString(p.observationId.toString),
      "observationHash" -> Json.fromString(p.observationHash), "reviewedDiffHash" -> Json.fromString(p.reviewedDiffHash),
      "blockingProblems" -> Json.arr(p.blockingProblems.map(Json.fromString): _*))))
}

private final case class PlanRequest(resourceId: UUID)
private final case class StartRequest(planId: UUID, requestId: UUID)
private object PlanRequest {
  implicit val decoder: Decoder[PlanRequest] = Decoder.instance { c =>
    for { keys <- c.keys.toRight(DecodingFailure("object required", c.history))
      _ <- Either.cond(keys.toSet == Set("resourceId"), (), DecodingFailure("unexpected fields", c.history))
      id <- c.get[UUID]("resourceId") } yield PlanRequest(id)
  }
}
private object StartRequest {
  implicit val decoder: Decoder[StartRequest] = Decoder.instance { c =>
    for { keys <- c.keys.toRight(DecodingFailure("object required", c.history))
      _ <- Either.cond(keys.toSet == Set("planId", "requestId"), (), DecodingFailure("unexpected fields", c.history))
      plan <- c.get[UUID]("planId"); request <- c.get[UUID]("requestId") } yield StartRequest(plan, request)
  }
}
