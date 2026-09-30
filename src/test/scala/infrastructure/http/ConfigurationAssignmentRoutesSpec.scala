package ru.bitec.app.ops
package infrastructure.http

import application.configuration.{ConfigurationAssignmentQueries, ConfigurationAssignments}
import application.port._
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import domain.auth.OrganizationRole
import domain.configuration._
import io.circe.Json
import munit.FunSuite
import org.http4s.circe.CirceEntityDecoder._
import org.http4s.{Method, Request, Status, Uri}
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant
import java.util.UUID

final class ConfigurationAssignmentRoutesSpec extends FunSuite {

  private val Org = UUID.fromString("30000000-0000-0000-0000-0000000000b1")
  private val Other = UUID.fromString("30000000-0000-0000-0000-0000000000b2")
  private val Node = UUID.fromString("50000000-0000-0000-0000-0000000000b1")
  private val Container = UUID.fromString("50000000-0000-0000-0000-0000000000b2")
  private val Profile = UUID.fromString("60000000-0000-0000-0000-0000000000b1")
  private val base = s"/api/v1/organizations/$Org/configuration-assignments"

  private def values(pairs: (String, String)*): Json =
    Json.arr(pairs.map { case (name, value) => Json.obj("name" -> Json.fromString(name), "value" -> Json.fromString(value)) }: _*)

  private def createBody(path: String = "/etc/nginx/nginx.conf", resource: UUID = Node, revision: Int = 1,
                         given: Json = values("domain" -> "example.com")): Json =
    Json.obj("resourceId" -> Json.fromString(resource.toString), "profileId" -> Json.fromString(Profile.toString),
      "profileRevisionNumber" -> Json.fromInt(revision), "targetPath" -> Json.fromString(path), "values" -> given)

  test("an owner assigns a revision to a node and reads it back with its context and explicit values only") {
    val f = new RouteFixture
    val (status, body) = f.call(Method.POST, base, Some(createBody()))
    assertEquals(status, Status.Created)
    val id = body.hcursor.get[String]("id").toOption.getOrElse(fail("no id"))
    assertEquals(body.hcursor.get[Int]("version"), Right(1))
    assertEquals(body.hcursor.get[Int]("profileRevisionNumber"), Right(1))
    assertEquals(body.hcursor.downField("resource").get[String]("name"), Right("prod-vps-01"))
    assertEquals(body.hcursor.downField("resource").downField("environment").get[String]("name"), Right("Production"))
    assertEquals(body.hcursor.downField("profile").get[Int]("latestRevisionNumber"), Right(2))
    assertEquals(body.hcursor.downField("values").as[List[Json]].map(_.size), Right(1))
    assertEquals(body.hcursor.downField("revision").downField("variables").as[List[Json]].map(_.size), Right(2))
    assert(!body.noSpaces.contains("listen"), "the detail carried the template")

    val (_, detail) = f.call(Method.GET, s"$base/$id")
    assertEquals(detail.hcursor.get[String]("targetPath"), Right("/etc/nginx/nginx.conf"))
    val (_, list) = f.call(Method.GET, s"$base?resourceId=$Node")
    val row = list.asArray.flatMap(_.headOption).getOrElse(fail("empty list")).hcursor
    assertEquals(row.get[String]("id"), Right(id))
    assert(row.downField("values").failed, "the list carried values")
    assertEquals(f.audit.recorded.map(_.action.code), List("CONFIGURATION_ASSIGNMENT_CREATED"))
  }

  test("a member is refused every assignment route, reads included") {
    val f = new RouteFixture
    List(
      (Method.GET, base, None), (Method.POST, base, Some(createBody())), (Method.POST, s"$base/preview", Some(createBody())),
      (Method.GET, s"$base/${UUID.randomUUID()}", None), (Method.PATCH, s"$base/${UUID.randomUUID()}", Some(createBody())),
      (Method.DELETE, s"$base/${UUID.randomUUID()}", None)
    ).foreach { case (method, path, body) =>
      assertEquals(f.call(method, path, body, OrganizationRole.Member)._1, Status.Forbidden, s"$method $path")
    }
    assertEquals(f.store.assignments.size, 0)
  }

