package ru.bitec.app.ops
package infrastructure.http

import application.auth.ActorContext
import application.integration._
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationPermission
import domain.integration._
import infrastructure.http.dto.{ApiErrorResponse, HttpJsonCodecs}
import io.circe.Json
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.time.Instant
import java.util.UUID
import org.http4s.{HttpRoutes, Request, Response}
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.dsl.io._
import org.typelevel.log4cats.Logger
import scala.util.Try

object FleetRolloutJson {
  private def str(v: String) = Json.fromString(v)
  private def id(v: UUID) = str(v.toString)
  private def opt[A](v: Option[A])(f: A => Json) = v.fold(Json.Null)(f)
  private def time(v: Instant) = str(v.toString)

  def issue(i: RolloutIssue): Json = Json.obj("code" -> str(i.code), "node" -> opt(i.node)(str))

  private def memberPlan(m: FleetRolloutMemberPlan) = Json.obj("membershipId" -> id(m.membershipId),
    "inventoryNodeId" -> id(m.inventoryNodeId), "nodeName" -> str(m.nodeName), "wave" -> Json.fromInt(m.wave),
    "position" -> Json.fromInt(m.position), "skipReason" -> opt(m.skipReason)(str),
    "actions" -> Json.arr(m.actions.map(a => str(a.code)): _*),
    "baselineCompliance" -> str(m.baseline.compliance), "baselineHealth" -> str(m.baseline.health),
    "rollbackCapabilities" -> Json.arr(m.actions.map { kind =>
      val supported = kind != FleetActionKind.ServerProfileApply || (m.actions.contains(FleetActionKind.ServerProfileAssign) &&
        m.baseline.assignmentProfileId.nonEmpty && m.baseline.assignmentRevisionNumber.nonEmpty)
      Json.obj("kind" -> str(kind.code), "supported" -> Json.fromBoolean(supported),
        "reason" -> (if (supported) Json.Null else str("NO_PREVIOUS_PROFILE")))
    }: _*))

  def snapshot(s: FleetRolloutSnapshot): Json = Json.obj("revisionId" -> id(s.revisionId),
    "revisionNumber" -> Json.fromInt(s.revisionNumber),
    "policy" -> Json.obj("waveSize" -> Json.fromInt(s.policy.waveSize),
      "canaryMembershipIds" -> Json.arr(s.policy.canaryMembershipIds.map(id): _*),
      "pauseAfterCanary" -> Json.fromBoolean(s.policy.pauseAfterCanary),
      "automaticRollback" -> Json.fromBoolean(s.policy.automaticRollback),
      "rollbackScope" -> str(s.policy.rollbackScope.code)),
    "sharedConfig" -> Json.obj("required" -> Json.fromBoolean(s.shared.required),
      "revisionNumber" -> Json.fromInt(s.shared.revisionNumber),
      "baselineRevisionNumber" -> opt(s.shared.baselineRevisionNumber)(Json.fromInt),
      "rollbackSupported" -> Json.fromBoolean(s.shared.baselineRevisionNumber.nonEmpty),
      "externalNodes" -> Json.fromInt(s.shared.externalNodes),
      "externalUnhealthyNodes" -> Json.fromInt(s.shared.externalUnhealthyNodes),
      "consumers" -> Json.arr(s.shared.consumers.map(n => Json.obj("inventoryNodeId" -> id(n.inventoryNodeId),
        "externalNodeId" -> str(n.externalNodeId), "nodeName" -> str(n.nodeName), "disabled" -> Json.fromBoolean(n.disabled),
        "connected" -> Json.fromBoolean(n.connected), "inFleet" -> Json.fromBoolean(s.members.exists(_.inventoryNodeId == n.inventoryNodeId)))): _*)),
    "waveCount" -> Json.fromInt(s.waveCount),
    "estimatedMutations" -> Json.fromInt(RemnawaveFleetRolloutPlanner.estimatedMutations(s)),
    "members" -> Json.arr(s.members.sortBy(m => (m.wave, m.position)).map(memberPlan): _*))

  def rollout(r: RemnawaveFleetRollout, now: Instant): Json = Json.obj("id" -> id(r.id),
    "fleetId" -> id(r.fleetId), "integrationId" -> id(r.integrationId), "revisionId" -> id(r.fleetRevisionId),
    "state" -> str(r.state.code), "phase" -> str(r.phase.code),
    "expired" -> Json.fromBoolean(r.state == FleetRolloutState.Planned && !r.expiresAt.isAfter(now)),
    "currentWave" -> Json.fromInt(r.currentWave), "waveCount" -> Json.fromInt(r.waveCount),
    "pauseAfterCanary" -> Json.fromBoolean(r.pauseAfterCanary),
    "automaticRollback" -> Json.fromBoolean(r.automaticRollback), "rollbackScope" -> str(r.rollbackScope.code),
    "createdAt" -> time(r.createdAt), "expiresAt" -> time(r.expiresAt), "startedAt" -> opt(r.startedAt)(time),
    "finishedAt" -> opt(r.finishedAt)(time), "failureCode" -> opt(r.failureCode)(str),
    "rollbackIncomplete" -> Json.fromBoolean(r.rollbackIncomplete), "pauseReason" -> opt(r.pauseReason)(str),
    "pauseRequested" -> Json.fromBoolean(r.pauseRequestedAt.nonEmpty),
    "rollbackRequested" -> Json.fromBoolean(r.rollbackRequestedAt.nonEmpty), "updatedAt" -> time(r.updatedAt))

