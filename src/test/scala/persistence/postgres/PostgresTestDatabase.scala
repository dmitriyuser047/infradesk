package ru.bitec.app.ops
package persistence.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all._
import infrastructure.database.{Database, DatabaseConfig, DatabaseMigrator}
import infrastructure.database.DoobieTransactionRunner
import org.typelevel.doobie.ConnectionIO
import org.typelevel.doobie.hikari.HikariTransactor
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.util.UUID

private[postgres] object PostgresTestDatabase {
  def config: DatabaseConfig = DatabaseConfig.fromEnvironment(sys.env).fold(throw _, identity)

  def transactor(config: DatabaseConfig, target: Option[String] = None): Resource[IO, HikariTransactor[IO]] =
    Resource.eval(target.fold(DatabaseMigrator.migrate(config,
      Slf4jLogger.getLoggerFromName[IO]("test.database.migration")).void)(version => IO.blocking {
      org.flywaydb.core.Flyway.configure().dataSource(config.url,config.user,config.password)
        .locations("classpath:db/migration").target(version).load().migrate(); ()
    })) *>
      Database.transactor(config).evalTap(ensureBaseFixtures)

  /** Global queue tests need a database, rather than tenant, boundary. */
  def isolatedTransactor(config: DatabaseConfig, target: Option[String] = None): Resource[IO, HikariTransactor[IO]] = {
    val name = "infradesk_isolated_" + UUID.randomUUID().toString.replace("-", "")
    def execute(statement: String): IO[Unit] = IO.blocking {
      val connection = java.sql.DriverManager.getConnection(config.url, config.user, config.password)
      try {
        val command = connection.createStatement()
        try { command.execute(statement); () } finally command.close()
      } finally connection.close()
    }
    val suffixAt = config.url.indexOf('?')
    val baseUrl = if (suffixAt < 0) config.url else config.url.take(suffixAt)
    val suffix = if (suffixAt < 0) "" else config.url.drop(suffixAt)
    val isolated = config.copy(url = baseUrl.take(baseUrl.lastIndexOf('/') + 1) + name + suffix)
    Resource.make(execute(s"CREATE DATABASE $name"))(_ => execute(s"DROP DATABASE $name WITH (FORCE)"))
      .flatMap(_ => transactor(isolated,target))
  }

  private def ensureBaseFixtures(xa: HikariTransactor[IO]): IO[Unit] = {
    val org = UUID.fromString("20000000-0000-0000-0000-000000000001")
    val project = UUID.fromString("30000000-0000-0000-0000-000000000001")
    val environment = UUID.fromString("40000000-0000-0000-0000-000000000001")
    val connection = UUID.fromString("60000000-0000-0000-0000-000000000003")
    // Audit rows point at a real actor, so the fixture owns one user and its membership.
    val actor = UUID.fromString("10000000-0000-0000-0000-0000000000aa")
    val program: ConnectionIO[Unit] = for {
      _ <- sql"insert into organization (id, code, name) values ($org, 'integration-fixture', 'Integration fixture') on conflict do nothing".update.run
      _ <- sql"insert into project (id, organization_id, code, name) values ($project, $org, 'integration-fixture', 'Integration fixture') on conflict do nothing".update.run
      _ <- sql"insert into environment (id, organization_id, project_id, code, name, kind) values ($environment, $org, $project, 'integration-fixture', 'Integration fixture', 'TEST') on conflict do nothing".update.run
      _ <- sql"insert into connection (id, organization_id, scope_type, connector_type, code, name) values ($connection, $org, 'ORGANIZATION', 'SSH', 'integration-fixture', 'Integration fixture') on conflict do nothing".update.run
      _ <- sql"""
        insert into user_account (id, email, password_hash, display_name, created_at, updated_at)
        values ($actor, 'integration-fixture@example.test', 'x', 'Integration fixture',
          current_timestamp, current_timestamp)
        on conflict do nothing
      """.update.run
      _ <- sql"""
        insert into organization_membership (user_id, organization_id, role, created_at, updated_at)
        values ($actor, $org, 'OWNER', current_timestamp, current_timestamp)
        on conflict do nothing
      """.update.run
    } yield ()
    new DoobieTransactionRunner(xa).run(program)
  }
}