  test("malformed ids, invalid paths, revisions and values are refused before anything is written") {
    val f = new RouteFixture
    assertEquals(f.call(Method.GET, s"$base/not-a-uuid")._1, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$base?resourceId=nope")._1, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$base?limit=0")._1, Status.BadRequest)
    assertEquals(f.call(Method.GET, s"$base?beforeId=${UUID.randomUUID()}")._1, Status.BadRequest)
    assertEquals(f.call(Method.POST, base, Some(createBody().deepMerge(Json.obj("resourceId" -> Json.fromString("x")))))._1,
      Status.BadRequest)
    List("etc/nginx/nginx.conf", "/etc/../root/file", "/etc//nginx/../x", "/etc/nginx/", "/", "", "/etc/a\nb", "/etc/\u0000x",
      "/" + "a" * 4096).foreach { path =>
      val (status, body) = f.call(Method.POST, base, Some(createBody(path)))
      assertEquals(status, Status.BadRequest, path)
      assertEquals(body.hcursor.get[String]("code"), Right("INVALID_CONFIGURATION_ASSIGNMENT"), path)
    }
    assertEquals(f.call(Method.POST, base, Some(createBody(revision = 0)))._1, Status.BadRequest)
    val (missingRevision, revisionBody) = f.call(Method.POST, base, Some(createBody(revision = 9)))
    assertEquals(missingRevision, Status.NotFound)
    assertEquals(revisionBody.hcursor.get[String]("code"), Right("CONFIGURATION_REVISION_NOT_FOUND"))
    val (invalid, invalidBody) = f.call(Method.POST, base, Some(createBody(given = values("domain" -> "x", "port" -> "https"))))
    assertEquals(invalid, Status.BadRequest)
    assertEquals(invalidBody.hcursor.get[String]("code"), Right("CONFIGURATION_VALUE_INVALID"))
    assertEquals(invalidBody.hcursor.get[String]("variableName"), Right("port"))
    assert(!invalidBody.noSpaces.contains("https"), "the refusal echoed the value")
    val tooMany = values((1 to 101).map(i => s"v$i" -> "x"): _*)
    assertEquals(f.call(Method.POST, base, Some(createBody(given = tooMany)))._2.hcursor.get[String]("code"),
      Right("INVALID_CONFIGURATION_ASSIGNMENT"))
    val (container, containerBody) = f.call(Method.POST, base, Some(createBody(resource = Container)))
    assertEquals(container, Status.BadRequest)
    assertEquals(containerBody.hcursor.get[String]("code"), Right("CONFIGURATION_TARGET_UNSUPPORTED"))
    assertEquals(f.store.assignments.size, 0)
  }

  test("a claimed path and a stale version are conflicts") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody()))._2.hcursor.get[String]("id").toOption.get
    val (duplicate, duplicateBody) = f.call(Method.POST, base, Some(createBody()))
    assertEquals(duplicate, Status.Conflict)
    assertEquals(duplicateBody.hcursor.get[String]("code"), Right("CONFIGURATION_ASSIGNMENT_PATH_CONFLICT"))

    val patch = Json.obj("expectedVersion" -> Json.fromInt(1), "profileRevisionNumber" -> Json.fromInt(2),
      "targetPath" -> Json.fromString("/etc/nginx/nginx.conf"), "values" -> values("domain" -> "example.org"))
    val (updated, updatedBody) = f.call(Method.PATCH, s"$base/$id", Some(patch))
    assertEquals(updated, Status.Ok)
    assertEquals(updatedBody.hcursor.get[Int]("version"), Right(2))
    assertEquals(updatedBody.hcursor.get[Int]("profileRevisionNumber"), Right(2))
    val (stale, staleBody) = f.call(Method.PATCH, s"$base/$id", Some(patch))
    assertEquals(stale, Status.Conflict)
    assertEquals(staleBody.hcursor.get[String]("code"), Right("CONFIGURATION_ASSIGNMENT_CHANGED"))
    assertEquals(f.call(Method.DELETE, s"$base/$id?expectedVersion=1")._1, Status.Conflict)
    assertEquals(f.call(Method.DELETE, s"$base/$id")._2.hcursor.get[String]("code"), Right("INVALID_REQUEST"))
    assertEquals(f.call(Method.DELETE, s"$base/$id?expectedVersion=zero")._1, Status.BadRequest)
    assertEquals(f.call(Method.DELETE, s"$base/$id?expectedVersion=0")._1, Status.BadRequest)
  }

  test("removal is soft and says so; a removed assignment is kept, listed no more, and takes no change") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody()))._2.hcursor.get[String]("id").toOption.get
    val (removed, body) = f.call(Method.DELETE, s"$base/$id?expectedVersion=1")
    assertEquals(removed, Status.Ok)
    assert(body.hcursor.get[String]("removedAt").isRight, "no removal time")
    assertEquals(f.store.assignments.size, 1)
    assertEquals(f.call(Method.GET, base)._2.asArray.map(_.size), Some(0))
    assertEquals(f.call(Method.GET, s"$base/$id")._1, Status.Ok)
    assertEquals(f.call(Method.DELETE, s"$base/$id?expectedVersion=2")._1, Status.NotFound)
    // The path is free again.
    assertEquals(f.call(Method.POST, base, Some(createBody()))._1, Status.Created)
    assertEquals(f.audit.recorded.map(_.action.code),
      List("CONFIGURATION_ASSIGNMENT_CREATED", "CONFIGURATION_ASSIGNMENT_REMOVED", "CONFIGURATION_ASSIGNMENT_CREATED"))
  }

  test("another organization's assignment, resource and profile are not found") {
    val f = new RouteFixture
    val id = f.call(Method.POST, base, Some(createBody()))._2.hcursor.get[String]("id").toOption.get
    val foreign = s"/api/v1/organizations/$Other/configuration-assignments"
    val (status, body) = f.call(Method.GET, s"$foreign/$id")
    assertEquals(status, Status.NotFound)
    assertEquals(body.hcursor.get[String]("code"), Right("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
    assertEquals(f.call(Method.DELETE, s"$foreign/$id?expectedVersion=1")._1, Status.NotFound)
    assertEquals(f.call(Method.GET, foreign)._2.asArray.map(_.size), Some(0))
    val (target, targetBody) = f.call(Method.POST, foreign, Some(createBody()))
    assertEquals(target, Status.NotFound)
    assertEquals(targetBody.hcursor.get[String]("code"), Right("CONFIGURATION_TARGET_NOT_FOUND"))
    assertEquals(f.call(Method.GET, s"$base/${UUID.randomUUID()}")._1, Status.NotFound)
  }

  test("a preview renders the exact revision with defaults, reports value problems, and writes nothing") {
    val f = new RouteFixture
    def preview(revision: Int, given: Json) = f.call(Method.POST, s"$base/preview", Some(Json.obj(
      "profileId" -> Json.fromString(Profile.toString), "profileRevisionNumber" -> Json.fromInt(revision), "values" -> given)))
    val (status, body) = preview(1, values("domain" -> "example.com"))
    assertEquals(status, Status.Ok)
    assertEquals(body.hcursor.get[Boolean]("valid"), Right(true))
    assertEquals(body.hcursor.get[String]("renderedPreview"), Right("server_name example.com; listen 443;"))
    assertEquals(body.hcursor.get[Int]("resolvedRevisionNumber"), Right(1))
    assertEquals(preview(2, values("domain" -> "example.com"))._2.hcursor.get[String]("renderedPreview"),
      Right("# v2\nserver_name example.com; listen 443;"))
    val (_, missing) = preview(1, values())
    assertEquals(missing.hcursor.get[Boolean]("valid"), Right(false))
    assertEquals(missing.hcursor.downField("error").get[String]("code"), Right("CONFIGURATION_VALUE_MISSING"))
    assertEquals(missing.hcursor.downField("error").get[String]("variableName"), Right("domain"))
    assert(missing.hcursor.downField("renderedPreview").focus.exists(_.isNull))
    assertEquals(preview(9, values())._1, Status.NotFound)
    assertEquals(f.store.assignments.size, 0)
    assertEquals(f.audit.recorded, List.empty)
  }

  test("an oversized body is refused unread, and an unexpected failure answers without detail") {
    val f = new RouteFixture
    val huge = createBody(given = values("domain" -> "x" * (3 * 1024 * 1024)))
    assertEquals(f.call(Method.POST, base, Some(huge))._1, Status.PayloadTooLarge)
    val failing = new RouteFixture(failReads = true)
    val (status, body) = failing.call(Method.GET, base)
    assertEquals(status, Status.InternalServerError)
    assertEquals(body.hcursor.get[String]("code"), Right("INTERNAL_ERROR"))
    assert(!body.noSpaces.contains("sql secret"))
  }

  // -------------------------------------------------------------------------------------------

  private final class Store {
    var assignments: Map[UUID, ConfigurationAssignment] = Map.empty
    var values: Map[UUID, List[ConfigurationVariableValue]] = Map.empty
  }

  private final class RouteFixture(failReads: Boolean = false) {
    val store = new Store
    val (audit, recorder) = support.TestAuditRecorder.recording
    private val now = Instant.parse("2026-09-28T10:00:00Z")
    private val actor = ConfigurationActor(support.AuthorizationFixtures.ActorUserId, "Owner")
    private val variables = List(
      ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, None, None),
      ConfigurationVariableDefinition("port", ConfigurationValueType.IntegerType, required = true, Some("443"), None))
    private val profile = ConfigurationProfile(Profile, Org, "nginx-main", "Nginx", None, archived = false, 2, now, now)
    private val revisions = List(
      ConfigurationRevision(UUID.randomUUID(), Org, Profile, 1, "server_name {{ domain }}; listen {{ port }};", variables, actor.id, now),
      ConfigurationRevision(UUID.randomUUID(), Org, Profile, 2, "# v2\nserver_name {{ domain }}; listen {{ port }};", variables, actor.id, now))

    private def guard[A](value: => A): IO[A] = if (failReads) IO.raiseError(new IllegalStateException("sql secret")) else IO(value)

    private val repository = new ConfigurationAssignmentRepository[IO] {
      def lockResource(organizationId: UUID, resourceId: UUID): IO[Boolean] = IO.pure(true)
      def createEligibility(organizationId: UUID, resourceId: UUID, profileId: UUID,
                            revisionNumber: Int): IO[ConfigurationAssignmentEligibility] = IO.pure(ConfigurationAssignmentEligibility.Eligible)
      private def taken(a: ConfigurationAssignment, path: String) = store.assignments.values.exists(other =>
        other.id != a.id && other.active && other.organizationId == a.organizationId && other.resourceId == a.resourceId && other.targetPath == path)
      def insert(assignment: ConfigurationAssignment, values: List[ConfigurationVariableValue]): IO[ConfigurationAssignmentWrite] = IO {
        if (taken(assignment, assignment.targetPath)) ConfigurationAssignmentWrite.PathTaken
        else {
          store.assignments += assignment.id -> assignment
          store.values += assignment.id -> values
          ConfigurationAssignmentWrite.Written
        }
      }
      def update(organizationId: UUID, id: UUID, expectedVersion: Int, revision: Int, path: String,
                 values: List[ConfigurationVariableValue], at: Instant): IO[ConfigurationAssignmentWrite] = IO {
        store.assignments.get(id).filter(a => a.organizationId == organizationId && a.active) match {
          case None => ConfigurationAssignmentWrite.Missing
          case Some(a) if a.version != expectedVersion => ConfigurationAssignmentWrite.Stale
          case Some(a) if taken(a, path) => ConfigurationAssignmentWrite.PathTaken
          case Some(a) =>
            store.assignments += id -> a.copy(profileRevisionNumber = revision, targetPath = path, version = a.version + 1, updatedAt = at)
            store.values += id -> values
            ConfigurationAssignmentWrite.Written
        }
      }
      def remove(organizationId: UUID, id: UUID, expectedVersion: Int, at: Instant): IO[ConfigurationAssignmentWrite] = IO {
        store.assignments.get(id).filter(a => a.organizationId == organizationId && a.active) match {
          case None => ConfigurationAssignmentWrite.Missing
          case Some(a) if expectedVersion != a.version => ConfigurationAssignmentWrite.Stale
          case Some(a) =>
            store.assignments += id -> a.copy(removedAt = Some(at), version = a.version + 1, updatedAt = at)
            ConfigurationAssignmentWrite.Written
        }
      }
      def find(organizationId: UUID, id: UUID) = IO(store.assignments.get(id).filter(_.organizationId == organizationId))
    }

    private val targets = new ConfigurationTargetQuery[IO] {
      def find(organizationId: UUID, resourceId: UUID) = IO(Option.when(organizationId == Org)(resourceId).collect {
        case Node => ConfigurationTarget(Node, "NODE", active = true)
        case Container => ConfigurationTarget(Container, "CONTAINER", active = true)
      })
    }

    private val profiles = new ConfigurationProfileQuery[IO] {
      def list(organizationId: UUID, archived: Boolean, limit: Int,
        kind: Option[domain.configuration.ConfigurationProfileKind]) = IO(List.empty[ConfigurationProfileSummary])
      def find(organizationId: UUID, id: UUID) = IO(Option.when(organizationId == Org && id == Profile)(profile))
      def listRevisions(organizationId: UUID, profileId: UUID, before: Option[Int], limit: Int) = IO(List.empty[ConfigurationRevisionSummary])
      def findRevision(organizationId: UUID, profileId: UUID, revisionNumber: Int) = IO(
        revisions.find(r => organizationId == Org && r.profileId == profileId && r.revisionNumber == revisionNumber)
          .map(ConfigurationRevisionView(_, actor)))
    }

    private val query = new ConfigurationAssignmentQuery[IO] {
      private def item(a: ConfigurationAssignment) = ConfigurationAssignmentListItem(a,
        AssignmentResourceView(a.resourceId, "prod-vps-01", "prod-vps-01", "NODE", active = true,
          ProjectReference(UUID.randomUUID(), "Project"), EnvironmentReference(UUID.randomUUID(), "Production", "PROD")),
        AssignmentProfileView(Profile, "nginx-main", "Nginx", archived = false, 2))
      def list(organizationId: UUID, filter: ConfigurationAssignmentFilter, before: Option[ConfigurationAssignmentCursor], limit: Int) =
        guard(store.assignments.values.toList.filter(a => a.organizationId == organizationId && a.active &&
          filter.resourceId.forall(_ == a.resourceId) && filter.profileId.forall(_ == a.profileId)).take(limit).map(item))
      def find(organizationId: UUID, id: UUID) = guard(store.assignments.get(id).filter(_.organizationId == organizationId).map(item))
      def values(organizationId: UUID, assignmentId: UUID) = guard(store.values.getOrElse(assignmentId, List.empty))
    }

    private val ids = new IdGenerator[IO] { def nextId: IO[UUID] = IO(UUID.randomUUID()) }
    private val time = new TimeProvider[IO] { def now: IO[Instant] = IO.pure(RouteFixture.this.now) }
    private val runner = new TransactionRunner[IO, IO] { def run[A](program: IO[A]): IO[A] = program }

    private val routes = new ConfigurationAssignmentRoutes[IO](
      new ConfigurationAssignments[IO, IO](repository, targets, profiles, ids, time, recorder, runner, runner),
      new ConfigurationAssignmentQueries[IO](query, profiles),
      runner, support.AuthorizationFixtures.authorization,
      Slf4jLogger.getLoggerFromName[IO]("test.configuration.assignments"))

    def call(method: Method, path: String, body: Option[Json] = None, role: OrganizationRole = OrganizationRole.Owner): (Status, Json) = {
      val request = body.fold(Request[IO](method, Uri.unsafeFromString(path)))(json =>
        Request[IO](method, Uri.unsafeFromString(path)).withEntity(json.noSpaces))
      val response = support.AuthorizationFixtures.authorized(routes.routes.orNotFound, role).run(request).unsafeRunSync()
      val json = response.as[Json].attempt.unsafeRunSync().getOrElse(Json.Null)
      (response.status, json)
    }
  }
}