  private def member(m: RemnawaveFleetRolloutMember, names: Map[UUID, String]) = Json.obj(
    "id" -> id(m.id), "membershipId" -> id(m.membershipId), "nodeName" -> opt(names.get(m.membershipId))(str),
    "wave" -> Json.fromInt(m.wave), "position" -> Json.fromInt(m.position), "state" -> str(m.state.code),
    "skipReason" -> opt(m.skipReason)(str),
    "plannedActions" -> Json.arr(m.plannedActions.map(a => str(a.code)): _*),
    "failureCode" -> opt(m.failureCode)(str), "rollbackFailureCode" -> opt(m.rollbackFailureCode)(str),
    "finishedAt" -> opt(m.finishedAt)(time))

  private def action(a: RemnawaveFleetRolloutAction) = Json.obj("id" -> id(a.id),
    "memberId" -> opt(a.memberId)(id), "rollback" -> Json.fromBoolean(a.rollback), "kind" -> str(a.kind.code),
    "sequence" -> Json.fromInt(a.sequence), "state" -> str(a.state.code),
    "serverProfileRunId" -> opt(a.serverProfileRunId)(id), "configRolloutId" -> opt(a.configRolloutId)(id),
    "desiredStateActionId" -> opt(a.desiredStateActionId)(id),
    "failureCode" -> opt(a.failureCode)(str), "finishedAt" -> opt(a.finishedAt)(time))

  def detail(d: RolloutDetail, now: Instant): Json = {
    val names = d.rollout.snapshot.members.map(m => m.membershipId -> m.nodeName).toMap
    rollout(d.rollout, now).deepMerge(Json.obj("snapshot" -> snapshot(d.rollout.snapshot),
      "members" -> Json.arr(d.members.sortBy(m => (m.wave, m.position)).map(member(_, names)): _*),
      "actions" -> Json.arr(d.actions.sortBy(a => (a.rollback, a.sequence)).map(action): _*)))
  }

  def preview(p: RolloutPreview, now: Instant): Json = p match {
    case RolloutPreview.Ready(plan, warnings, count) => Json.obj("status" -> str("READY"),
      "planId" -> id(plan.id), "expiresAt" -> time(plan.expiresAt), "estimatedMutations" -> Json.fromInt(count),
      "warnings" -> Json.arr(warnings.map(issue): _*), "plan" -> snapshot(plan.snapshot))
    case RolloutPreview.RefreshRequired(issues) => Json.obj("status" -> str("REFRESH_REQUIRED"),
      "issues" -> Json.arr(issues.map(issue): _*))
    case RolloutPreview.Blocked(issues, warnings) => Json.obj("status" -> str("BLOCKED"),
      "issues" -> Json.arr(issues.map(issue): _*), "warnings" -> Json.arr(warnings.map(issue): _*))
  }
}

/** Rollout control over HTTP. Reading needs organization read; every other action causes remote
  * changes through existing child operations and so needs the integration, configuration and
  * operations permissions together. There is no delete and no cancel.
  */
