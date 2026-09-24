package ru.bitec.app.ops
package persistence.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.all._
import domain.connection.{Connection, ConnectionConfig, ConnectionScope}
import infrastructure.database.DoobieTransactionRunner
import munit.FunSuite
import org.typelevel.doobie.implicits._
import org.typelevel.doobie.postgres.implicits._

import java.time.Instant
import java.util.UUID

final class PostgresConnectionRepositoryIntegrationSpec extends FunSuite {
  test("saveIfUnmodified rejects a stale writer without overwriting the winner") {
    assume(
      sys.env.get("INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS").contains("true"),
      "Set INFRADESK_RUN_POSTGRES_INTEGRATION_TESTS=true"
    )

    val organizationId = UUID.randomUUID()
    val connectionId = UUID.randomUUID()
    val originalUpdatedAt = Instant.parse("2026-09-25T08:00:00Z")
    val winnerUpdatedAt = originalUpdatedAt.plusSeconds(1)
    val staleUpdatedAt = originalUpdatedAt.plusSeconds(2)
    val original = Connection(
      id = connectionId,
      organizationId = organizationId,
      scope = ConnectionScope.Organization,
      connectorType = "SSH",
      code = "fenced-ssh",
      name = "Original",
      config = ConnectionConfig(Map("host" -> "original.example")),
      secretRef = Some("secret-original"),
      isActive = true,
      createdAt = originalUpdatedAt,
      updatedAt = originalUpdatedAt
    )
    val winner = original.copy(
      name = "Winner",
      config = ConnectionConfig(Map("host" -> "winner.example")),
      secretRef = Some("secret-winner"),
      updatedAt = winnerUpdatedAt
    )
    val stale = original.copy(
      name = "Stale",
      config = ConnectionConfig(Map("host" -> "stale.example")),
      secretRef = Some("secret-stale"),
      updatedAt = staleUpdatedAt
    )

    PostgresTestDatabase.transactor(PostgresTestDatabase.config).use { xa =>
      val runner = new DoobieTransactionRunner(xa)
      val repository = new PostgresConnectionRepository
      val setup =
        sql"insert into organization (id, code, name) values ($organizationId, ${organizationId.toString}, 'Connection fence')".update.run *>
          repository.save(original)
      val cleanup =
        sql"delete from connection where organization_id = $organizationId".update.run *>
          sql"delete from organization where id = $organizationId".update.run

      (runner.run(setup) *> (for {
        winnerSaved <- runner.run(repository.saveIfUnmodified(winner, originalUpdatedAt))
        staleSaved <- runner.run(repository.saveIfUnmodified(stale, originalUpdatedAt))
        stored <- runner.run(repository.findById(organizationId, connectionId))
        _ <- IO {
          assert(winnerSaved)
          assert(!staleSaved)
          assertEquals(stored, Some(winner))
        }
      } yield ())).guarantee(runner.run(cleanup).void)
    }.unsafeRunSync()
  }
}
