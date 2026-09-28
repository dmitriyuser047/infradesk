package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.configuration.{
  ConfigurationAssignmentDraft,
  ConfigurationAssignmentError,
  ConfigurationAssignmentQueries,
  ConfigurationAssignments,
  ConfigurationProfileManagement,
  ConfigurationProfileMetadata,
  CreateConfigurationProfileCommand
}
import application.port.{ConfigurationAssignmentCursor, ConfigurationAssignmentFilter}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.audit.{AuditCursor, AuditEvent}
import domain.configuration.{
  ConfigurationRenderError,
  ConfigurationValidation,
  ConfigurationValueType,
  ConfigurationVariableDefinition,
  ConfigurationVariableValue,
  ValidatedConfiguration
}
import infrastructure.database.{ConnectionIOIdGenerator, ConnectionIOTimeProvider, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.util.log.{LogEvent, LogHandler}
import org.typelevel.doobie.{ConnectionIO, Transactor}
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import support.AuthorizationFixtures

import java.util.UUID

/** Configuration assignments against a real database: exact revision pinning, one owner per
  * target file even under a race, compare-and-set updates, soft removal, tenant integrity in the
  * schema itself, and a journal that commits with the change or not at all.
  */
final class ConfigurationAssignmentIntegrationSpec extends FunSuite {

  private val NginxPath = "/etc/nginx/nginx.conf"
  private val Template = "server_name {{ domain }};\nlisten {{ port }};\nhttp2 {{ http2 }};"

  test("an assignment pins the exact revision and stores only the values given explicitly") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        detail <- w.run(w.queries.detail(w.org, id))
        stored <- w.run(sql"select name, value from configuration_assignment_value where assignment_id = $id order by name"
          .query[(String, String)].to[List])
        journal <- w.journal
      } yield {
        val item = detail.getOrElse(fail("no detail")).item
        assertEquals(item.assignment.profileRevisionNumber, 1)
        assertEquals(item.assignment.version, 1)
        assertEquals(item.assignment.targetPath, NginxPath)
        assertEquals(item.resource.name, "prod-vps-01")
        assertEquals(item.resource.environment.name, "Production")
        assertEquals(item.profile.latestRevisionNumber, 1)
        assertEquals(detail.map(_.revision.revision.variables.map(_.name)), Some(List("domain", "port", "http2")))
        // The defaults of port and http2 stay in the revision: they are not overrides.
        assertEquals(stored, List("domain" -> "example.com"))
        assertEquals(journal, List(("CONFIGURATION_ASSIGNMENT_CREATED", "CONFIGURATION_ASSIGNMENT", Some(id))))
      }
    }
  }

  test("a new profile revision never moves an assignment; only an explicit update does") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        _ <- w.run(w.profiles.appendRevision(w.actor, profile, w.content("# v2\n" + Template)))
        _ <- w.run(w.profiles.appendRevision(w.actor, profile, w.content("# v3\n" + Template)))
        listed <- w.run(w.queries.list(w.org, ConfigurationAssignmentFilter(None, Some(profile)), None, 50))
        _ <- w.service.update(w.actor, id, 1, draft(3, NginxPath, "domain" -> "example.com"))
        moved <- w.run(w.queries.detail(w.org, id))
      } yield {
        assertEquals(listed.map(row => (row.assignment.profileRevisionNumber, row.profile.latestRevisionNumber)), List((1, 3)))
        assertEquals(moved.map(d => (d.item.assignment.profileRevisionNumber, d.item.assignment.version)), Some((3, 2)))
      }
    }
  }

  test("explicit values and revision defaults render deterministically, and a preview writes nothing") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        first <- w.service.preview(w.org, profile, 1, List(ConfigurationVariableValue("domain", "example.com")))
        second <- w.service.preview(w.org, profile, 1, List(ConfigurationVariableValue("domain", "example.com")))
        overridden <- w.service.preview(w.org, profile, 1,
          List(ConfigurationVariableValue("domain", "example.com"), ConfigurationVariableValue("port", "8443")))
        missing <- w.service.preview(w.org, profile, 1, List.empty)
        rows <- w.count("configuration_assignment")
      } yield {
        assertEquals(first.rendered, Right("server_name example.com;\nlisten 443;\nhttp2 true;"))
        assertEquals(second, first)
        assertEquals(overridden.rendered, Right("server_name example.com;\nlisten 8443;\nhttp2 true;"))
        // Never an empty substitution.
        assertEquals(missing.rendered, Left(ConfigurationRenderError.MissingValue("domain")))
        assertEquals(rows, 0L)
      }
    }
  }

  test("missing, invalid, unknown and duplicate values are refused and nothing is written") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        missing <- w.assign(w.node, profile, 1, NginxPath).attempt
        invalid <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com", "port" -> "https").attempt
        boolean <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com", "http2" -> "yes").attempt
        unknown <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com", "password" -> "x").attempt
        duplicate <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "a", "domain" -> "b").attempt
        tooLong <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "x" * 4097).attempt
        noRevision <- w.assign(w.node, profile, 9, NginxPath, "domain" -> "example.com").attempt
        rows <- w.count("configuration_assignment")
        values <- w.count("configuration_assignment_value")
        journal <- w.journal
      } yield {
        assertEquals(List(missing, invalid, boolean, unknown, duplicate, tooLong, noRevision).map(code),
          List(Some("CONFIGURATION_VALUE_MISSING"), Some("CONFIGURATION_VALUE_INVALID"), Some("CONFIGURATION_VALUE_INVALID"),
            Some("CONFIGURATION_VALUE_UNKNOWN"), Some("CONFIGURATION_VALUE_DUPLICATE"), Some("CONFIGURATION_VALUE_INVALID"),
            Some("CONFIGURATION_REVISION_NOT_FOUND")))
        assertEquals(List(missing, invalid, unknown).map(variable), List(Some("domain"), Some("port"), Some("password")))
        assertEquals((rows, values, journal), (0L, 0L, List.empty))
      }
    }
  }

  test("another tenant's resource and profile are not found, and the schema itself refuses to mix tenants") {
    withWorld { w =>
      for {
        mine <- w.profile("nginx-main")
        theirs <- w.profile("nginx-main", w.foreignActor)
        foreignNode <- w.resource(w.foreignOrg, NodeType, "foreign-node")
        foreignTarget <- w.assign(foreignNode, mine, 1, NginxPath, "domain" -> "example.com").attempt
        foreignProfile <- w.assign(w.node, theirs, 1, NginxPath, "domain" -> "example.com").attempt
        foreignPreview <- w.service.preview(w.org, theirs, 1, List.empty).attempt
        // Past the application: the foreign keys carry the organization, so a crafted row fails too.
        crafted <- w.run(sql"""
          insert into configuration_assignment (id, organization_id, resource_id, profile_id, profile_revision_number,
            target_path, created_at, updated_at)
          values (${UUID.randomUUID()}, ${w.org}, $foreignNode, $mine, 1, '/etc/x', now(), now())
        """.update.run).attempt
        craftedProfile <- w.run(sql"""
          insert into configuration_assignment (id, organization_id, resource_id, profile_id, profile_revision_number,
            target_path, created_at, updated_at)
          values (${UUID.randomUUID()}, ${w.org}, ${w.node}, $theirs, 1, '/etc/x', now(), now())
        """.update.run).attempt
        mineId <- w.assign(w.node, mine, 1, NginxPath, "domain" -> "example.com")
        foreignRead <- w.run(w.queries.detail(w.foreignOrg, mineId))
        foreignUpdate <- w.service.update(w.foreignActor, mineId, 1, draft(1, NginxPath, "domain" -> "x")).attempt
        foreignRemove <- w.service.remove(w.foreignActor, mineId, None).attempt
        after <- w.run(w.queries.detail(w.org, mineId))
      } yield {
        assertEquals(code(foreignTarget), Some("CONFIGURATION_TARGET_NOT_FOUND"))
        assertEquals(code(foreignProfile), Some("CONFIGURATION_PROFILE_NOT_FOUND"))
        assertEquals(code(foreignPreview), Some("CONFIGURATION_PROFILE_NOT_FOUND"))
        assert(crafted.isLeft, "an assignment named another tenant's resource")
        assert(craftedProfile.isLeft, "an assignment named another tenant's profile")
        assertEquals(foreignRead, None)
        assertEquals(code(foreignUpdate), Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
        assertEquals(code(foreignRemove), Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
        assertEquals(after.map(d => (d.item.assignment.version, d.item.assignment.removedAt)), Some((1, None)))
      }
    }
  }

  test("a revision must belong to the assignment's own profile, in the schema too") {
    withWorld { w =>
      for {
        a <- w.profile("profile-a")
        b <- w.profile("profile-b")
        _ <- w.run(w.profiles.appendRevision(w.actor, b, w.content()))
        _ <- w.run(w.profiles.appendRevision(w.actor, b, w.content()))
        viaService <- w.assign(w.node, a, 3, NginxPath, "domain" -> "example.com").attempt
        crafted <- w.run(sql"""
          insert into configuration_assignment (id, organization_id, resource_id, profile_id, profile_revision_number,
            target_path, created_at, updated_at)
          values (${UUID.randomUUID()}, ${w.org}, ${w.node}, $a, 3, '/etc/x', now(), now())
        """.update.run).attempt
      } yield {
        assertEquals(code(viaService), Some("CONFIGURATION_REVISION_NOT_FOUND"))
        assert(crafted.isLeft, "profile A was pinned to a revision number only profile B has")
      }
    }
  }

  test("only an active node can receive a new assignment") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        container <- w.resource(w.org, ContainerType, "nginx-container")
        inactive <- w.resource(w.org, NodeType, "retired-node", active = false)
        onContainer <- w.assign(container, profile, 1, NginxPath, "domain" -> "example.com").attempt
        onInactive <- w.assign(inactive, profile, 1, NginxPath, "domain" -> "example.com").attempt
        unknown <- w.assign(UUID.randomUUID(), profile, 1, NginxPath, "domain" -> "example.com").attempt
        rows <- w.count("configuration_assignment")
      } yield {
        assertEquals(code(onContainer), Some("CONFIGURATION_TARGET_UNSUPPORTED"))
        assertEquals(code(onInactive), Some("CONFIGURATION_TARGET_INACTIVE"))
        assertEquals(code(unknown), Some("CONFIGURATION_TARGET_NOT_FOUND"))
        assertEquals(rows, 0L)
      }
    }
  }

  test("a node that becomes inactive keeps its assignment, shown as inactive") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        _ <- w.run(sql"update resource set is_active = false where id = ${w.node}".update.run)
        listed <- w.run(w.queries.list(w.org, ConfigurationAssignmentFilter(Some(w.node), None), None, 50))
      } yield assertEquals(listed.map(row => (row.assignment.id, row.resource.active)), List((id, false)))
    }
  }

  test("one active owner per file on a resource; the same profile may manage several files") {
    withWorld { w =>
      for {
        nginx <- w.profile("nginx-main")
        app <- w.profile("app-conf")
        _ <- w.assign(w.node, nginx, 1, NginxPath, "domain" -> "example.com")
        again <- w.assign(w.node, app, 1, NginxPath, "domain" -> "example.com").attempt
        _ <- w.assign(w.node, nginx, 1, "/opt/app/a.conf", "domain" -> "a.example.com")
        _ <- w.assign(w.node, nginx, 1, "/opt/app/b.conf", "domain" -> "b.example.com")
        _ <- w.assign(w.node, app, 1, "/opt/app/application.conf", "domain" -> "example.com")
        // The same path on another node is another file.
        _ <- w.assign(w.secondNode, nginx, 1, NginxPath, "domain" -> "example.com")
        active <- w.run(w.queries.list(w.org, ConfigurationAssignmentFilter(Some(w.node), None), None, 50))
      } yield {
        assertEquals(code(again), Some("CONFIGURATION_ASSIGNMENT_PATH_CONFLICT"))
        assertEquals(active.map(_.assignment.targetPath).sorted,
          List("/etc/nginx/nginx.conf", "/opt/app/a.conf", "/opt/app/application.conf", "/opt/app/b.conf"))
      }
    }
  }

  test("concurrent claims of the same file: exactly one wins, the others conflict") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        results <- (1 to 6).toList.parTraverse(i => w.assign(w.node, profile, 1, NginxPath, "domain" -> s"n$i.example.com").attempt)
        active <- w.run(sql"""
          select count(*) from configuration_assignment
           where organization_id = ${w.org} and resource_id = ${w.node} and target_path = $NginxPath and removed_at is null
        """.query[Long].unique)
        journal <- w.journal
      } yield {
        assertEquals(results.count(_.isRight), 1)
        assertEquals(results.flatMap(code).distinct, List("CONFIGURATION_ASSIGNMENT_PATH_CONFLICT"))
        assertEquals(active, 1L)
        assertEquals(journal.size, 1)
      }
    }
  }

  test("concurrent updates from the same version: exactly one wins, the other is told it changed") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        both <- IO.both(
          w.service.update(w.actor, id, 1, draft(1, NginxPath, "domain" -> "first.example.com")).attempt,
          w.service.update(w.actor, id, 1, draft(1, NginxPath, "domain" -> "second.example.com", "port" -> "8443")).attempt)
        after <- w.run(w.queries.detail(w.org, id))
        stale <- w.service.update(w.actor, id, 1, draft(1, NginxPath, "domain" -> "late.example.com")).attempt
        journal <- w.journal
      } yield {
        val outcomes = List(both._1, both._2)
        assertEquals(outcomes.count(_.isRight), 1)
        assertEquals(outcomes.flatMap(code), List("CONFIGURATION_ASSIGNMENT_CHANGED"))
        assertEquals(after.map(_.item.assignment.version), Some(2))
        // The winner's values, whole: never a mix of both.
        val values = after.map(_.values.map(v => v.name -> v.value)).getOrElse(List.empty)
        assert(values == List("domain" -> "first.example.com") ||
          values == List("domain" -> "second.example.com", "port" -> "8443"), values.toString)
        assertEquals(code(stale), Some("CONFIGURATION_ASSIGNMENT_CHANGED"))
        assertEquals(journal.count(_._1 == "CONFIGURATION_ASSIGNMENT_UPDATED"), 1)
      }
    }
  }

  test("the repository compare-and-set itself refuses a stale version and a claimed path") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        other <- w.assign(w.node, profile, 1, "/etc/nginx/other.conf", "domain" -> "example.com")
        now <- w.run(sql"select now()".query[java.time.Instant].unique)
        first <- w.run(w.repository.update(w.org, id, 1, 1, NginxPath, List(ConfigurationVariableValue("domain", "a")), now))
        stale <- w.run(w.repository.update(w.org, id, 1, 1, NginxPath, List(ConfigurationVariableValue("domain", "b")), now))
        claimed <- w.service.update(w.actor, id, 2, draft(1, "/etc/nginx/other.conf", "domain" -> "c")).attempt
        after <- w.run(w.queries.detail(w.org, id))
        otherAfter <- w.run(w.queries.detail(w.org, other))
      } yield {
        assertEquals(first.toString, "Written")
        assertEquals(stale.toString, "Stale")
        assertEquals(code(claimed), Some("CONFIGURATION_ASSIGNMENT_PATH_CONFLICT"))
        assertEquals(after.map(d => (d.item.assignment.version, d.item.assignment.targetPath, d.values.map(_.value))),
          Some((2, NginxPath, List("a"))))
        assertEquals(otherAfter.map(_.item.assignment.version), Some(1))
      }
    }
  }

  test("removal is soft: history stays, the path is freed, and a removed assignment takes no change") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        staleRemove <- w.service.remove(w.actor, id, Some(7)).attempt
        _ <- w.service.remove(w.actor, id, Some(1))
        removed <- w.run(w.queries.detail(w.org, id))
        values <- w.count("configuration_assignment_value")
        listed <- w.run(w.queries.list(w.org, ConfigurationAssignmentFilter(None, None), None, 50))
        update <- w.service.update(w.actor, id, 2, draft(1, NginxPath, "domain" -> "x")).attempt
        again <- w.service.remove(w.actor, id, None).attempt
        replacement <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "new.example.com")
        journal <- w.journal
      } yield {
        assertEquals(code(staleRemove), Some("CONFIGURATION_ASSIGNMENT_CHANGED"))
        assert(removed.exists(_.item.assignment.removedAt.isDefined), "the removed assignment is gone")
        assertEquals(removed.map(_.item.assignment.version), Some(2))
        // The removed assignment keeps its values as history; it is simply no longer listed.
        assertEquals(values, 1L)
        assertEquals(listed, List.empty)
        assertEquals(code(update), Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
        assertEquals(code(again), Some("CONFIGURATION_ASSIGNMENT_NOT_FOUND"))
        assertNotEquals(replacement, id)
        assertEquals(journal.map(_._1), List("CONFIGURATION_ASSIGNMENT_CREATED", "CONFIGURATION_ASSIGNMENT_REMOVED",
          "CONFIGURATION_ASSIGNMENT_CREATED"))
      }
    }
  }

  test("archiving a profile keeps its assignments valid; it only stops new ones") {
    withWorld { w =>
      for {
        profile <- w.profile("nginx-main")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        _ <- w.run(w.profiles.archive(w.actor, profile))
        listed <- w.run(w.queries.list(w.org, ConfigurationAssignmentFilter(None, Some(profile)), None, 50))
        _ <- w.service.update(w.actor, id, 1, draft(1, NginxPath, "domain" -> "still.example.com"))
        fresh <- w.assign(w.secondNode, profile, 1, NginxPath, "domain" -> "example.com").attempt
        after <- w.run(w.queries.detail(w.org, id))
      } yield {
        assertEquals(listed.map(row => (row.assignment.id, row.profile.archived, row.assignment.profileRevisionNumber)),
          List((id, true, 1)))
        assertEquals(code(fresh), Some("CONFIGURATION_PROFILE_ARCHIVED"))
        assertEquals(after.map(d => (d.item.assignment.version, d.values.map(_.value))), Some((2, List("still.example.com"))))
      }
    }
  }

  test("when the journal fails, no assignment is created, changed or removed") {
    withWorld { w =>
      val failing = w.serviceWith(new FailingAuditEvents)
      for {
        profile <- w.profile("nginx-main")
        created <- failing.create(w.actor, w.node, profile, draft(1, NginxPath, "domain" -> "example.com")).attempt
        rows <- w.count("configuration_assignment")
        values <- w.count("configuration_assignment_value")
        id <- w.assign(w.node, profile, 1, NginxPath, "domain" -> "example.com")
        updated <- failing.update(w.actor, id, 1, draft(1, "/etc/nginx/moved.conf", "domain" -> "changed.example.com")).attempt
        removed <- failing.remove(w.actor, id, Some(1)).attempt
        after <- w.run(w.queries.detail(w.org, id))
      } yield {
        assert(created.isLeft && updated.isLeft && removed.isLeft, "the journal failed but a change went through")
        assertEquals((rows, values), (0L, 0L))
        assertEquals(after.map(d => (d.item.assignment.version, d.item.assignment.targetPath, d.item.assignment.removedAt,
          d.values.map(_.value))), Some((1, NginxPath, None, List("example.com"))))
      }
    }
  }

  test("the list is one statement whatever it mentions, the detail a fixed few, and pages continue exactly") {
    withWorld { w =>
      def statements[A](program: ConnectionIO[A]): IO[Int] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] { override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1) }
          val config = PostgresTestDatabase.config
          val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, Some(handler))
          new DoobieTransactionRunner(xa).run(program) *> counter.get
        }
      val all = ConfigurationAssignmentFilter(None, None)
      for {
        profile <- w.profile("nginx-main")
        first <- w.assign(w.node, profile, 1, "/etc/app/00.conf", "domain" -> "example.com")
        smallList <- statements(w.queries.list(w.org, all, None, 200))
        smallDetail <- statements(w.queries.detail(w.org, first))
        others <- (1 to 5).toList.traverse(i => w.profile(s"p-$i"))
        nodes <- (1 to 3).toList.traverse(i => w.resource(w.org, NodeType, s"node-$i"))
        _ <- (for { (p, i) <- others.zipWithIndex; n <- nodes } yield (p, i, n)).traverse { case (p, i, n) =>
          w.assign(n, p, 1, s"/etc/app/$i.conf", "domain" -> "example.com", "port" -> "1", "http2" -> "false")
        }
        largeList <- statements(w.queries.list(w.org, all, None, 200))
        largeDetail <- statements(w.queries.detail(w.org, first))
        whole <- w.run(w.queries.list(w.org, all, None, 200))
        page1 <- w.run(w.queries.list(w.org, all, None, 7))
        page2 <- w.run(w.queries.list(w.org, all,
          page1.lastOption.map(row => ConfigurationAssignmentCursor(row.assignment.createdAt, row.assignment.id)), 7))
      } yield {
        assertEquals((smallList, largeList), (1, 1))
        // The assignment with its context, the revision, its variables, the values.
        assertEquals((smallDetail, largeDetail), (4, 4))
        assertEquals(whole.size, 16)
        assertEquals((page1 ++ page2).map(_.assignment.id), whole.take(14).map(_.assignment.id))
      }
    }
  }

  // -------------------------------------------------------------------------------------------

  private val NodeType = UUID.fromString("10000000-0000-0000-0000-000000000001")
  private val ContainerType = UUID.fromString("10000000-0000-0000-0000-000000000002")

  private def draft(revision: Int, path: String, values: (String, String)*): ConfigurationAssignmentDraft =
    ConfigurationAssignmentDraft(revision, path, values.toList.map { case (name, value) => ConfigurationVariableValue(name, value) })

  private def code(result: Either[Throwable, _]): Option[String] =
    result.swap.toOption.collect { case error: ConfigurationAssignmentError => error.code }

  private def variable(result: Either[Throwable, _]): Option[String] =
    result.swap.toOption.collect { case error: ConfigurationAssignmentError => error.variableName }.flatten

  private def withWorld(body: World => IO[Unit]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val world = new World(new DoobieTransactionRunner(xa))
      (world.setUp *> body(world)).guarantee(world.cleanUp)
    }.unsafeRunSync()
  }

  private final class World(runner: DoobieTransactionRunner) {
    val org: UUID = UUID.randomUUID()
    val foreignOrg: UUID = UUID.randomUUID()
    private val environments = Map(org -> UUID.randomUUID(), foreignOrg -> UUID.randomUUID())
    val node: UUID = UUID.randomUUID()
    val secondNode: UUID = UUID.randomUUID()
    val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, org)
    val foreignActor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, foreignOrg)

    val repository = new PostgresConfigurationAssignmentRepository
    private val profileQuery = new PostgresConfigurationProfileQuery
    val queries = new ConfigurationAssignmentQueries[ConnectionIO](new PostgresConfigurationAssignmentQuery, profileQuery)
    val profiles = new ConfigurationProfileManagement[ConnectionIO](new PostgresConfigurationProfileRepository,
      new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, recorder(new PostgresAuditEventRepository))
    val service: ConfigurationAssignments[IO, ConnectionIO] = serviceWith(new PostgresAuditEventRepository)

    def serviceWith(events: application.port.AuditEventRepository[ConnectionIO]): ConfigurationAssignments[IO, ConnectionIO] =
      new ConfigurationAssignments[IO, ConnectionIO](repository, new PostgresConfigurationTargetQuery, profileQuery,
        new ConnectionIOIdGenerator, new ConnectionIOTimeProvider, recorder(events), runner, runner)

    private def recorder(events: application.port.AuditEventRepository[ConnectionIO]) =
      new AuditRecorder[ConnectionIO](events, new ConnectionIOIdGenerator, new ConnectionIOTimeProvider)

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def content(template: String = Template): ValidatedConfiguration =
      ConfigurationValidation.validate(template, List(
        ConfigurationVariableDefinition("domain", ConfigurationValueType.StringType, required = true, None, None),
        ConfigurationVariableDefinition("port", ConfigurationValueType.IntegerType, required = true, Some("443"), None),
        ConfigurationVariableDefinition("http2", ConfigurationValueType.BooleanType, required = false, Some("true"), None)
      )).fold(errors => throw new IllegalStateException(errors.toString), identity)

    def profile(code: String, by: ActorContext = actor): IO[UUID] =
      run(profiles.create(by, CreateConfigurationProfileCommand(code, ConfigurationProfileMetadata(s"Profile $code", None), content())))
        .map(_._1.id)

    def assign(resource: UUID, profile: UUID, revision: Int, path: String, values: (String, String)*): IO[UUID] =
      service.create(actor, resource, profile, draft(revision, path, values: _*))

    def resource(organization: UUID, typeId: UUID, name: String, active: Boolean = true): IO[UUID] = {
      val id = UUID.randomUUID()
      run(sql"""
        insert into resource (id, organization_id, environment_id, resource_type_id, code, name, is_active)
        values ($id, $organization, ${environments(organization)}, $typeId, $name, $name, $active)
      """.update.run).as(id)
    }

    def count(table: String): IO[Long] = run((table match {
      case "configuration_assignment" => sql"select count(*) from configuration_assignment where organization_id = $org"
      case _ => sql"select count(*) from configuration_assignment_value where organization_id = $org"
    }).query[Long].unique)

    def journal: IO[List[(String, String, Option[UUID])]] =
      run(sql"""
        select action, target_type, target_id from audit_event
         where organization_id = $org and target_type = 'CONFIGURATION_ASSIGNMENT'
         order by occurred_at, created_at, action
      """.query[(String, String, Option[UUID])].to[List])

    def setUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      val project = UUID.randomUUID()
      for {
        _ <- sql"insert into organization (id, code, name) values ($id, ${s"assign-${id.toString.take(8)}"}, 'Assignments')".update.run
        _ <- sql"insert into project (id, organization_id, code, name) values ($project, $id, 'p', 'Project')".update.run
        _ <- sql"""insert into environment (id, organization_id, project_id, code, name, kind)
                   values (${environments(id)}, $id, $project, 'prod', 'Production', 'PROD')""".update.run
      } yield ()
    } *> List(node -> "prod-vps-01", secondNode -> "prod-vps-02").traverse_ { case (id, name) =>
      sql"""
        insert into resource (id, organization_id, environment_id, resource_type_id, code, name, is_active)
        values ($id, $org, ${environments(org)}, $NodeType, $name, $name, true)
      """.update.run
    })

    def cleanUp: IO[Unit] = run(List(org, foreignOrg).traverse_ { id =>
      for {
        _ <- sql"delete from audit_event where organization_id = $id".update.run
        _ <- sql"delete from configuration_assignment_value where organization_id = $id".update.run
        _ <- sql"delete from configuration_assignment where organization_id = $id".update.run
        _ <- sql"delete from configuration_revision_variable where organization_id = $id".update.run
        _ <- sql"delete from configuration_revision where organization_id = $id".update.run
        _ <- sql"delete from configuration_profile where organization_id = $id".update.run
        _ <- sql"delete from resource where organization_id = $id".update.run
        _ <- sql"delete from environment where organization_id = $id".update.run
        _ <- sql"delete from project where organization_id = $id".update.run
        _ <- sql"delete from organization where id = $id".update.run
      } yield ()
    })
  }

  /** A journal whose insert fails, so the change around it has to roll back. */
  private final class FailingAuditEvents extends application.port.AuditEventRepository[ConnectionIO] {
    override def save(event: AuditEvent): ConnectionIO[Unit] =
      new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]
    override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] = save(events.head)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): ConnectionIO[List[AuditEvent]] =
      List.empty[AuditEvent].pure[ConnectionIO]
  }
}
