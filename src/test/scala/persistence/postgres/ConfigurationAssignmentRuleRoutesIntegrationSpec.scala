package ru.bitec.app.ops
package persistence.postgres

import application.configuration._
import application.port._
import cats.effect.IO
import cats.syntax.all._
import domain.auth.OrganizationRole
import domain.configuration._
import infrastructure.http.ConfigurationAssignmentRuleRoutes
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.circe.CirceEntityEncoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.doobie.ConnectionIO
import org.typelevel.log4cats.slf4j.Slf4jLogger
import support.AuthorizationFixtures

import java.util.UUID

/** The rule and label HTTP API over the real services and database. */
final class ConfigurationAssignmentRuleRoutesIntegrationSpec extends FunSuite {
  private val Path = "/etc/xray/config.json"
  private val logger = Slf4jLogger.getLoggerFromName[IO]("test.configuration.rule.routes")
  private val Defaults = List(ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true,
    Some("vpn.example"), None))

  private final class Api(val w: ConfigurationDeploymentWorld, broken: Boolean = false) {
    val repository = new PostgresConfigurationAssignmentRuleRepository
    private val profiles: ConfigurationProfileQuery[ConnectionIO] =
      if (!broken) w.profileQuery
      else new ConfigurationProfileQuery[ConnectionIO] {
        private def boom[A]: ConnectionIO[A] = new IllegalStateException("template=secret-value").raiseError[ConnectionIO, A]
        def list(organizationId: UUID, archived: Boolean, limit: Int) = boom
        def find(organizationId: UUID, id: UUID) = boom
        def listRevisions(organizationId: UUID, profileId: UUID, before: Option[Int], limit: Int) = boom
        def findRevision(organizationId: UUID, profileId: UUID, revisionNumber: Int) = boom
      }
    val rules = new ConfigurationAssignmentRules[IO, ConnectionIO](repository, repository, w.assignmentRepository,
      w.assignmentQuery, profiles, w.ids, w.time, w.audit, w.runner, w.runner)
    val labels = new ResourceLabels[IO, ConnectionIO](new PostgresResourceLabelRepository, w.time, w.audit, w.runner, w.runner)
    private val routes = new ConfigurationAssignmentRuleRoutes[ConnectionIO](rules, labels, AuthorizationFixtures.authorization, logger).routes
    val worker = new ConfigurationAssignmentRuleWorker[ConnectionIO](repository, w.profileQuery, w.ids, w.runner, UUID.randomUUID(),
      ConfigurationRuleSettings(), logger, IO.realTimeInstant.map(_.plusSeconds(1)), Some(w.org))

    def call(method: Method, path: String, body: Option[Json] = None, role: OrganizationRole = OrganizationRole.Owner,
             org: UUID = w.org): IO[(Status, Json)] = {
      val request = Request[IO](method, Uri.unsafeFromString(s"/api/v1/organizations/$org$path"))
      AuthorizationFixtures.authorized(routes.orNotFound, role).run(body.fold(request)(request.withEntity(_)))
        .flatMap(response => response.as[Json].handleError(_ => Json.Null).map(response.status -> _))
    }

    def profile(): IO[UUID] = w.run(w.profiles.create(w.actor, CreateConfigurationProfileCommand(
      s"xray-${UUID.randomUUID().toString.take(8)}", ConfigurationProfileMetadata("Xray", None),
      w.content("server_name {{ domain }};\n", Defaults)))).map(_._1.id)
  }

  private def api(body: Api => IO[Unit], broken: Boolean = false): Unit =
    ConfigurationDeploymentWorld.run(w => body(new Api(w, broken)))

  private def code(json: Json): Option[String] = json.hcursor.get[String]("code").toOption
  private def selector(pairs: (String, String)*): Json = Json.obj("requiredLabels" -> Json.arr(pairs.map { case (k, v) =>
    Json.obj("key" -> Json.fromString(k), "value" -> Json.fromString(v)) }: _*))

  test("labels: read, replace by version, stale, foreign resource and member permission") {
    api { a =>
      for {
        node <- a.w.resource("fi")
        foreign <- a.w.resource("foreign", a.w.defaultEnvironment(a.w.foreignOrg), a.w.foreignOrg)
        empty <- a.call(Method.GET, s"/resources/$node/labels")
        put = (version: Int, role: OrganizationRole) => a.call(Method.PUT, s"/resources/$node/labels", Some(Json.obj(
          "expectedVersion" -> Json.fromInt(version), "labels" -> Json.arr(Json.obj("key" -> Json.fromString("Role"),
            "value" -> Json.fromString("vpn"))))), role)
        member <- put(0, OrganizationRole.Member)
        memberRead <- a.call(Method.GET, s"/resources/$node/labels", role = OrganizationRole.Member)
        written <- put(0, OrganizationRole.Owner)
        stale <- put(0, OrganizationRole.Owner)
        foreignRead <- a.call(Method.GET, s"/resources/$foreign/labels")
        tooMany <- a.call(Method.PUT, s"/resources/$node/labels", Some(Json.obj("expectedVersion" -> Json.fromInt(1),
          "labels" -> Json.arr((1 to 33).map(i => Json.obj("key" -> Json.fromString(s"k$i"), "value" -> Json.fromString("v"))): _*))))
      } yield {
        assertEquals(empty._2, Json.obj("version" -> Json.fromInt(0), "labels" -> Json.arr()))
        assertEquals(member._1, Status.Forbidden)
        assertEquals(memberRead._1, Status.Ok)
        assertEquals(written._2.hcursor.downField("labels").downArray.get[String]("key"), Right("role"))
        assertEquals((stale._1, code(stale._2)), (Status.Conflict, Some("RESOURCE_LABELS_CHANGED")))
        assertEquals(foreignRead._1, Status.NotFound)
        assertEquals(tooMany._1, Status.BadRequest)
      }
    }
  }

  test("rules: create, list, detail, preview, patch with CAS, enable, disable, reconcile, archive") {
    api { a =>
      for {
        profile <- a.profile()
        node <- a.w.resource("fi")
        _ <- a.labels.replace(a.w.actor, node, 0, List("role" -> "vpn"))
        create = (code: String) => Json.obj("code" -> Json.fromString(code), "name" -> Json.fromString("VPN Production"),
          "profileId" -> Json.fromString(profile.toString), "profileRevisionNumber" -> Json.fromInt(1),
          "targetPath" -> Json.fromString(Path), "selector" -> selector("role" -> "vpn"))
        preview <- a.call(Method.POST, "/configuration-assignment-rules/selector-preview", Some(Json.obj(
          "profileId" -> Json.fromString(profile.toString), "revisionNumber" -> Json.fromInt(1),
          "targetPath" -> Json.fromString(Path), "selector" -> selector("role" -> "vpn"))))
        created <- a.call(Method.POST, "/configuration-assignment-rules", Some(create("vpn-production")))
        duplicate <- a.call(Method.POST, "/configuration-assignment-rules", Some(create("vpn-production")))
        badPath <- a.call(Method.POST, "/configuration-assignment-rules", Some(create("other").deepMerge(
          Json.obj("targetPath" -> Json.fromString("etc/relative")))))
        badSelector <- a.call(Method.POST, "/configuration-assignment-rules", Some(create("other2").deepMerge(
          Json.obj("selector" -> Json.obj("environments" -> Json.arr(Json.fromString(UUID.randomUUID().toString)))))))
        id = created._2.hcursor.get[String]("id").toOption.get
        member <- a.call(Method.GET, "/configuration-assignment-rules", role = OrganizationRole.Member)
        list <- a.call(Method.GET, s"/configuration-assignment-rules?profileId=$profile")
        patched <- a.call(Method.PATCH, s"/configuration-assignment-rules/$id", Some(Json.obj("expectedVersion" -> Json.fromInt(1),
          "name" -> Json.fromString("VPN"), "selector" -> selector("role" -> "vpn"))))
        stalePatch <- a.call(Method.PATCH, s"/configuration-assignment-rules/$id", Some(Json.obj("expectedVersion" -> Json.fromInt(1),
          "name" -> Json.fromString("Other"), "selector" -> selector())))
        enabled <- a.call(Method.POST, s"/configuration-assignment-rules/$id/enable", Some(Json.obj("expectedVersion" -> Json.fromInt(2))))
        reconcile <- a.call(Method.POST, s"/configuration-assignment-rules/$id/reconcile")
        _ <- a.worker.tick
        detail <- a.call(Method.GET, s"/configuration-assignment-rules/$id")
        targets <- a.call(Method.GET, s"/configuration-assignment-rules/$id/targets?limit=1")
        disabled <- a.call(Method.POST, s"/configuration-assignment-rules/$id/disable", Some(Json.obj("expectedVersion" -> Json.fromInt(3))))
        archived <- a.call(Method.DELETE, s"/configuration-assignment-rules/$id?expectedVersion=4")
        malformed <- a.call(Method.GET, "/configuration-assignment-rules/not-a-uuid")
        foreign <- a.call(Method.GET, s"/configuration-assignment-rules/$id", org = a.w.foreignOrg)
      } yield {
        assertEquals(preview._2.hcursor.get[Int]("matchedCount"), Right(1))
        assertEquals(preview._2.hcursor.get[Int]("eligibleCount"), Right(1))
        assertEquals(created._1, Status.Created)
        assertEquals(created._2.hcursor.get[Boolean]("enabled"), Right(false))
        assertEquals((duplicate._1, code(duplicate._2)), (Status.Conflict, Some("CONFIGURATION_RULE_CODE_EXISTS")))
        assertEquals(badPath._1, Status.BadRequest)
        assertEquals((badSelector._1, code(badSelector._2)), (Status.BadRequest, Some("CONFIGURATION_RULE_SELECTOR_INVALID")))
        assertEquals(member._1, Status.Forbidden)
        assertEquals(list._2.hcursor.downField("items").as[List[Json]].map(_.size), Right(1))
        assertEquals(patched._2.hcursor.get[Int]("version"), Right(2))
        assertEquals((stalePatch._1, code(stalePatch._2)), (Status.Conflict, Some("CONFIGURATION_RULE_CHANGED")))
        assertEquals(enabled._2.hcursor.get[Boolean]("enabled"), Right(true))
        assertEquals(reconcile._1, Status.Accepted)
        assertEquals(detail._2.hcursor.downField("counts").get[Int]("managed"), Right(1))
        assertEquals(targets._2.hcursor.downField("items").downArray.get[String]("status"), Right("ASSIGNED"))
        assertEquals(disabled._2.hcursor.get[Boolean]("enabled"), Right(false))
        assertEquals(archived._2.hcursor.get[Boolean]("archived"), Right(true))
        assertEquals(malformed._1, Status.BadRequest)
        assertEquals((foreign._1, code(foreign._2)), (Status.NotFound, Some("CONFIGURATION_RULE_NOT_FOUND")))
      }
    }
  }

  test("exclusions, detach, adopt and promotion over HTTP") {
    api { a =>
      for {
        profile <- a.profile()
        node <- a.w.resource("fi")
        other <- a.w.resource("de")
        _ <- a.labels.replace(a.w.actor, node, 0, List("role" -> "vpn"))
        id <- a.rules.create(a.w.actor, ConfigurationRuleDraft("vpn", "VPN", None, profile, 1, Path,
          ConfigurationRuleSelector(Nil, Nil, List(ResourceLabel("role", "vpn")), Nil), enabled = true))
        _ <- a.worker.tick
        managed <- a.w.run(a.w.assignmentQuery.list(a.w.org, ConfigurationAssignmentFilter(Some(node), None), None, 10)).map(_.head.assignment)
        excluded <- a.call(Method.POST, s"/configuration-assignment-rules/$id/exclude/$other")
        included <- a.call(Method.DELETE, s"/configuration-assignment-rules/$id/exclude/$other")
        staleDetach <- a.call(Method.POST, s"/configuration-assignment-rules/$id/assignments/${managed.id}/detach",
          Some(Json.obj("expectedVersion" -> Json.fromInt(9))))
        detached <- a.call(Method.POST, s"/configuration-assignment-rules/$id/assignments/${managed.id}/detach",
          Some(Json.obj("expectedVersion" -> Json.fromInt(1))))
        // Detach excluded the node: it cannot be adopted back until it is included again.
        adoptExcluded <- a.call(Method.POST, s"/configuration-assignment-rules/$id/assignments/${managed.id}/adopt",
          Some(Json.obj("expectedVersion" -> Json.fromInt(2))))
        _ <- a.call(Method.DELETE, s"/configuration-assignment-rules/$id/exclude/$node")
        adopted <- a.call(Method.POST, s"/configuration-assignment-rules/$id/assignments/${managed.id}/adopt",
          Some(Json.obj("expectedVersion" -> Json.fromInt(2))))
        _ <- a.w.revision(profile, "# v2\nserver_name {{ domain }};\n", Defaults)
        rule <- a.rules.detail(a.w.org, id)
        preview <- a.call(Method.POST, s"/configuration-assignment-rules/$id/promotion-preview", Some(Json.obj(
          "targetRevisionNumber" -> Json.fromInt(2), "expectedRuleVersion" -> Json.fromInt(rule.rule.version))))
        promote = (ruleVersion: Int, assignmentVersion: Int) => a.call(Method.POST, s"/configuration-assignment-rules/$id/promote",
          Some(Json.obj("targetRevisionNumber" -> Json.fromInt(2), "expectedRuleVersion" -> Json.fromInt(ruleVersion),
            "assignments" -> Json.arr(Json.obj("assignmentId" -> Json.fromString(managed.id.toString),
              "expectedVersion" -> Json.fromInt(assignmentVersion))))))
        staleRule <- promote(rule.rule.version - 1, 3)
        staleAssignment <- promote(rule.rule.version, 2)
        promoted <- promote(rule.rule.version, 3)
        missingRevision <- a.call(Method.POST, s"/configuration-assignment-rules/$id/promotion-preview", Some(Json.obj(
          "targetRevisionNumber" -> Json.fromInt(9), "expectedRuleVersion" -> Json.fromInt(rule.rule.version + 1))))
      } yield {
        assertEquals(List(excluded._1, included._1), List(Status.NoContent, Status.NoContent))
        assertEquals((staleDetach._1, code(staleDetach._2)), (Status.Conflict, Some("CONFIGURATION_ASSIGNMENT_CHANGED")))
        assertEquals(detached._1, Status.NoContent)
        assertEquals((adoptExcluded._1, code(adoptExcluded._2)), (Status.Conflict, Some("CONFIGURATION_RULE_RESOURCE_NOT_ELIGIBLE")))
        assertEquals(adopted._1, Status.NoContent)
        assertEquals(preview._2.hcursor.get[Boolean]("compatible"), Right(true))
        assertEquals(preview._2.hcursor.get[Int]("assignmentCount"), Right(1))
        assertEquals((staleRule._1, code(staleRule._2)), (Status.Conflict, Some("CONFIGURATION_RULE_CHANGED")))
        assertEquals((staleAssignment._1, code(staleAssignment._2)), (Status.Conflict, Some("CONFIGURATION_RULE_PROMOTION_CONFLICT")))
        assertEquals(promoted._2.hcursor.downField("assignments").downArray.get[Int]("version"), Right(4))
        assertEquals(missingRevision._1, Status.NotFound)
      }
    }
  }

  test("an unexpected failure is a generic 500 that carries nothing from inside") {
    api(a => for {
      response <- a.call(Method.POST, "/configuration-assignment-rules/selector-preview", Some(Json.obj(
        "profileId" -> Json.fromString(UUID.randomUUID().toString), "revisionNumber" -> Json.fromInt(1),
        "targetPath" -> Json.fromString(Path), "selector" -> selector("role" -> "vpn"))))
    } yield {
      assertEquals(response._1, Status.InternalServerError)
      assertEquals(code(response._2), Some("INTERNAL_ERROR"))
      assert(!response._2.noSpaces.contains("secret"))
    }, broken = true)
  }
}
