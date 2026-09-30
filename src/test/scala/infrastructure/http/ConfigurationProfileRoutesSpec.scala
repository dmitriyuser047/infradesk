package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{ConfigurationProfileManagement, ConfigurationProfileQueries}
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.configuration.{ConfigurationProfile, ConfigurationProfileKind, ConfigurationRevision}
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

final class ConfigurationProfileRoutesSpec extends FunSuite {

  private val Org = UUID.fromString("30000000-0000-0000-0000-0000000000a1")
  private val Other = UUID.fromString("30000000-0000-0000-0000-0000000000a2")
  private val base = s"/api/v1/organizations/$Org/configuration-profiles"

  private val createBody = Json.obj(
    "code" -> Json.fromString("vpn-production"),
    "name" -> Json.fromString("VPN Production Nodes"),
    "description" -> Json.Null,
    "template" -> Json.fromString("""{"port": {{ port }}, "protocol": "{{ protocol }}"}"""),
    "variables" -> Json.arr(
      Json.obj("name" -> Json.fromString("port"), "type" -> Json.fromString("INTEGER"), "required" -> Json.True,
        "defaultValue" -> Json.fromString("443")),
      Json.obj("name" -> Json.fromString("protocol"), "type" -> Json.fromString("STRING"), "required" -> Json.True,
        "defaultValue" -> Json.fromString("vless"), "description" -> Json.fromString("Inbound protocol"))))

  test("an owner creates a profile with revision 1 and reads it back; the list carries no content") {
    val f = new RouteFixture
    val (created, body) = f.call(Method.POST, base, Some(createBody))
    assertEquals(created, Status.Created)
    val id = body.hcursor.downField("profile").get[String]("id").toOption.getOrElse(fail("no id"))
    assertEquals(body.hcursor.downField("latestRevision").get[Int]("revisionNumber"), Right(1))
    assertEquals(body.hcursor.downField("latestRevision").downField("createdBy").get[String]("displayName"), Right("Owner"))

    val (status, detail) = f.call(Method.GET, s"$base/$id")
    assertEquals(status, Status.Ok)
    assertEquals(detail.hcursor.downField("latestRevision").get[String]("template"),
      Right("""{"port": {{ port }}, "protocol": "{{ protocol }}"}"""))
    assertEquals(detail.hcursor.downField("latestRevision").downField("variables").downArray.get[String]("type"), Right("INTEGER"))

    val (_, list) = f.call(Method.GET, base)
    val row = list.asArray.flatMap(_.headOption).getOrElse(fail("empty list")).hcursor
    assertEquals(row.get[Int]("latestRevisionNumber"), Right(1))
    assert(!list.noSpaces.contains("protocol"), "the list leaked template content")
  }

  test("a member is refused every configuration route, reads included") {
    val f = new RouteFixture
    List(
      (Method.GET, base, None), (Method.POST, base, Some(createBody)), (Method.POST, s"$base/validate", Some(createBody)),
      (Method.GET, s"$base/${UUID.randomUUID()}", None), (Method.DELETE, s"$base/${UUID.randomUUID()}", None)
    ).foreach { case (method, path, body) =>
      assertEquals(f.call(method, path, body, OrganizationRole.Member)._1, Status.Forbidden, s"$method $path")
    }
    assertEquals(f.store.profiles.size, 0)
  }

