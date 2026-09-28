package ru.bitec.app.ops
package persistence.postgres

import application.audit.AuditRecorder
import application.auth.ActorContext
import application.configuration.{
  ConfigurationError,
  ConfigurationProfileManagement,
  ConfigurationProfileMetadata,
  ConfigurationProfileQueries,
  CreateConfigurationProfileCommand
}
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.audit.{AuditCursor, AuditEvent}
import domain.configuration.{
  ConfigurationValidation,
  ConfigurationValueType,
  ConfigurationVariableDefinition,
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

/** Configuration profiles against a real database: immutable revisions, race-safe numbering,
  * tenant isolation and a journal that commits with the change or not at all.
  */
final class ConfigurationProfileIntegrationSpec extends FunSuite {

  private val OrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000c0")
  private val OtherOrganizationId = UUID.fromString("20000000-0000-0000-0000-0000000000c1")
  private val Template = """{"routing": {"domainStrategy": "{{ routing_strategy }}"}, "inbounds": [{"port": {{ port }}}]}"""

  test("a profile is created with its revision 1, its variables in order, and two journal entries") {
    withFixture { fixture =>
      for {
        created <- fixture.create("vpn-production")
        profile = created._1
        revision = created._2
        detail <- fixture.run(fixture.queries.detail(OrganizationId, profile.id))
        journal <- fixture.journal(OrganizationId)
      } yield IO {
        assertEquals(profile.latestRevisionNumber, 1)
        assertEquals(revision.revisionNumber, 1)
        assertEquals(detail.map(_.latestRevision.revision.template), Some(Template))
        assertEquals(detail.map(_.latestRevision.revision.variables.map(_.name)), Some(List("routing_strategy", "port")))
        assertEquals(detail.map(_.latestRevision.revision.variables.map(_.valueType)),
          Some(List(ConfigurationValueType.StringType, ConfigurationValueType.IntegerType)))
        assertEquals(detail.map(_.latestRevision.createdBy.displayName), Some("Integration fixture"))
        // Identifiers only: the journal has no column that could hold a template or a value.
        assertEquals(journal, List(
          ("CONFIGURATION_PROFILE_CREATED", "CONFIGURATION_PROFILE", Some(profile.id)),
          ("CONFIGURATION_REVISION_CREATED", "CONFIGURATION_PROFILE", Some(profile.id))))
      }
    }
  }

  test("a code is unique within an organization, not across organizations") {
    withFixture { fixture =>
      for {
        _ <- fixture.create("vpn-default")
        again <- fixture.create("vpn-default").attempt
        elsewhere <- fixture.create("vpn-default", fixture.otherActor).attempt
        mine <- fixture.run(fixture.queries.list(OrganizationId, archived = false, 50))
      } yield IO {
        assertEquals(again.swap.toOption.collect { case error: ConfigurationError => error.code },
          Some("CONFIGURATION_PROFILE_CODE_EXISTS"))
        assert(elsewhere.isRight, "another organization could not use the same code")
        assertEquals(mine.map(_.profile.code), List("vpn-default"))
      }
    }
  }

  test("another organization's profile is never read, changed, archived or given a revision") {
    withFixture { fixture =>
      for {
        theirs <- fixture.create("theirs", fixture.otherActor)
        id = theirs._1.id
        read <- fixture.run(fixture.queries.detail(OrganizationId, id))
        revisions <- fixture.run(fixture.queries.revisions(OrganizationId, id, None, 50))
        revision <- fixture.run(fixture.queries.revision(OrganizationId, id, 1))
        renamed <- fixture.run(fixture.management.updateMetadata(fixture.actor, id, ConfigurationProfileMetadata("x", None))).attempt
        archived <- fixture.run(fixture.management.archive(fixture.actor, id)).attempt
        appended <- fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content())).attempt
        untouched <- fixture.run(fixture.queries.detail(OtherOrganizationId, id))
      } yield IO {
        assertEquals(read, None)
        assertEquals(revisions, None)
        assertEquals(revision, None)
        List(renamed, archived, appended).foreach(result =>
          assertEquals(result.swap.toOption.collect { case error: ConfigurationError => error.code },
            Some("CONFIGURATION_PROFILE_NOT_FOUND")))
        assertEquals(untouched.map(_.profile.name), Some("Profile theirs"))
        assertEquals(untouched.map(_.profile.latestRevisionNumber), Some(1))
        assertEquals(untouched.map(_.profile.archived), Some(false))
      }
    }
  }

  test("a new revision leaves the old one exactly as it was, and the database refuses to change one") {
    withFixture { fixture =>
      for {
        created <- fixture.create("vpn-production")
        id = created._1.id
        v2 <- fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content("port={{ port }}")))
        v1 <- fixture.run(fixture.queries.revision(OrganizationId, id, 1))
        latest <- fixture.run(fixture.queries.detail(OrganizationId, id))
        rewrite <- fixture.run(sql"update configuration_revision set template_text = 'x' where profile_id = $id".update.run).attempt
        rewriteVariable <- fixture.run(sql"""
          update configuration_revision_variable set default_value = 'x'
           where organization_id = $OrganizationId""".update.run).attempt
        v1After <- fixture.run(fixture.queries.revision(OrganizationId, id, 1))
      } yield IO {
        assertEquals(v2.revisionNumber, 2)
        assertEquals(v1.map(_.revision.template), Some(Template))
        assertEquals(latest.map(_.profile.latestRevisionNumber), Some(2))
        assertEquals(latest.map(_.latestRevision.revision.template), Some("port={{ port }}"))
        assert(rewrite.isLeft, "a stored revision was rewritten")
        assert(rewriteVariable.isLeft, "a stored variable definition was rewritten")
        assertEquals(v1After, v1)
      }
    }
  }

  test("two revisions saved at the same moment get consecutive numbers, never the same one") {
    withFixture { fixture =>
      for {
        created <- fixture.create("race")
        id = created._1.id
        both <- IO.both(
          fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content("a={{ port }}"))),
          fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content("b={{ port }}"))))
        more <- (1 to 4).toList.parTraverse(i =>
          fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content(s"$i={{ port }}"))))
        history <- fixture.run(fixture.queries.revisions(OrganizationId, id, None, 50))
        profile <- fixture.run(fixture.queries.detail(OrganizationId, id))
      } yield IO {
        assertEquals(Set(both._1.revisionNumber, both._2.revisionNumber), Set(2, 3))
        assertEquals((more.map(_.revisionNumber) ++ List(both._1.revisionNumber, both._2.revisionNumber, 1)).sorted, (1 to 7).toList)
        assertEquals(history.map(_.map(_.revisionNumber)), Some((1 to 7).toList.reverse))
        assertEquals(profile.map(_.profile.latestRevisionNumber), Some(7))
      }
    }
  }

  test("an archived profile keeps its history, takes no new revision and no rename, and is journalled once") {
    withFixture { fixture =>
      for {
        created <- fixture.create("old")
        id = created._1.id
        _ <- fixture.run(fixture.management.archive(fixture.actor, id))
        _ <- fixture.run(fixture.management.archive(fixture.actor, id))
        appended <- fixture.run(fixture.management.appendRevision(fixture.actor, id, fixture.content())).attempt
        renamed <- fixture.run(fixture.management.updateMetadata(fixture.actor, id, ConfigurationProfileMetadata("x", None))).attempt
        active <- fixture.run(fixture.queries.list(OrganizationId, archived = false, 50))
        archived <- fixture.run(fixture.queries.list(OrganizationId, archived = true, 50))
        v1 <- fixture.run(fixture.queries.revision(OrganizationId, id, 1))
        journal <- fixture.journal(OrganizationId)
      } yield IO {
        List(appended, renamed).foreach(result =>
          assertEquals(result.swap.toOption.collect { case error: ConfigurationError => error.code }, Some("CONFIGURATION_PROFILE_ARCHIVED")))
        assertEquals(active, List.empty)
        assertEquals(archived.map(_.profile.id), List(id))
        assert(v1.isDefined, "archiving lost the history")
        assertEquals(journal.count(_._1 == "CONFIGURATION_PROFILE_ARCHIVED"), 1)
      }
    }
  }

  test("renaming changes the metadata and no revision; an unchanged rename journals nothing") {
    withFixture { fixture =>
      for {
        created <- fixture.create("rename")
        id = created._1.id
        renamed <- fixture.run(fixture.management.updateMetadata(fixture.actor, id,
          ConfigurationProfileMetadata("VPN Production Nodes", Some("All VPN nodes"))))
        _ <- fixture.run(fixture.management.updateMetadata(fixture.actor, id,
          ConfigurationProfileMetadata("VPN Production Nodes", Some("All VPN nodes"))))
        detail <- fixture.run(fixture.queries.detail(OrganizationId, id))
        revisions <- fixture.run(sql"select count(*) from configuration_revision where profile_id = $id".query[Long].unique)
        journal <- fixture.journal(OrganizationId)
      } yield IO {
        assertEquals(renamed.code, "rename")
        assertEquals(detail.map(p => (p.profile.name, p.profile.description, p.profile.latestRevisionNumber)),
          Some(("VPN Production Nodes", Some("All VPN nodes"), 1)))
        assertEquals(detail.map(_.latestRevision.revision.template), Some(Template))
        assertEquals(revisions, 1L)
        assertEquals(journal.count(_._1 == "CONFIGURATION_PROFILE_UPDATED"), 1)
      }
    }
  }

  test("when the journal fails, neither the profile nor its revision is kept, nor a new revision") {
    withFixture { fixture =>
      val failing = fixture.managementWith(new FailingAuditEvents)
      for {
        created <- fixture.run(failing.create(fixture.actor, fixture.command("rollback"))).attempt
        profiles <- fixture.run(sql"select count(*) from configuration_profile where organization_id = $OrganizationId".query[Long].unique)
        revisions <- fixture.run(sql"select count(*) from configuration_revision where organization_id = $OrganizationId".query[Long].unique)
        variables <- fixture.run(sql"select count(*) from configuration_revision_variable where organization_id = $OrganizationId".query[Long].unique)
        kept <- fixture.create("kept")
        appended <- fixture.run(failing.appendRevision(fixture.actor, kept._1.id, fixture.content())).attempt
        after <- fixture.run(fixture.queries.detail(OrganizationId, kept._1.id))
        keptRevisions <- fixture.run(sql"select count(*) from configuration_revision where profile_id = ${kept._1.id}".query[Long].unique)
      } yield IO {
        assert(created.isLeft, "the journal failed but the profile was created")
        assertEquals((profiles, revisions, variables), (0L, 0L, 0L))
        assert(appended.isLeft, "the journal failed but the revision was saved")
        assertEquals(after.map(_.profile.latestRevisionNumber), Some(1))
        assertEquals(keptRevisions, 1L)
      }
    }
  }

  test("the list and the history cost one statement each, whatever their size") {
    withFixture { fixture =>
      def statements[A](program: ConnectionIO[A]): IO[Int] =
        Ref.of[IO, Int](0).flatMap { counter =>
          val handler = new LogHandler[IO] { override def run(event: LogEvent): IO[Unit] = counter.update(_ + 1) }
          val config = PostgresTestDatabase.config
          val xa = Transactor.fromDriverManager[IO]("org.postgresql.Driver", config.url, config.user, config.password, Some(handler))
          new DoobieTransactionRunner(xa).run(program) *> counter.get
        }
      for {
        first <- fixture.create("p-00")
        smallList <- statements(fixture.queries.list(OrganizationId, archived = false, 200))
        smallHistory <- statements(fixture.query.listRevisions(OrganizationId, first._1.id, None, 50))
        _ <- (1 to 15).toList.traverse_(i => fixture.create(f"p-$i%02d"))
        _ <- (1 to 10).toList.traverse_(i =>
          fixture.run(fixture.management.appendRevision(fixture.actor, first._1.id, fixture.content(s"$i={{ port }}"))))
        largeList <- statements(fixture.queries.list(OrganizationId, archived = false, 200))
        largeHistory <- statements(fixture.query.listRevisions(OrganizationId, first._1.id, None, 50))
        page <- fixture.run(fixture.query.listRevisions(OrganizationId, first._1.id, Some(5), 3))
        list <- fixture.run(fixture.queries.list(OrganizationId, archived = false, 200))
      } yield IO {
        assertEquals((smallList, largeList), (1, 1))
        assertEquals((smallHistory, largeHistory), (1, 1))
        assertEquals(page.map(_.revisionNumber), List(4, 3, 2))
        assertEquals(page.map(_.createdBy.displayName).distinct, List("Integration fixture"))
        assertEquals(list.find(_.profile.code == "p-00").map(_.profile.latestRevisionNumber), Some(11))
        assertEquals(list.size, 16)
      }
    }
  }

  // -------------------------------------------------------------------------------------------

  private def withFixture(body: ProfileFixture => IO[IO[Unit]]): Unit = {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests"
    )
    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val fixture = new ProfileFixture(new DoobieTransactionRunner(xa))
      fixture.reset *> body(fixture).flatten.guarantee(fixture.reset)
    }.unsafeRunSync()
  }

  private final class ProfileFixture(runner: DoobieTransactionRunner) {
    val actor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, OrganizationId)
    val otherActor: ActorContext = ActorContext(AuthorizationFixtures.ActorUserId, OtherOrganizationId)
    val repository = new PostgresConfigurationProfileRepository
    val query = new PostgresConfigurationProfileQuery
    val queries = new ConfigurationProfileQueries[ConnectionIO](query)
    val management: ConfigurationProfileManagement[ConnectionIO] = managementWith(new PostgresAuditEventRepository)

    def managementWith(events: application.port.AuditEventRepository[ConnectionIO]): ConfigurationProfileManagement[ConnectionIO] =
      new ConfigurationProfileManagement[ConnectionIO](repository, new ConnectionIOIdGenerator, new ConnectionIOTimeProvider,
        new AuditRecorder[ConnectionIO](events, new ConnectionIOIdGenerator, new ConnectionIOTimeProvider))

    def run[A](program: ConnectionIO[A]): IO[A] = runner.run(program)

    def content(template: String = Template): ValidatedConfiguration =
      ConfigurationValidation.validate(template, List(
        ConfigurationVariableDefinition("routing_strategy", ConfigurationValueType.StringType, required = true, Some("IPIfNonMatch"), None),
        ConfigurationVariableDefinition("port", ConfigurationValueType.IntegerType, required = true, Some("443"), Some("Listen port"))
      )).fold(errors => throw new IllegalStateException(errors.toString), identity)

    def command(code: String): CreateConfigurationProfileCommand =
      CreateConfigurationProfileCommand(code, ConfigurationProfileMetadata(s"Profile $code", None), content())

    def create(code: String, by: ActorContext = actor) = run(managementWith(new PostgresAuditEventRepository).create(by, command(code)))

    def journal(organizationId: UUID): IO[List[(String, String, Option[UUID])]] =
      run(sql"""
        select action, target_type, target_id from audit_event
         where organization_id = $organizationId order by occurred_at, created_at, action
      """.query[(String, String, Option[UUID])].to[List])

    def reset: IO[Unit] = run(for {
      _ <- sql"""
        insert into organization (id, code, name) values
          ($OrganizationId, 'configuration-profiles', 'Configuration profiles'),
          ($OtherOrganizationId, 'configuration-profiles-other', 'Other organization')
        on conflict do nothing
      """.update.run
      _ <- sql"delete from audit_event where organization_id in ($OrganizationId, $OtherOrganizationId)".update.run
      _ <- sql"delete from configuration_revision_variable where organization_id in ($OrganizationId, $OtherOrganizationId)".update.run
      _ <- sql"delete from configuration_revision where organization_id in ($OrganizationId, $OtherOrganizationId)".update.run
      _ <- sql"delete from configuration_profile where organization_id in ($OrganizationId, $OtherOrganizationId)".update.run
    } yield ())
  }

  /** A journal whose insert fails, so the mutation around it has to roll back. */
  private final class FailingAuditEvents extends application.port.AuditEventRepository[ConnectionIO] {
    override def save(event: AuditEvent): ConnectionIO[Unit] =
      new IllegalStateException("audit unavailable").raiseError[ConnectionIO, Unit]
    override def saveAll(events: List[AuditEvent]): ConnectionIO[Unit] = save(events.head)
    override def listByOrganization(organizationId: UUID, before: Option[AuditCursor], limit: Int): ConnectionIO[List[AuditEvent]] =
      List.empty[AuditEvent].pure[ConnectionIO]
  }
}
