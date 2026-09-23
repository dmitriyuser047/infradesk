package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.enviroment.{Environment, EnvironmentKind}
import domain.project.Project
import infrastructure.database.{Database, DatabaseConfig, DoobieTransactionRunner}
import munit.FunSuite
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class WorkspaceRepositoryIntegrationSpec extends FunSuite {
  test("environment context projection enforces tenant and active boundaries") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val orgA = UUID.randomUUID()
    val orgB = UUID.randomUUID()
    val projectId = UUID.randomUUID()
    val environmentId = UUID.randomUUID()
    val queries = new PostgresNavigationQueryRepository

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($orgA, ${orgA.toString}, 'Context A')".update.run
        _ <- sql"insert into organization (id, code, name) values ($orgB, ${orgB.toString}, 'Context B')".update.run
        _ <- sql"insert into project (id, organization_id, code, name) values ($projectId, $orgA, 'context', 'Context')".update.run
        _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environmentId, $orgA, $projectId, 'prod', 'Production', 'PROD')".update.run
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from environment where id = $environmentId".update.run
        _ <- sql"delete from project where id = $projectId".update.run
        _ <- sql"delete from organization where id in ($orgA, $orgB)".update.run
      } yield ()

      (runner.run(setup) *> (for {
        own <- runner.run(queries.findEnvironmentContext(orgA, environmentId))
        foreign <- runner.run(queries.findEnvironmentContext(orgB, environmentId))
        _ <- runner.run(sql"update project set is_active = false where id = $projectId".update.run)
        inactiveProject <- runner.run(queries.findEnvironmentContext(orgA, environmentId))
        _ <- runner.run(sql"update project set is_active = true where id = $projectId".update.run)
        _ <- runner.run(sql"update environment set is_active = false where id = $environmentId".update.run)
        inactiveEnvironment <- runner.run(queries.findEnvironmentContext(orgA, environmentId))
        _ <- runner.run(sql"update environment set is_active = true where id = $environmentId".update.run)
        _ <- runner.run(sql"update organization set is_active = false where id = $orgA".update.run)
        inactiveOrganization <- runner.run(queries.findEnvironmentContext(orgA, environmentId))
        _ <- IO {
          assertEquals(own.map(_.project.id), Some(projectId))
          assertEquals(own.map(_.environment.id), Some(environmentId))
          assertEquals(foreign, None)
          assertEquals(inactiveProject, None)
          assertEquals(inactiveEnvironment, None)
          assertEquals(inactiveOrganization, None)
        }
      } yield ())).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }

  test("PostgreSQL enforces case-insensitive scoped project and environment creation") {
    assume(sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true to run PostgreSQL integration tests")

    val orgA = UUID.randomUUID()
    val orgB = UUID.randomUUID()
    val at = Instant.parse("2026-09-23T10:00:00Z")
    val projects = new PostgresProjectRepository
    val environments = new PostgresEnvironmentRepository
    def project(org: UUID, code: String): Project =
      Project(UUID.randomUUID(), org, code, code, None, true, at, at)
    def environment(org: UUID, parent: UUID, code: String): Environment =
      Environment(UUID.randomUUID(), org, parent, code, code, EnvironmentKind.Prod, true, at, at)

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val setup: ConnectionIO[Unit] = for {
        _ <- sql"insert into organization (id, code, name) values ($orgA, ${orgA.toString}, 'Workspace A')".update.run
        _ <- sql"insert into organization (id, code, name) values ($orgB, ${orgB.toString}, 'Workspace B')".update.run
      } yield ()
      val cleanup: ConnectionIO[Unit] = for {
        _ <- sql"delete from environment where organization_id in ($orgA, $orgB)".update.run
        _ <- sql"delete from project where organization_id in ($orgA, $orgB)".update.run
        _ <- sql"delete from organization where id in ($orgA, $orgB)".update.run
      } yield ()
      runner.run(setup) *> (for {
        first <- IO(project(orgA, "app"))
        second <- IO(project(orgA, "other"))
        foreign <- IO(project(orgB, "APP"))
        firstCreated <- runner.run(projects.tryCreate(first))
        duplicate <- runner.run(projects.tryCreate(project(orgA, "APP")))
        secondCreated <- runner.run(projects.tryCreate(second))
        foreignCreated <- runner.run(projects.tryCreate(foreign))
        envCreated <- runner.run(environments.tryCreate(environment(orgA, first.id, "prod")))
        envDuplicate <- runner.run(environments.tryCreate(environment(orgA, first.id, "PROD")))
        otherProjectCreated <- runner.run(environments.tryCreate(environment(orgA, second.id, "PROD")))
        wrongTenant <- runner.run(environments.tryCreate(environment(orgA, foreign.id, "wrong"))).attempt
        _ <- IO {
          assert(firstCreated && secondCreated && foreignCreated)
          assert(!duplicate)
          assert(envCreated && otherProjectCreated)
          assert(!envDuplicate)
          assert(wrongTenant.isLeft)
        }
      } yield ()).guarantee(runner.run(cleanup))
    }.unsafeRunSync()
  }
}