  test("generic configuration routes never expose or mutate a Remnawave config profile") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody))._2.hcursor.downField("profile")
      .get[String]("id").toOption.get
    val profileId = UUID.fromString(id)
    f.store.profiles += profileId -> f.store.profiles(profileId)
      .copy(kind = ConfigurationProfileKind.RemnawaveConfig)
    val initialAudit = f.audit.recorded.size
    assertEquals(f.call(Method.GET, base)._2.asArray.map(_.size), Some(0))
    assertEquals(f.call(Method.GET, s"$base?kind=FILE_TEMPLATE")._2.asArray.map(_.size), Some(0))
    assertEquals(f.call(Method.GET, s"$base?kind=REMNAWAVE_CONFIG")._1, Status.BadRequest)
    val update = Json.obj("name" -> Json.fromString("Changed"), "description" -> Json.Null)
    val revision = Json.obj("template" -> Json.fromString("updated"), "variables" -> Json.arr())
    List(
      (Method.GET, s"$base/$id", None),
      (Method.GET, s"$base/$id/revisions", None),
      (Method.GET, s"$base/$id/revisions/1", None),
      (Method.PATCH, s"$base/$id", Some(update)),
      (Method.DELETE, s"$base/$id", None),
      (Method.POST, s"$base/$id/revisions", Some(revision))
    ).foreach { case (method, path, body) =>
      assertEquals(f.call(method, path, body)._1, Status.NotFound, s"$method $path")
    }
    assertEquals(f.store.profiles(profileId).name, "VPN Production Nodes")
    assertEquals(f.store.profiles(profileId).archived, false)
    assertEquals(f.store.revisions.size, 1)
    assertEquals(f.audit.recorded.size, initialAudit)
  }

  test("invalid content is refused with its diagnostics, and nothing is stored") {
    val f = new RouteFixture
    val invalid = createBody.deepMerge(Json.obj("template" -> Json.fromString("port={{ vpn_port }}")))
    val (status, body) = f.call(Method.POST, base, Some(invalid))
    assertEquals(status, Status.BadRequest)
    assertEquals(body.hcursor.get[String]("code"), Right("INVALID_CONFIGURATION"))
    assertEquals(body.hcursor.downField("diagnostics").downArray.get[String]("code"), Right("CONFIGURATION_VARIABLE_UNDEFINED"))
    assertEquals(body.hcursor.downField("diagnostics").downArray.get[String]("variableName"), Right("vpn_port"))
    val badType = createBody.deepMerge(Json.obj("variables" -> Json.arr(Json.obj(
      "name" -> Json.fromString("port"), "type" -> Json.fromString("SECRET")))))
    assertEquals(f.call(Method.POST, base, Some(badType))._1, Status.BadRequest)
    assertEquals(f.call(Method.POST, base, Some(createBody.deepMerge(Json.obj("code" -> Json.fromString("Bad Code")))))._1,
      Status.BadRequest)
    assertEquals(f.store.profiles.size, 0)
  }

  test("a duplicate code is a conflict") {
    val f = new RouteFixture
    assertEquals(f.call(Method.POST, base, Some(createBody))._1, Status.Created)
    val (status, body) = f.call(Method.POST, base, Some(createBody))
    assertEquals(status, Status.Conflict)
    assertEquals(body.hcursor.get[String]("code"), Right("CONFIGURATION_PROFILE_CODE_EXISTS"))
  }

  test("metadata, revisions, history, archive: the lifecycle, with the server numbering versions") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody))._2.hcursor.downField("profile").get[String]("id").toOption.get
    val (patched, profile) = f.call(Method.PATCH, s"$base/$id",
      Some(Json.obj("name" -> Json.fromString("Renamed"), "description" -> Json.fromString("All nodes"))))
    assertEquals(patched, Status.Ok)
    assertEquals(profile.hcursor.get[String]("code"), Right("vpn-production"))
    assertEquals(profile.hcursor.get[Int]("latestRevisionNumber"), Right(1))

    val revision = Json.obj("template" -> Json.fromString("listen {{ port }};"), "variables" -> Json.arr(
      Json.obj("name" -> Json.fromString("port"), "type" -> Json.fromString("INTEGER"))))
    val (createdRevision, v2) = f.call(Method.POST, s"$base/$id/revisions", Some(revision))
    assertEquals(createdRevision, Status.Created)
    assertEquals(v2.hcursor.get[Int]("revisionNumber"), Right(2))

    val (_, history) = f.call(Method.GET, s"$base/$id/revisions")
    assertEquals(history.asArray.map(_.flatMap(_.hcursor.get[Int]("revisionNumber").toOption)), Some(Vector(2, 1)))
    val (_, v1) = f.call(Method.GET, s"$base/$id/revisions/1")
    assertEquals(v1.hcursor.get[String]("template"), Right("""{"port": {{ port }}, "protocol": "{{ protocol }}"}"""))
    assertEquals(f.call(Method.GET, s"$base/$id/revisions/9")._1, Status.NotFound)
    assertEquals(f.call(Method.GET, s"$base/$id/revisions/zero")._1, Status.BadRequest)

    val (archived, archivedBody) = f.call(Method.DELETE, s"$base/$id")
    assertEquals(archived, Status.Ok)
    assertEquals(archivedBody.hcursor.get[Boolean]("archived"), Right(true))
    val (refused, refusedBody) = f.call(Method.POST, s"$base/$id/revisions", Some(revision))
    assertEquals(refused, Status.Conflict)
    assertEquals(refusedBody.hcursor.get[String]("code"), Right("CONFIGURATION_PROFILE_ARCHIVED"))
    assertEquals(f.call(Method.GET, s"$base?archived=true")._2.asArray.map(_.size), Some(1))
    assertEquals(f.call(Method.GET, base)._2.asArray.map(_.size), Some(0))
    assertEquals(f.audit.recorded.map(_.action.code), List("CONFIGURATION_PROFILE_CREATED", "CONFIGURATION_REVISION_CREATED",
      "CONFIGURATION_PROFILE_UPDATED", "CONFIGURATION_REVISION_CREATED", "CONFIGURATION_PROFILE_ARCHIVED"))
  }

  test("another organization's profile, a malformed id and an unknown one") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody))._2.hcursor.downField("profile").get[String]("id").toOption.get
    val foreign = s"/api/v1/organizations/$Other/configuration-profiles/$id"
    val (status, body) = f.call(Method.GET, foreign)
    assertEquals(status, Status.NotFound)
    assertEquals(body.hcursor.get[String]("code"), Right("CONFIGURATION_PROFILE_NOT_FOUND"))
    assertEquals(f.call(Method.DELETE, foreign)._1, Status.NotFound)
    assertEquals(f.call(Method.GET, s"$foreign/revisions")._1, Status.NotFound)
    assertEquals(f.call(Method.GET, s"$base/not-a-uuid")._1, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$base/${UUID.randomUUID()}")._1, Status.NotFound)
    assertEquals(f.call(Method.GET, s"$base?limit=0")._1, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$base?limit=201")._1, Status.BadRequest)
  }

  test("validation reports errors and warnings, renders a preview when it can, and stores nothing") {
    val f = new RouteFixture
    val (status, body) = f.call(Method.POST, s"$base/validate", Some(Json.obj(
      "template" -> Json.fromString("port={{ port }}"),
      "variables" -> Json.arr(Json.obj("name" -> Json.fromString("port"), "type" -> Json.fromString("INTEGER")),
        Json.obj("name" -> Json.fromString("spare"), "type" -> Json.fromString("STRING"), "defaultValue" -> Json.fromString("x"))),
      "previewValues" -> Json.arr(Json.obj("name" -> Json.fromString("port"), "value" -> Json.fromString("8443"))))))
    assertEquals(status, Status.Ok)
    assertEquals(body.hcursor.get[Boolean]("valid"), Right(true))
    assertEquals(body.hcursor.get[List[String]]("referencedVariables"), Right(List("port")))
    assertEquals(body.hcursor.get[String]("renderedPreview"), Right("port=8443"))
    assertEquals(body.hcursor.downField("diagnostics").downArray.get[String]("code"), Right("CONFIGURATION_VARIABLE_UNUSED"))

    val (_, noPreview) = f.call(Method.POST, s"$base/validate", Some(Json.obj(
      "template" -> Json.fromString("port={{ port }}"),
      "variables" -> Json.arr(Json.obj("name" -> Json.fromString("port"), "type" -> Json.fromString("INTEGER"))))))
    assertEquals(noPreview.hcursor.get[Boolean]("valid"), Right(true))
    assert(noPreview.hcursor.downField("renderedPreview").focus.exists(_.isNull))
    assertEquals(noPreview.hcursor.downField("previewError").get[String]("code"), Right("CONFIGURATION_VALUE_MISSING"))

    val (_, invalid) = f.call(Method.POST, s"$base/validate", Some(Json.obj(
      "template" -> Json.fromString("a {{ port"), "variables" -> Json.arr())))
    assertEquals(invalid.hcursor.get[Boolean]("valid"), Right(false))
    assertEquals(invalid.hcursor.downField("diagnostics").downArray.get[Int]("line"), Right(1))
    assertEquals(f.store.profiles.size, 0)
  }

  test("an oversized body is refused unread, and an unexpected failure answers without detail") {
    val f = new RouteFixture
    val huge = Json.obj("template" -> Json.fromString("x" * (3 * 1024 * 1024)), "variables" -> Json.arr())
    assertEquals(f.call(Method.POST, s"$base/validate", Some(huge))._1, Status.PayloadTooLarge)
    val failing = new RouteFixture(failReads = true)
    val (status, body) = failing.call(Method.GET, base)
    assertEquals(status, Status.InternalServerError)
    assertEquals(body.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!body.noSpaces.contains("sql secret"))
  }

  // -------------------------------------------------------------------------------------------

  private final class Store {
    var profiles: Map[UUID, ConfigurationProfile] = Map.empty
    var revisions: List[ConfigurationRevision] = List.empty
  }

  private final class RouteFixture(failReads: Boolean = false) {
    val store = new Store
    val (audit, recorder) = support.TestAuditRecorder.recording
    private val now = Instant.parse("2026-09-28T10:00:00Z")
    private val actor = ConfigurationActor(support.AuthorizationFixtures.ActorUserId, "Owner")

    private val repository = new ConfigurationProfileRepository[IO] {
      def insertProfile(profile: ConfigurationProfile) = IO {
        val taken = store.profiles.values.exists(p => p.organizationId == profile.organizationId && p.code == profile.code)
        if (!taken) store.profiles += profile.id -> profile
        !taken
      }
      def findForUpdate(organizationId: UUID, id: UUID) = IO(store.profiles.get(id).filter(_.organizationId == organizationId))
      def updateProfile(profile: ConfigurationProfile) = IO { store.profiles += profile.id -> profile }
      def insertRevision(revision: ConfigurationRevision) = IO { store.revisions = store.revisions :+ revision }
    }

    private val query = new ConfigurationProfileQuery[IO] {
      private def guard[A](value: => A): IO[A] = if (failReads) IO.raiseError(new IllegalStateException("sql secret")) else IO(value)
      def list(organizationId: UUID, archived: Boolean, limit: Int,
        kind: Option[domain.configuration.ConfigurationProfileKind]) = guard(store.profiles.values.toList
        .filter(p => p.organizationId == organizationId && p.archived == archived && kind.forall(_ == p.kind)).take(limit)
        .map(ConfigurationProfileSummary(_, now)))
      def find(organizationId: UUID, id: UUID) = guard(store.profiles.get(id).filter(_.organizationId == organizationId))
      def listRevisions(organizationId: UUID, profileId: UUID, before: Option[Int], limit: Int) = guard(store.revisions
        .filter(r => r.organizationId == organizationId && r.profileId == profileId && before.forall(r.revisionNumber < _))
        .sortBy(-_.revisionNumber).take(limit).map(r => ConfigurationRevisionSummary(r.revisionNumber, r.variables.size, actor, r.createdAt)))
      def findRevision(organizationId: UUID, profileId: UUID, revisionNumber: Int) = guard(store.revisions
        .find(r => r.organizationId == organizationId && r.profileId == profileId && r.revisionNumber == revisionNumber)
        .map(ConfigurationRevisionView(_, actor)))
    }

    private val ids = new IdGenerator[IO] { def nextId: IO[UUID] = IO(UUID.randomUUID()) }
    private val time = new TimeProvider[IO] { def now: IO[Instant] = IO.pure(RouteFixture.this.now) }
    private val runner = new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }

    private val routes = new ConfigurationProfileRoutes[IO](
      new ConfigurationProfileQueries[IO](query),
      new ConfigurationProfileManagement[IO](repository, ids, time, recorder),
      runner, runner, support.AuthorizationFixtures.authorization,
      Slf4jLogger.getLoggerFromName[IO]("test.configuration"))

    def call(method: Method, path: String, body: Option[Json] = None, role: OrganizationRole = OrganizationRole.Owner): (Status, Json) = {
      val request = body.fold(Request[IO](method, Uri.unsafeFromString(path)))(json =>
        Request[IO](method, Uri.unsafeFromString(path)).withEntity(json.noSpaces))
      val response = support.AuthorizationFixtures.authorized(routes.routes.orNotFound, role).run(request).unsafeRunSync()
      val json = response.as[Json].attempt.unsafeRunSync().getOrElse(Json.Null)
      (response.status, json)
    }
  }
}