final class RemnawaveFleetRolloutRoutes[Tx[_]](service: RemnawaveFleetRollouts[Tx],
  authorization: OrganizationAuthorization, logger: Logger[IO]) {
  import HttpJsonCodecs._
  private val invalid = ApiErrorResponse("INVALID_REQUEST", "Invalid Remnawave fleet rollout request")
  private val MaxBytes = 8192
  private val Base = "remnawave-fleets"

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" / "preview" =>
      control(req)(ctx => withId(integration)(i => withId(fleet)(f =>
        body(req, Set("revisionId", "canaryMemberIds", "waveSize", "automaticRollback", "pauseAfterCanary")) { json =>
          val c = json.hcursor
          val decoded = for {
            revision <- c.get[String]("revisionId").toOption.flatMap(uuid)
            raw <- c.get[List[String]]("canaryMemberIds").toOption.filter(_.size <= 500)
            canary <- raw.traverse(uuid)
            wave <- c.get[Int]("waveSize").toOption.filter(_ >= 1)
            auto <- c.get[Boolean]("automaticRollback").toOption
            pause <- c.get[Boolean]("pauseAfterCanary").toOption
          } yield RolloutPreviewInput(revision, canary, wave, auto, pause)
          decoded.fold[IO[Response[IO]]](BadRequest(invalid))(input => respond(req, ctx)(
            service.preview(ctx.actor, i, f, input).flatMap(p => IO.realTimeInstant.flatMap(now =>
              Ok(FleetRolloutJson.preview(p, now))))))
        })))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" =>
      control(req)(ctx => withId(integration)(i => withId(fleet)(f =>
        body(req, Set("planId", "requestId")) { json =>
          val decoded = for {
            plan <- json.hcursor.get[String]("planId").toOption.flatMap(uuid)
            request <- json.hcursor.get[String]("requestId").toOption.flatMap(uuid)
          } yield plan -> request
          decoded.fold[IO[Response[IO]]](BadRequest(invalid)) { case (plan, request) =>
            respond(req, ctx)(service.start(ctx.actor, i, f, plan, request).flatMap(r =>
              IO.realTimeInstant.flatMap(now => Accepted(FleetRolloutJson.rollout(r, now)))))
          }
        })))

    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" =>
      read(req)(ctx => withId(integration)(i => withId(fleet)(f => respond(req, ctx)(for {
        rows <- service.history(ctx.organizationId, i, f)
        now <- IO.realTimeInstant
        response <- Ok(Json.obj("items" -> Json.arr(rows.map(FleetRolloutJson.rollout(_, now)): _*)))
      } yield response))))

    case req @ GET -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" / rollout =>
      read(req)(ctx => withId(integration)(i => withId(fleet)(f => withId(rollout)(r => respond(req, ctx)(for {
        detail <- service.detail(ctx.organizationId, i, f, r)
        now <- IO.realTimeInstant
        response <- Ok(FleetRolloutJson.detail(detail, now))
      } yield response)))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" / rollout / "pause" =>
      control(req)(ctx => withId(integration)(i => withId(fleet)(f => withId(rollout)(r => respond(req, ctx)(
        service.pause(ctx.actor, i, f, r).flatMap(value => IO.realTimeInstant.flatMap(now =>
          Ok(FleetRolloutJson.rollout(value, now)))))))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" / rollout / "resume" =>
      control(req)(ctx => withId(integration)(i => withId(fleet)(f => withId(rollout)(r => respond(req, ctx)(
        service.resume(ctx.actor, i, f, r).flatMap(value => IO.realTimeInstant.flatMap(now =>
          Ok(FleetRolloutJson.rollout(value, now)))))))))

    case req @ POST -> Root / "api" / "v1" / "organizations" / _ / "integrations" / integration / `Base` / fleet / "rollouts" / rollout / "rollback" =>
      control(req)(ctx => withId(integration)(i => withId(fleet)(f => withId(rollout)(r =>
        body(req, Set("scope")) { json =>
          json.hcursor.get[String]("scope").toOption.flatMap(FleetRollbackScope.fromCode).fold[IO[Response[IO]]](
            BadRequest(invalid))(scope => respond(req, ctx)(service.rollback(ctx.actor, i, f, r, scope).flatMap(value =>
            IO.realTimeInstant.flatMap(now => Accepted(FleetRolloutJson.rollout(value, now))))))
        }))))
  }

  private def read(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(req, OrganizationPermission.ReadOrganization)(next)
  private def control(req: Request[IO])(next: OrganizationAccessContext => IO[Response[IO]]): IO[Response[IO]] =
    authorization.require(req, OrganizationPermission.ManageIntegrations)(_ =>
      authorization.require(req, OrganizationPermission.ManageConfigurations)(_ =>
        authorization.require(req, OrganizationPermission.ExecuteOperations)(next)))

  private def uuid(value: String): Option[UUID] =
    Try(UUID.fromString(value)).toOption.filter(_.toString == value)
  private def withId(value: String)(f: UUID => IO[Response[IO]]): IO[Response[IO]] =
    uuid(value).fold[IO[Response[IO]]](BadRequest(invalid))(f)

  private def body(req: Request[IO], keys: Set[String])(f: Json => IO[Response[IO]]): IO[Response[IO]] =
    req.body.take(MaxBytes + 1L).compile.to(Array).flatMap { bytes =>
      val decoded = Try(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString).toOption
      val json = decoded.flatMap(io.circe.parser.parse(_).toOption)
        .filter(value => value.asObject.exists(_.keys.toSet == keys))
      if (bytes.length > MaxBytes || json.isEmpty) BadRequest(invalid) else f(json.get)
    }

  private def respond(req: Request[IO], ctx: OrganizationAccessContext)(
    action: IO[Response[IO]]): IO[Response[IO]] = action.handleErrorWith {
    case e: IntegrationError if e.code.endsWith("_NOT_FOUND") => NotFound(ApiErrorResponse(e.code, e.getMessage))
    case e: IntegrationError => Conflict(ApiErrorResponse(e.code, e.getMessage))
    case e => ReadModelHttp.failedAs(logger, "remnawave.fleet.rollout.request_failed", req, "fleet-rollout",
      "organizationId" -> ctx.organizationId, "actorUserId" -> ctx.user.id)(e)
  }
}
