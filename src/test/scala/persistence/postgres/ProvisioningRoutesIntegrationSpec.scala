package ru.bitec.app.ops
package persistence.postgres

import application.provisioning.{ProvisioningRuns, ProvisioningSettings}
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationRole
import infrastructure.http.ProvisioningRoutes
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.AuthorizationFixtures

import java.util.UUID

final class ProvisioningRoutesIntegrationSpec extends FunSuite {
  import ConfigurationDeploymentWorld.run

  private final class Api(w: ConfigurationDeploymentWorld) {
    private val service = new ProvisioningRuns[IO, ConnectionIO](new PostgresProvisioningRunRepository,
      new PostgresProvisioningTargetQuery, w.ids, w.time, w.audit, w.runner, w.runner, ProvisioningSettings.Default)
    private val routes = new ProvisioningRoutes[ConnectionIO](service, AuthorizationFixtures.authorization,
      Slf4jLogger.getLoggerFromName[IO]("test.provisioning.routes")).routes
    def call(method: Method, path: String, body: Option[Json] = None,
      role: OrganizationRole = OrganizationRole.Owner, org: UUID = w.org): IO[(Status, Json)] = {
      val request = Request[IO](method, Uri.unsafeFromString(s"/api/v1/organizations/$org$path"))
      AuthorizationFixtures.authorized(routes.orNotFound, role).run(body.fold(request)(request.withEntity(_)))
        .flatMap(response => response.as[Json].handleError(_ => Json.Null).map(response.status -> _))
    }
  }

  private def code(body: Json): Option[String] = body.hcursor.get[String]("code").toOption

  test("plan/start/detail/history enforce permissions, strict bodies, tenant scope and request idempotency") {
    run { w =>
      val api = new Api(w)
      for {
        node <- w.node("provisioning-routes")
        planBody = Json.obj("resourceId" -> Json.fromString(node.resourceId.toString))
        memberPlan <- api.call(Method.POST, "/provisioning/plan", Some(planBody), OrganizationRole.Member)
        memberStart <- api.call(Method.POST, "/provisioning/runs", Some(Json.obj(
          "planId" -> Json.fromString(UUID.randomUUID().toString),
          "requestId" -> Json.fromString(UUID.randomUUID().toString))), OrganizationRole.Member)
        extraPlan <- api.call(Method.POST, "/provisioning/plan", Some(planBody.deepMerge(Json.obj("command" -> Json.fromString("id")))))
        planned <- api.call(Method.POST, "/provisioning/plan", Some(planBody))
        planId = UUID.fromString(planned._2.hcursor.downField("run").get[String]("id").toOption.get)
        requestId = UUID.randomUUID()
        approval = Json.obj("planId" -> Json.fromString(planId.toString), "requestId" -> Json.fromString(requestId.toString))
        extraStart <- api.call(Method.POST, "/provisioning/runs", Some(approval.deepMerge(Json.obj("script" -> Json.fromString("id")))))
        duplicate <- List.fill(2)(()).parTraverse(_ => api.call(Method.POST, "/provisioning/runs", Some(approval)))
        detail <- api.call(Method.GET, s"/provisioning/runs/$planId")
        resourceHistory <- api.call(Method.GET, s"/resources/${node.resourceId}/provisioning/runs", None, OrganizationRole.Member)
        foreign <- api.call(Method.GET, s"/provisioning/runs/$planId", org = w.foreignOrg)
        secondPlan <- api.call(Method.POST, "/provisioning/plan", Some(planBody))
        secondId = UUID.fromString(secondPlan._2.hcursor.downField("run").get[String]("id").toOption.get)
        reused <- api.call(Method.POST, "/provisioning/runs", Some(Json.obj(
          "planId" -> Json.fromString(secondId.toString), "requestId" -> Json.fromString(requestId.toString))))
        auditCount <- w.run(sql"select count(*) from audit_event where organization_id=${w.org} and action='PROVISIONING_RUN_REQUESTED'".query[Long].unique)
      } yield {
        assertEquals(memberPlan._1, Status.Forbidden)
        assertEquals(memberStart._1, Status.Forbidden)
        assertEquals(extraPlan._1, Status.BadRequest)
        assertEquals(extraStart._1, Status.BadRequest)
        assert(duplicate.forall(_._1 == Status.Accepted), clues(duplicate))
        assertEquals(detail._1, Status.Ok)
        assertEquals(detail._2.hcursor.downField("run").get[String]("state"), Right("QUEUED"))
        assertEquals(resourceHistory._1, Status.Ok)
        assertEquals(resourceHistory._2.hcursor.downField("items").as[List[Json]].map(_.size), Right(1))
        assertEquals((foreign._1, code(foreign._2)), (Status.NotFound, Some("PROVISIONING_RUN_NOT_FOUND")))
        assertEquals((reused._1, code(reused._2)), (Status.BadRequest, Some("PROVISIONING_REQUEST_REUSED")))
        assertEquals(auditCount, 1L)
      }
    }
  }
}
